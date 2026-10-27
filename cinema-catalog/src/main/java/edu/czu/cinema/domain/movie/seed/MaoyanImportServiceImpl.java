package edu.czu.cinema.domain.movie.seed;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.domain.movie.entity.*;
import edu.czu.cinema.domain.movie.enums.CrewRoleType;
import edu.czu.cinema.domain.movie.enums.ImageKind;
import edu.czu.cinema.domain.movie.mapper.*;
import edu.czu.cinema.domain.movie.service.MovieMetadataPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 把单条 {@link MaoyanMovieSnapshot} 解析并落库.
 *
 * <p>设计要点:
 *  - 按各来源独立 ID 匹配；跨来源匹配还要核对原名或年份，不盲目合并同名电影。
 *  - 卫星资源仅在本次实际获取该资源时替换，来源缺项不清空旧资料。
 *  - person 按 name 去重 insert; 老 data 迁移已经在 SQL migration 阶段完成.
 *  - cat 字段 (csv) 同时写原值到 movie.cat 与按 , 拆分 upsert 到 type / movie_to_type.
 *  - dir 字段 (csv) 按 , 拆分 → person upsert → movie_crew(role_type=DIRECTOR).
 *  - star 字段 csv (形如 "李四 饰 阿明,王五 饰 阿华") 拆 → person + movie_crew(ACTOR, character_name).
 *  - boxInfo.totalBox / firstDayBox / showDays 落到 movie.total_box / first_day_box / show_days.
 *  - videos (单条 mp4) 写到 movie_video, 同时 movie.trailer_url 冗余.
 *  - images 写 poster / backdrop / stills 三类.
 *  - filmTags 写 movie_tag.
 *  - aka 写 movie_alt_title.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaoyanImportServiceImpl {

    private final MovieMapper movieMapper;
    private final PersonMapper personMapper;
    private final MovieCrewMapper crewMapper;
    private final MovieImageMapper imageMapper;
    private final MovieVideoMapper videoMapper;
    private final MovieTagMapper tagMapper;
    private final MovieAltTitleMapper altTitleMapper;
    private final TypeMapper typeMapper;
    private final MovieTypeBindMapper bindMapper;
    private final edu.czu.cinema.domain.movie.search.MovieProjectionWriter projection;

    /**
     * 入口: 把快照入库. 已存在则覆盖主表 + 重建 satellite.
     * 失败时事务回滚, 不会污染主表.
     */
    @Transactional(rollbackFor = Exception.class)
    public Long importOne(MaoyanMovieSnapshot s) {
        if (s == null || s.nm() == null || s.nm().isBlank()) {
            log.warn("[maoyan-import] skip snapshot without title");
            return null;
        }
        // 1. upsert movie
        Movie movie = findOrNew(s);
        if (movie.getId() != null) movie = movieMapper.lockById(movie.getId());
        if (movie == null) throw new IllegalStateException("影片已不存在");
        s = MovieMetadataPolicy.filter(movie, s, false);
        fillMovie(movie, s);
        if (movie.getId() == null) {
            movieMapper.insert(movie);
        } else {
            movieMapper.updateById(movie);
        }
        Long movieId = movie.getId();

        // 来源缺项时保留旧资料；只替换本次确实获取成功的资源。
        if (StrUtil.isNotBlank(s.dir())) crewMapper.delete(new LambdaQueryWrapper<MovieCrew>()
                .eq(MovieCrew::getMovieId, movieId).eq(MovieCrew::getRoleType, 2));
        if (StrUtil.isNotBlank(s.star())) crewMapper.delete(new LambdaQueryWrapper<MovieCrew>()
                .eq(MovieCrew::getMovieId, movieId).eq(MovieCrew::getRoleType, 1));
        if (StrUtil.isNotBlank(s.img())) deleteImages(movieId, 1);
        if (StrUtil.isNotBlank(s.bgImg())) deleteImages(movieId, 2);
        if (CollUtil.isNotEmpty(s.photos())) deleteImages(movieId, 3);
        if (StrUtil.isNotBlank(s.videoUrl())) videoMapper.deleteByMovie(movieId);
        if (CollUtil.isNotEmpty(s.filmTags())) tagMapper.deleteByMovie(movieId);
        if (CollUtil.isNotEmpty(s.aka())) altTitleMapper.deleteByMovie(movieId);
        if (StrUtil.isNotBlank(s.cat())) bindMapper.removeByMovie(movieId);

        // 3. cat -> type / movie_to_type
        upsertTypes(movieId, s.cat());

        // 4. dir -> person / movie_crew(DIRECTOR)
        upsertCrewFromCsv(movieId, s.dir(), CrewRoleType.DIRECTOR, null);

        // 5. star -> person / movie_crew(ACTOR, character_name)
        upsertActorsFromStar(movieId, s.star());

        // 6. photos / img / bgImg -> movie_image
        upsertImages(movieId, s);

        // 7. videoUrl -> movie_video
        upsertVideo(movieId, s.videoUrl());

        // 8. filmTags -> movie_tag
        upsertTags(movieId, s.filmTags());

        // 9. aka -> movie_alt_title
        upsertAltTitles(movieId, s.aka());

        projection.changed(movieId);
        log.info("[maoyan-import] upserted maoyanId={} title={} -> movieId={}", s.id(), s.nm(), movieId);
        return movieId;
    }

    /** 只更新基本资料及其类型、导演、海报关联，不改评分、来源、市场状态。调用者持有影片行锁。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveBasicMetadata(Movie m, MaoyanMovieSnapshot s) {
        saveBasicMetadata(m, s, false);
    }

    /**
     * @param manualOverride true 表示人工核实写入：本次字段刚刚被管理员确认并加锁，
     *                       必须绕过 lockedFields 过滤；同步/补缺路径传 false（P1-M6 写入口兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveBasicMetadata(Movie m, MaoyanMovieSnapshot s, boolean manualOverride) {
        // P1-M6：写入口内聚强制 lockedFields 保护——即使调用方未先 filter，也不让补缺覆盖人工确认字段。
        if (!manualOverride) {
            s = MovieMetadataPolicy.filter(m, s, false);
        }
        if (StrUtil.isNotBlank(s.story())) m.setSummary(s.story());
        else if (StrUtil.isNotBlank(s.intro())) m.setSummary(s.intro());
        if (s.dur() != null && s.dur() > 0) m.setRuntimeMin(s.dur());
        if (StrUtil.isNotBlank(s.rt())) m.setReleaseDate(LocalDate.parse(s.rt()));
        if (StrUtil.isNotBlank(s.src())) m.setArea(s.src());
        if (StrUtil.isNotBlank(s.language())) m.setLanguage(s.language());
        if (StrUtil.isNotBlank(s.cat())) {
            m.setCat(s.cat());
            bindMapper.removeByMovie(m.getId());
            upsertTypes(m.getId(), s.cat());
        }
        if (StrUtil.isNotBlank(s.dir())) {
            m.setDir(s.dir());
            crewMapper.delete(new LambdaQueryWrapper<MovieCrew>().eq(MovieCrew::getMovieId,m.getId()).eq(MovieCrew::getRoleType,2));
            upsertCrewFromCsv(m.getId(),s.dir(),CrewRoleType.DIRECTOR,null);
        }
        if (StrUtil.isNotBlank(s.img())) {
            m.setPosterUrl(s.img());
            deleteImages(m.getId(),1);
            upsertImages(m.getId(),s);
        }
        m.setUpdateTime(LocalDateTime.now());
        movieMapper.updateById(m);
        projection.changed(m.getId());
    }

    /* ====================== helpers ====================== */

    /**
     * 定位要覆盖的影片行, 两级匹配:
     * <ol>
     *   <li>外部 ID (maoyan_id) —— 正常路径, 保证同一条快照反复导入是幂等的.</li>
     *   <li>片名兜底 —— 换了数据源 (猫眼 → 豆瓣) 时外部 ID 会变, 只靠 ID 匹配
     *       会把同一部片子插成两行, 老行的死链海报也清不掉. 按片名认领可以
     *       把旧行直接更新掉.</li>
     * </ol>
     * <p>P1-M5: 片名兜底先按主表标题精确匹配; 未命中再并入别名(movie_alt_title)匹配，
     * 并做标题归一化(去空白/标点/全半角)，避免译名不一致/别名才叫该名被重复插入。
     * 同名但无日期/原名的残缺记录仍保守不合并（宁缺勿错）。
     */
    public Movie findOrNew(MaoyanMovieSnapshot s) {
        if (s.id() != null) {
            Movie byId = movieMapper.selectOne(new LambdaQueryWrapper<Movie>()
                    .eq("TMDB".equalsIgnoreCase(s.source()) ? Movie::getTmdbId : Movie::getMaoyanId, s.id()).last("LIMIT 1"));
            if (byId != null) return byId;
        }
        List<Movie> candidates = new ArrayList<>();
        candidates.addAll(movieMapper.selectList(new LambdaQueryWrapper<Movie>()
                .eq(Movie::getTitle, s.nm())));
        // 别名命中：优先精确别名，未命中时用归一化标题比对，避免译名差异漏合并。
        List<MovieAltTitle> exactAliases = altTitleMapper.selectList(new LambdaQueryWrapper<MovieAltTitle>()
                .eq(MovieAltTitle::getTitle, s.nm()));
        if (exactAliases.isEmpty() && StrUtil.isNotBlank(s.nm())) {
            String key = normalizeTitle(s.nm());
            exactAliases = altTitleMapper.selectList(null).stream()
                    .filter(a -> StrUtil.isNotBlank(a.getTitle()) && normalizeTitle(a.getTitle()).equals(key)).toList();
        }
        if (!exactAliases.isEmpty()) {
            var ids = exactAliases.stream().map(MovieAltTitle::getMovieId).filter(java.util.Objects::nonNull).distinct().toList();
            if (!ids.isEmpty()) candidates.addAll(movieMapper.selectList(new LambdaQueryWrapper<Movie>()
                    .in(Movie::getId, ids)));
        }
        List<Movie> matches = candidates.stream().distinct().filter(m -> sameFilm(m, s)).toList();
        if (matches.size() > 1) throw new IllegalStateException("同名影片匹配不唯一，未自动合并：" + s.nm());
        return matches.isEmpty() ? new Movie() : matches.getFirst();
    }

    static boolean sameFilm(Movie movie, MaoyanMovieSnapshot snapshot) {
        Integer currentId = "TMDB".equalsIgnoreCase(snapshot.source()) ? movie.getTmdbId() : movie.getMaoyanId();
        if (currentId != null && !currentId.equals(snapshot.id())) return false;
        if (StrUtil.isNotBlank(movie.getOriginalTitle()) && StrUtil.isNotBlank(snapshot.enm())
                && movie.getOriginalTitle().equalsIgnoreCase(snapshot.enm())) return true;
        if (movie.getReleaseDate() != null && snapshot.rt() != null && snapshot.rt().length() >= 4) {
            return String.valueOf(movie.getReleaseDate().getYear()).equals(snapshot.rt().substring(0, 4));
        }
        return false; // 无年份/原名的同名电影不猜测合并。
    }

    /** 标题归一化：去标点、空白、全半角（NFKC）统一，供判重跨译名/别名比对。 */
    static String normalizeTitle(String value) {
        if (value == null) return "";
        String nfkc = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC);
        return nfkc.replaceAll("[\\p{P}\\p{Z}\\s]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private void deleteImages(Long movieId, int kind) {
        imageMapper.delete(new LambdaQueryWrapper<MovieImage>()
                .eq(MovieImage::getMovieId, movieId).eq(MovieImage::getKind, kind));
    }

    private void fillMovie(Movie m, MaoyanMovieSnapshot s) {
        m.setTitle(s.nm());
        // 以下字段走「只填空不覆盖」: 快照里是 null/空且库里已有非空值时, 保留库里原值.
        // 不保护的话, 豆瓣 rexxar 限流时 null 字段会清掉库里已有数据 —— 比如《千与千寻》
        // 的 original_title="千と千尋の神隠し" 会被 s.enm()=null 抹成 NULL, 这是数据破坏.
        if (StrUtil.isNotBlank(s.enm()) || StrUtil.isBlank(m.getOriginalTitle())) {
            m.setOriginalTitle(StrUtil.blankToDefault(s.enm(), m.getOriginalTitle()));
        }
        if (StrUtil.isNotBlank(s.language()) || StrUtil.isBlank(m.getLanguage())) {
            m.setLanguage(StrUtil.blankToDefault(s.language(), m.getLanguage()));
        }
        // 时长: dur>0 才写；dur 缺失/0 保持 runtime_min=0（约定：待映未公布时长）
        if (s.dur() != null && s.dur() > 0) {
            m.setRuntimeMin(s.dur());
        }
        if (m.getRuntimeMin() == null) m.setRuntimeMin(0);
        if (StrUtil.isNotBlank(s.img())) { m.setPosterUrl(s.img()); }
        if (StrUtil.isNotBlank(s.bgImg())) m.setBackdropUrl(s.bgImg());
        if (StrUtil.isNotBlank(s.videoUrl())) m.setTrailerUrl(s.videoUrl());
        if (StrUtil.isNotBlank(s.cat())) m.setCat(s.cat());
        if (StrUtil.isNotBlank(s.dir())) m.setDir(s.dir());
        // source: 只在「新片」时写, upsert 已有影片时保留库里的原值.
        // 同步快照总是 source="DOUBAN", 不能让同步把基线人工写的 MANUAL 覆盖.
        if (m.getId() == null || "MAOYAN".equalsIgnoreCase(s.source()) || "TMDB".equalsIgnoreCase(s.source())) {
            m.setSource(StrUtil.blankToDefault(s.source(), "DOUBAN"));
        }
        if ("TMDB".equalsIgnoreCase(s.source())) m.setTmdbId(s.id());
        else m.setMaoyanId(s.id());
        if (StrUtil.isNotBlank(s.fmt())) m.setFmt(s.fmt());
        if (s.wishCnt() != null) m.setWishCount(s.wishCnt());
        // 猫眼的有效评分可以刷新，评分来源单独保存；没有开分时保留已有有效评分。
        // 库里已有评分就一律不动, 只给没评分的填空。
        //
        // 不能写成 `s.sc() != null ? s.sc() : ZERO` —— 豆瓣临时没返回评分时，
        // 那句会把库里已有的评分抹成 0，而且是被"同步"这个看起来最可信的操作抹掉的。
        if (s.sc() != null && s.sc().signum() > 0
                && ("MAOYAN".equalsIgnoreCase(s.source()) || (s.source() != null && s.source().equalsIgnoreCase(m.getScoreSource()))
                    || m.getScore() == null || m.getScore().signum() == 0)) {
            m.setScore(s.sc());
            m.setScoreSource(s.source());
            if (s.snum() != null) m.setScoreCount(s.snum());
        }
        String summary = StrUtil.blankToDefault(s.story(), s.intro());
        if (StrUtil.isNotBlank(summary)) m.setSummary(summary);
        // area: 同样的"只填空"策略
        if (StrUtil.isNotBlank(s.src()) || StrUtil.isBlank(m.getArea())) {
            m.setArea(StrUtil.blankToDefault(s.src(), m.getArea()));
        }
        // 状态: 1=热映 2=即将上映 0=经典
        // 快照显式给了 status 就用它(种子文件可控); 否则新片按上映日/上映天数推导,
        // 已有影片保留原有状态不被覆盖.
        boolean preserveConfirmedMarket = "TMDB".equalsIgnoreCase(s.source()) && Integer.valueOf(0).equals(s.status())
                && m.getMaoyanId()!=null && m.getMarketStatus()!=null;
        if (s.status() != null && !preserveConfirmedMarket) {
            m.setMarketStatus(s.status());
        } else if (m.getMarketStatus() == null) {
            m.setMarketStatus(deriveStatus(s));
        }
        if (s.rt() != null && s.rt().length() >= 10) {
            try {
                LocalDate rd = LocalDate.parse(s.rt().substring(0, 10));
                // 保护: 库里已有正确日期且快照是兜底值 (yyyy-01-01 且不是首日) 时, 保留库里原值
                // 实现: 如果库里 release_date 已有值, 且快照日期是 "<year>-01-01" 这种兜底,
                //       且库里日期不是 01-01 (说明库里已经是真实日), 则不覆盖
                if (m.getReleaseDate() != null) {
                    String snapKey = rd.toString();
                    String curKey = m.getReleaseDate().toString();
                    boolean snapIsFiller = snapKey.endsWith("-01-01");
                    boolean curIsReal = !curKey.endsWith("-01-01");
                    if ("MAOYAN".equalsIgnoreCase(s.source()) || "TMDB".equalsIgnoreCase(s.source()) || !(snapIsFiller && curIsReal)) {
                        m.setReleaseDate(rd);
                    }
                } else {
                    m.setReleaseDate(rd);
                }
            } catch (Exception ignored) {}
        }
        if (s.boxInfo() != null) {
            m.setTotalBox(orZero(s.boxInfo().totalBox()));
            m.setFirstDayBox(orZero(s.boxInfo().firstDayBox()));
            m.setShowDays(s.boxInfo().showDays() == null ? 0 : s.boxInfo().showDays());
        }
        m.setUpdateTime(LocalDateTime.now());
        if ("MAOYAN".equalsIgnoreCase(s.source()) || "TMDB".equalsIgnoreCase(s.source())) m.setLastSyncedAt(LocalDateTime.now());
    }

    /**
     * 推导排档状态: 2 即将上映 / 1 热映 / 0 经典.
     *
     * <p><b>没有 showDays 时用上映日期的年龄兜底</b>。从豆瓣片库发现来的老片天然没有
     * {@code boxInfo}（豆瓣不提供票房），旧写法会一路落到 {@code return 1}，
     * 结果 1931 年的《城市之光》被标成"热映"，首页「正在热映」区会被几十部老片淹掉。
     */
    private static int deriveStatus(MaoyanMovieSnapshot s) {
        try {
            if (s.rt() != null && s.rt().length() >= 10) {
                LocalDate rd = LocalDate.parse(s.rt().substring(0, 10));
                LocalDate today = LocalDate.now();
                if (rd.isAfter(today)) return 2; // SOON
                int showDays = (s.boxInfo() != null && s.boxInfo().showDays() != null)
                        ? s.boxInfo().showDays()
                        : (int) ChronoUnit.DAYS.between(rd, today);
                if (showDays >= 90) return 0; // CLASSIC: 下档 90 天以上
            }
        } catch (Exception ignored) {}
        return 1; // NOW
    }

    private void upsertTypes(Long movieId, String cat) {
        if (StrUtil.isBlank(cat)) return;
        for (String name : cat.split(",")) {
            String n = name.trim();
            if (n.isEmpty()) continue;
            Type t = typeMapper.selectOne(new LambdaQueryWrapper<Type>().eq(Type::getName, n).last("LIMIT 1"));
            if (t == null) {
                t = new Type();
                t.setName(n);
                typeMapper.insert(t);
            }
            MovieTypeBind b = new MovieTypeBind();
            b.setMovieId(movieId);
            b.setTypeId(t.getId());
            bindMapper.insert(b);
        }
    }

    /**
     * dir 字段格式: "张三,李四" (猫眼示例).
     */
    private void upsertCrewFromCsv(Long movieId, String csv, CrewRoleType role, String jobLabel) {
        if (StrUtil.isBlank(csv)) return;
        int order = 0;
        for (String name : csv.split(",")) {
            String n = name.trim();
            if (n.isEmpty()) continue;
            Person p = personMapper.findByName(n);
            if (p == null) {
                p = new Person();
                p.setName(n);
                personMapper.insert(p);
            }
            MovieCrew mc = new MovieCrew();
            mc.setMovieId(movieId);
            mc.setPersonId(p.getId());
            mc.setRoleType(role.getCode());
            mc.setJobName(role == CrewRoleType.DIRECTOR ? "导演" : jobLabel);
            mc.setSortOrder(order++);
            crewMapper.insert(mc);
        }
    }

    /**
     * star 字段格式: "李四 饰 阿明,王五 饰 阿华".
     * 若没"饰 X"格式则整段作 name, character 留空.
     */
    private void upsertActorsFromStar(Long movieId, String star) {
        if (StrUtil.isBlank(star)) return;
        int order = 0;
        for (String entry : star.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String name;
            String character = null;
            int idx = e.indexOf("饰");
            if (idx > 0) {
                name = e.substring(0, idx).trim();
                character = e.substring(idx + 1).trim();
            } else {
                name = e;
            }
            if (name.isEmpty()) continue;
            Person p = personMapper.findByName(name);
            if (p == null) {
                p = new Person();
                p.setName(name);
                personMapper.insert(p);
            }
            MovieCrew mc = new MovieCrew();
            mc.setMovieId(movieId);
            mc.setPersonId(p.getId());
            mc.setRoleType(CrewRoleType.ACTOR.getCode());
            mc.setCharacterName(character);
            mc.setSortOrder(order++);
            crewMapper.insert(mc);
        }
    }

    private void upsertImages(Long movieId, MaoyanMovieSnapshot s) {
        // poster
        if (StrUtil.isNotBlank(s.img())) {
            warnIfBrokenUrl(s.img(), "img", s.nm());
            MovieImage img = new MovieImage();
            img.setMovieId(movieId);
            img.setKind(ImageKind.POSTER.getCode());
            img.setUrl(s.img());
            imageMapper.insert(img);
        }
        // backdrop
        if (StrUtil.isNotBlank(s.bgImg())) {
            warnIfBrokenUrl(s.bgImg(), "bgImg", s.nm());
            MovieImage img = new MovieImage();
            img.setMovieId(movieId);
            img.setKind(ImageKind.BACKDROP.getCode());
            img.setUrl(s.bgImg());
            imageMapper.insert(img);
        }
        // stills
        if (CollUtil.isNotEmpty(s.photos())) {
            for (String url : s.photos()) {
                if (StrUtil.isBlank(url)) continue;
                warnIfBrokenUrl(url, "photo", s.nm());
                MovieImage img = new MovieImage();
                img.setMovieId(movieId);
                img.setKind(ImageKind.STILL.getCode());
                img.setUrl(url);
                imageMapper.insert(img);
            }
        }
    }

    /**
     * 检测常见 404 / 占位图域名, 失败时 warn 但不阻断写入.
     * (前端 MovieCard 已加 @error 兜底, 这里只是给后端运维一个 warning 信号.)
     */
    private static void warnIfBrokenUrl(String url, String field, String title) {
        if (url == null) return;
        String lower = url.toLowerCase();
        if (lower.contains("/daodaos/")
                || lower.contains("/moviemachine/")
                || lower.contains("/_test_/")
                || lower.contains("p0.pic.pstatp.com")
                // 占位图床: 浏览器侧经常加载不出来, 不该再进种子数据
                || lower.contains("placehold.co")
                || lower.contains("dummyimage.com")
                // 豆瓣图床有防盗链, 直接给前端会在浏览器里 418, 必须转存 OSS
                || lower.contains("doubanio.com")
                || lower.endsWith(".404")) {
            log.warn("[maoyan-import] {} url looks unreachable ({}): title={}, url={}",
                    field, "placeholder/known-404-domain", title, url);
        }
    }

    private void upsertVideo(Long movieId, String url) {
        if (StrUtil.isBlank(url)) return;
        MovieVideo v = new MovieVideo();
        v.setMovieId(movieId);
        v.setSite("mp4");
        v.setVideoKey(url);
        v.setName("预告片");
        v.setVideoType("Trailer");
        v.setOfficial(1);
        videoMapper.insert(v);
    }

    private void upsertTags(Long movieId, List<String> tags) {
        if (CollUtil.isEmpty(tags)) return;
        List<MovieTag> rows = new ArrayList<>();
        for (String t : tags) {
            String tag = t == null ? null : t.trim();
            if (StrUtil.isBlank(tag)) continue;
            MovieTag mt = new MovieTag();
            mt.setMovieId(movieId);
            mt.setTag(tag);
            rows.add(mt);
        }
        if (!rows.isEmpty()) {
            // 用 insert 走 single,简单可靠
            rows.forEach(tagMapper::insert);
        }
    }

    private void upsertAltTitles(Long movieId, List<String> akas) {
        if (CollUtil.isEmpty(akas)) return;
        for (String a : akas) {
            if (StrUtil.isBlank(a)) continue;
            MovieAltTitle t = new MovieAltTitle();
            t.setMovieId(movieId);
            t.setTitle(a.trim());
            altTitleMapper.insert(t);
        }
    }

    private static String firstChar(String s) {
        return StrUtil.isBlank(s) ? null : s.split(",")[0].trim();
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
