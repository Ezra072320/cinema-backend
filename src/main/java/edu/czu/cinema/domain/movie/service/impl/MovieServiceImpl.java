package edu.czu.cinema.domain.movie.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.movie.entity.*;
import edu.czu.cinema.domain.movie.enums.CrewRoleType;
import edu.czu.cinema.domain.movie.mapper.*;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.movie.service.MovieMetadataPolicy;
import edu.czu.cinema.domain.movie.vo.*;
import edu.czu.cinema.domain.score.entity.Score;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MovieServiceImpl implements MovieService {

    private final MovieMapper movieMapper;
    private final TypeMapper typeMapper;
    private final MovieTypeBindMapper bindMapper;
    private final UserWantMapper wantMapper;
    private final ScoreMapper scoreMapper;

    /* ===== 2026-10-16 翻新: 新表 mappers ===== */
    private final PersonMapper personMapper;
    private final MovieCrewMapper crewMapper;
    private final MovieImageMapper imageMapper;
    private final MovieVideoMapper videoMapper;
    private final MovieTagMapper tagMapper;
    private final MovieAltTitleMapper altTitleMapper;
    private final edu.czu.cinema.domain.schedule.mapper.ScheduleMapper scheduleMapper;
    private static final String ACTIVE_SHOW = "SELECT 1 FROM `schedule` s WHERE s.movie_id = movie.id AND s.status = 1 AND s.end_time > NOW()";

    @Override
    public PageResult<Movie> page(int page, int size, String title, Integer marketStatus, Boolean showing, Boolean incomplete) {
        Page<Movie> req = Page.of(page, size);
        LambdaQueryWrapper<Movie> wrap = new LambdaQueryWrapper<Movie>()
                .like(title != null && !title.isBlank(), Movie::getTitle, title)
                .eq(marketStatus != null && marketStatus != 3, Movie::getMarketStatus, marketStatus)
                .exists(Boolean.TRUE.equals(showing), ACTIVE_SHOW)
                .notExists(Boolean.FALSE.equals(showing), ACTIVE_SHOW)
                .apply(Boolean.TRUE.equals(incomplete),MovieMetadataPolicy.INCOMPLETE_SQL)
                .orderByDesc(Movie::getReleaseDate).orderByDesc(Movie::getId);
        Page<Movie> result = movieMapper.selectPage(req, wrap);
        enrichShowings(result.getRecords());
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    @Override
    public List<Movie> hotMovies() {
        List<Movie> rows = movieMapper.selectList(new LambdaQueryWrapper<Movie>()
                .exists(ACTIVE_SHOW).orderByDesc(Movie::getReleaseDate));
        enrichShowings(rows);
        return rows;
    }

    private void enrichShowings(List<Movie> rows) {
        if (rows.isEmpty()) return;
        Map<Long, MovieShowSummary> summaries = scheduleMapper.movieShowSummaries(rows.stream().map(Movie::getId).toList())
                .stream().collect(Collectors.toMap(MovieShowSummary::getMovieId, s -> s));
        for (Movie movie : rows) {
            MovieShowSummary summary = summaries.get(movie.getId());
            movie.setHasSchedules(summary != null);
            movie.setScheduleCount(summary == null ? 0 : summary.getScheduleCount());
            movie.setNextShowTime(summary == null ? null : summary.getNextShowTime());
        }
    }

    @Override
    public List<MovieSoldVO> topSales() {
        return movieMapper.topSales(10);
    }

    @Override
    public List<MovieSoldVO> topBoxOffice() {
        return movieMapper.topBoxOffice(10);
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "movieInfo", key = "#movieId", condition = "#movieId != null")
    public MovieInfoVO info(Long movieId) {
        Movie movie = movieMapper.selectById(movieId);
        if (movie == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电影不存在");
        }
        MovieInfoVO vo = new MovieInfoVO();
        enrichShowings(List.of(movie));
        vo.setBase(movie);

        // 类型
        List<Long> typeIds = bindMapper.typeIdsByMovie(movieId);
        if (CollUtil.isNotEmpty(typeIds)) {
            List<Type> types = typeMapper.selectBatchIds(typeIds);
            vo.setTypes(types);
            vo.setTypeNames(types.stream().map(Type::getName).toList());
        }

        vo.setIsWant(false);
        vo.setSoldCount(0L);
        vo.setWantCount((long) wantMapper.countByMovie(movieId));

        // ===== 2026-10-16 翻新: 追加 director / writers / cast / crew / images / videos / tags =====
        enrichMovieInfo(vo, movieId);

        return vo;
    }

    /**
     * 把 movie 自身的新字段平铺到 vo 顶层, 并查 person / movie_crew / movie_image / movie_video / movie_tag.
     * 失败不影响主流程 (只是字段缺失), 与老 data 共存.
     */
    private void enrichMovieInfo(MovieInfoVO vo, Long movieId) {
        Movie m = vo.getBase();
        if (m != null) {
            vo.setOriginalTitle(m.getOriginalTitle());
            vo.setLanguage(m.getLanguage());
            vo.setRuntimeMin(m.getRuntimeMin());
            vo.setPosterUrl(m.getPosterUrl());
            vo.setBackdropUrl(m.getBackdropUrl());
            vo.setTrailerUrl(m.getTrailerUrl());
            vo.setCat(m.getCat());
            vo.setDir(m.getDir());
            vo.setSource(m.getSource());
            vo.setFmt(m.getFmt());
            vo.setShowDays(m.getShowDays());
            vo.setFirstDayBox(m.getFirstDayBox());
            vo.setTotalBox(m.getTotalBox());
            vo.setWishCount(m.getWishCount());
            vo.setScoreCount(m.getScoreCount());
            vo.setMaoyanId(m.getMaoyanId());
        }

        // crew
        List<MovieCrew> rows = crewMapper.listByMovie(movieId);
        if (CollUtil.isNotEmpty(rows)) {
            Set<Long> personIds = new LinkedHashSet<>();
            for (MovieCrew c : rows) personIds.add(c.getPersonId());
            Map<Long, Person> personMap = personMapper.selectBatchIds(personIds).stream()
                    .collect(Collectors.toMap(Person::getId, p -> p, (a, b) -> a));

            List<MovieCastVO> cast = new ArrayList<>();
            List<MovieCrewVO> crewVo = new ArrayList<>();
            for (MovieCrew c : rows) {
                Person p = personMap.get(c.getPersonId());
                if (p == null) continue;
                MovieCrewVO cv = new MovieCrewVO();
                cv.setPerson(p);
                cv.setRoleType(c.getRoleType());
                cv.setCharacterName(c.getCharacterName());
                cv.setJobName(c.getJobName());
                cv.setOrder(c.getSortOrder());
                crewVo.add(cv);
                if (CrewRoleType.ACTOR.getCode() == c.getRoleType()) {
                    MovieCastVO mc = new MovieCastVO();
                    mc.setPerson(p);
                    mc.setCharacter(c.getCharacterName());
                    mc.setOrder(c.getSortOrder());
                    cast.add(mc);
                }
            }
            vo.setCrew(crewVo);
            vo.setCast(cast);

            // director / writers: 找 role_type=2 的第一条
            Person director = crewVo.stream()
                    .filter(c -> c.getRoleType() != null
                            && c.getRoleType().intValue() == CrewRoleType.DIRECTOR.getCode())
                    .findFirst().map(MovieCrewVO::getPerson).orElse(null);
            vo.setDirector(director);
            List<Person> writers = crewVo.stream()
                    .filter(c -> c.getRoleType() != null
                            && c.getRoleType().intValue() == CrewRoleType.SCREENWRITER.getCode())
                    .map(MovieCrewVO::getPerson).toList();
            vo.setWriters(writers);

            // 兼容老 actors 字段 (Person 投影)
            vo.setActors(cast.stream().map(MovieCastVO::getPerson).toList());
        }

        // images
        List<MovieImage> images = imageMapper.listByMovie(movieId);
        if (CollUtil.isNotEmpty(images)) {
            vo.setImages(images.stream().map(i -> {
                MovieImageVO iv = new MovieImageVO();
                BeanUtil.copyProperties(i, iv);
                return iv;
            }).toList());
        }

        // videos
        List<MovieVideo> videos = videoMapper.listByMovie(movieId);
        if (CollUtil.isNotEmpty(videos)) {
            vo.setVideos(videos.stream().map(v -> {
                MovieVideoVO vv = new MovieVideoVO();
                BeanUtil.copyProperties(v, vv);
                vv.setEmbedUrl(toEmbedUrl(v.getSite(), v.getVideoKey()));
                return vv;
            }).toList());
        }

        // tags
        List<MovieTag> tags = tagMapper.listByMovie(movieId);
        if (CollUtil.isNotEmpty(tags)) {
            vo.setTags(tags.stream().map(MovieTag::getTag).toList());
        }
    }

    private static String toEmbedUrl(String site, String key) {
        if (StrUtil.isBlank(key)) return null;
        if ("YouTube".equalsIgnoreCase(site)) {
            return "https://www.youtube.com/embed/" + key;
        }
        return key; // mp4
    }

    @Override
    public List<MovieImageVO> images(Long movieId) {
        return imageMapper.listByMovie(movieId).stream().map(i -> {
            MovieImageVO v = new MovieImageVO();
            BeanUtil.copyProperties(i, v);
            return v;
        }).toList();
    }

    @Override
    public List<MovieVideoVO> videos(Long movieId) {
        return videoMapper.listByMovie(movieId).stream().map(v -> {
            MovieVideoVO vv = new MovieVideoVO();
            BeanUtil.copyProperties(v, vv);
            vv.setEmbedUrl(toEmbedUrl(v.getSite(), v.getVideoKey()));
            return vv;
        }).toList();
    }

    @Override
    public List<MovieCrewVO> crew(Long movieId, Integer roleType) {
        List<MovieCrew> rows = crewMapper.listByMovie(movieId);
        if (CollUtil.isEmpty(rows)) return List.of();
        if (roleType != null) {
            rows = rows.stream().filter(c -> roleType.equals(c.getRoleType())).toList();
        }
        Set<Long> personIds = rows.stream().map(MovieCrew::getPersonId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Person> personMap = personMapper.selectBatchIds(personIds).stream()
                .collect(Collectors.toMap(Person::getId, p -> p, (a, b) -> a));
        List<MovieCrewVO> out = new ArrayList<>();
        for (MovieCrew c : rows) {
            Person p = personMap.get(c.getPersonId());
            if (p == null) continue;
            MovieCrewVO cv = new MovieCrewVO();
            cv.setPerson(p);
            cv.setRoleType(c.getRoleType());
            cv.setCharacterName(c.getCharacterName());
            cv.setJobName(c.getJobName());
            cv.setOrder(c.getSortOrder());
            out.add(cv);
        }
        return out;
    }

    @Override
    public String describeBrief(String keyword) {
        List<Movie> movies = movieMapper.selectList(
                new LambdaQueryWrapper<Movie>().like(Movie::getTitle, keyword).last("LIMIT 3"));
        if (movies.isEmpty()) {
            return "暂未匹配到电影，可以换个关键词试试。";
        }
        return movies.stream()
                .map(m -> m.getTitle() + "（" + m.getArea() + " / " + m.getReleaseDate() + "）")
                .collect(Collectors.joining("；"));
    }

    @Override
    @Transactional
    public boolean handleWant(Long userId, Long movieId) {
        // 2026-10-19 修复: 返回值语义是「切换后的状态」(true=现在想看), 不是「操作成功」.
        // 早先两个分支都 return true, 前端 WantButton 拿它驱动提示文案/按钮态,
        // 于是取消想看后仍弹「已加入想看」且按钮停在「已想看」不回退.
        // 先删后插: 删到行 = 之前想看, 本次是取消; 没删到 = 本次加入.
        // (比 selectCount 再分支少一次查询, 且天然规避 check-then-act 的重复插入窗口)
        int removed = wantMapper.delete(new LambdaQueryWrapper<UserWant>()
                .eq(UserWant::getUserId, userId)
                .eq(UserWant::getMovieId, movieId));
        if (removed > 0) {
            return false;
        }
        UserWant w = new UserWant();
        w.setUserId(userId);
        w.setMovieId(movieId);
        wantMapper.insert(w);
        return true;
    }

    @Override
    public List<Long> userWantList(Long userId) {
        return wantMapper.movieIdsByUser(userId);
    }

    @Override
    public boolean isWant(Long movieId, Long userId) {
        if (userId == null) return false;
        Long cnt = wantMapper.selectCount(new LambdaQueryWrapper<UserWant>()
                .eq(UserWant::getMovieId, movieId)
                .eq(UserWant::getUserId, userId));
        return cnt != null && cnt > 0;
    }

    @Override
    @Transactional
    public boolean updateScoreByOrder(Long orderId, Integer score) {
        return false;
    }

    @Override
    public PageResult<Type> listTypes(String name, int page, int size) {
        Page<Type> req = Page.of(page, size);
        LambdaQueryWrapper<Type> wrap = new LambdaQueryWrapper<Type>()
                .like(name != null && !name.isBlank(), Type::getName, name)
                .orderByAsc(Type::getId);
        Page<Type> result = typeMapper.selectPage(req, wrap);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

}
