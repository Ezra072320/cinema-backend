package edu.czu.cinema.domain.movie.sync;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.entity.MovieCrew;
import edu.czu.cinema.domain.movie.entity.Person;
import edu.czu.cinema.domain.movie.mapper.MovieCrewMapper;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.movie.mapper.PersonMapper;
import edu.czu.cinema.domain.movie.seed.MaoyanImportServiceImpl;
import edu.czu.cinema.domain.movie.service.MovieMetadataPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单片事务。同步只新增：已存在的影片绝不更新（补缺走 {@link #repair}），保留本系统 movie.id 和排片、订单等引用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainlandMovieWriter {
    private final MaoyanImportServiceImpl importer;
    private final MovieMapper movieMapper;
    private final PersonMapper personMapper;
    private final MovieCrewMapper crewMapper;
    private final edu.czu.cinema.domain.movie.search.MovieProjectionWriter projection;

    public record Written(Long movieId, boolean created, List<String> missingFields) {}

    @Transactional(rollbackFor = Exception.class)
    public Written write(MainlandMovieFetcher.Fetched fetched) {
        // 硬保证 insert-only：外部 ID 或片名核对命中已有影片时一个字节都不写。
        Movie existing = importer.findOrNew(fetched.snapshot());
        if (existing.getId() != null) {
            log.warn("[mainland-sync] 影片已存在，未更新：{}（movieId={}）", fetched.snapshot().nm(), existing.getId());
            return new Written(existing.getId(), false, existing.getMissingFields());
        }
        Long movieId = importer.importOne(fetched.snapshot());
        if (movieId == null) throw new IllegalStateException("影片入库失败");
        Movie current = movieMapper.selectById(movieId);
        var acceptedCredits = fetched.credits().stream()
                .filter(c -> c.roleType() != 2 || !current.getLockedFields().contains("dir")).toList();
        Set<Integer> roles = new HashSet<>();
        for (var credit : acceptedCredits) roles.add(credit.roleType());
        for (Integer role : roles) crewMapper.delete(new LambdaQueryWrapper<MovieCrew>()
                .eq(MovieCrew::getMovieId, movieId).eq(MovieCrew::getRoleType, role));
        Set<String> seen = new HashSet<>();
        int sort = 0;
        for (var credit : acceptedCredits) {
            if (!seen.add(credit.roleType() + ":" + credit.name() + ":" + credit.character())) continue;
            Person person = credit.tmdbId() != null ? personMapper.selectOne(new LambdaQueryWrapper<Person>()
                    .eq(Person::getTmdbId, credit.tmdbId()).last("LIMIT 1"))
                    : credit.externalId() == null ? null : personMapper.selectOne(new LambdaQueryWrapper<Person>()
                    .eq(Person::getMaoyanId, credit.externalId()).last("LIMIT 1"));
            if (person == null) {
                // 只有还没有猫眼人物 ID 的同名旧人物才允许认领。
                person = personMapper.selectOne(new LambdaQueryWrapper<Person>()
                        .eq(Person::getName, credit.name()).isNull(Person::getMaoyanId).isNull(Person::getTmdbId).last("LIMIT 1"));
            }
            if (person == null) {
                person = new Person();
                person.setName(credit.name());
                person.setMaoyanId(credit.externalId());
                person.setTmdbId(credit.tmdbId());
                person.setProfileUrl(credit.avatar());
                personMapper.insert(person);
            } else {
                if (credit.externalId() != null) person.setMaoyanId(credit.externalId());
                if (credit.tmdbId() != null) person.setTmdbId(credit.tmdbId());
                if (StrUtil.isNotBlank(credit.avatar())) person.setProfileUrl(credit.avatar());
                personMapper.updateById(person);
            }
            MovieCrew crew = new MovieCrew();
            crew.setMovieId(movieId);
            crew.setPersonId(person.getId());
            crew.setRoleType(credit.roleType());
            crew.setCharacterName(credit.character());
            crew.setJobName(switch (credit.roleType()) { case 2 -> "导演"; case 3 -> "编剧"; default -> "演员"; });
            crew.setSortOrder(sort++);
            crewMapper.insert(crew);
        }
        Movie movie = movieMapper.selectById(movieId);
        if (fetched.tmdbId() != null || fetched.maoyanId() != null) {
            if (fetched.tmdbId() != null) {
                if (movie.getTmdbId() != null && !movie.getTmdbId().equals(fetched.tmdbId()))
                    throw new IllegalStateException("TMDB 影片标识冲突，未合并");
                movie.setTmdbId(fetched.tmdbId());
            }
            if (fetched.maoyanId() != null) {
                if (movie.getMaoyanId() != null && !movie.getMaoyanId().equals(fetched.maoyanId()))
                    throw new IllegalStateException("猫眼影片标识冲突，未合并");
                movie.setMaoyanId(fetched.maoyanId());
            }
            movieMapper.updateById(movie);
        }
        projection.changed(movieId);
        List<String> missing = new ArrayList<>(movie.getMissingFields());
        missing.addAll(fetched.warnings());
        return new Written(movieId, true, List.copyOf(missing));
    }

    /**
     * 待映影片状态刷新（轻量更新，区别于 repair 的资料补缺）：
     * 只更新「状态三件套」——时长（0=未公布 → 公布值）、上映日（来源给出新日期时）、
     * 市场状态（按新上映日重新推导 2/1/0）。已公布的非零时长和人工确认字段一律不碰。
     * 仅允许作用于待映影片（上映日在未来，或市场状态仍为 2）；已上映影片走 repair。
     */
    @Transactional(rollbackFor = Exception.class)
    public Written refreshUpcoming(Long movieId, MainlandMovieFetcher.Fetched fetched) {
        Movie movie = movieMapper.lockById(movieId);
        if (movie == null) throw new IllegalStateException("影片已不存在");
        boolean wasUpcoming = movie.isUpcoming() || Integer.valueOf(2).equals(movie.getMarketStatus());
        if (!wasUpcoming) return new Written(movieId, false, movie.getMissingFields());
        var s = fetched.snapshot();
        boolean changed = false;
        // 时长：只在未公布(0)时接收公布值；已公布的不回退、不覆盖（人工补录优先）。
        // 时长约定: runtimeMin=0 表示「待映未公布」，公布后才写入真实值。
        if ((movie.getRuntimeMin() == null || movie.getRuntimeMin() <= 0) && !movie.getLockedFields().contains("runtimeMin")
                && s.dur() != null && s.dur() > 0) {
            movie.setRuntimeMin(s.dur());
            changed = true;
        }
        // 上映日：来源给出不同日期时更新（改档）；人工确认字段受保护。
        if (s.rt() != null && s.rt().length() >= 10 && !movie.getLockedFields().contains("releaseDate")) {
            try {
                java.time.LocalDate rd = java.time.LocalDate.parse(s.rt().substring(0, 10));
                if (!rd.equals(movie.getReleaseDate())) {
                    movie.setReleaseDate(rd);
                    changed = true;
                }
            } catch (RuntimeException ignored) { /* 日期解析失败保留原值。 */ }
        }
        // 市场状态按当前上映日重推导：未来→2 待映；上映 90 天内→1；更早→0。
        Integer derived = movie.isUpcoming() ? 2
                : movie.getReleaseDate() != null && movie.getReleaseDate().isAfter(java.time.LocalDate.now().minusDays(90)) ? 1 : 0;
        if (!derived.equals(movie.getMarketStatus())) {
            movie.setMarketStatus(derived);
            changed = true;
        }
        if (changed) {
            movie.setLastSyncedAt(java.time.LocalDateTime.now());
            movie.setUpdateTime(java.time.LocalDateTime.now());
            movieMapper.updateById(movie);
        }
        if (changed) projection.changed(movieId);
        return new Written(movieId, false, movie.getMissingFields());
    }

    /** 网络请求结束后重新锁行并检查缺项，防止抓取期间的人工补录被覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public Written repair(Long movieId, MainlandMovieFetcher.Fetched fetched) {        Movie movie = movieMapper.lockById(movieId);
        if (movie == null) throw new IllegalStateException("影片已不存在");
        var filtered = MovieMetadataPolicy.filter(movie,fetched.snapshot(),true);
        // 补缺不认领其他数据库影片，更不新建电影；经严格匹配的外部 ID 可供后续直接查询。
        if (fetched.tmdbId() != null) {
            if (movie.getTmdbId() != null && !movie.getTmdbId().equals(fetched.tmdbId()))
                throw new IllegalStateException("TMDB 影片标识冲突，未补缺");
            movie.setTmdbId(fetched.tmdbId());
        }
        movie.setLastRepairAt(java.time.LocalDateTime.now());
        importer.saveBasicMetadata(movie,filtered);
        return new Written(movieId,false,movie.getMissingFields());
    }
}
