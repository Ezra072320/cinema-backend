package edu.czu.cinema.domain.movie.sync;

import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.service.MovieMetadataPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 管理双来源切换：猫眼待映片单为主，TMDB 备用片单兜底。 */
@Service
@RequiredArgsConstructor
public class MovieReleaseSource {
    private final MaoyanClient maoyan;
    private final MainlandMovieFetcher fetcher;
    private final TmdbMovieService tmdb;
    private boolean maoyanAvailable;

    public void beginRepair() { maoyanAvailable = true; }

    public MainlandMovieFetcher.Fetched fetchMissing(Movie movie) {
        MainlandMovieFetcher.Fetched knownExtra = null;
        if (tmdb.configured() && movie.getTmdbId() != null) {
            try {
                knownExtra = tmdb.fetchExisting(movie);
                if (MovieMetadataPolicy.coversMissing(movie,knownExtra.snapshot())) return knownExtra;
            }
            catch (MaoyanClient.SourceUnavailableException ex) { throw ex; }
            catch (RuntimeException ex) { if (movie.getMaoyanId() == null) throw ex; }
        }
        var candidate = new MaoyanClient.Candidate(movie.getMaoyanId() == null ? 0 : movie.getMaoyanId(),
                movie.getTitle(), movie.getReleaseDate() == null ? null : movie.getReleaseDate().toString());
        if (movie.getMaoyanId() != null && maoyanAvailable) {
            MainlandMovieFetcher.Fetched primary = null;
            try {
                primary = fetcher.fetch(candidate);
            } catch (MaoyanClient.SourceUnavailableException ex) { maoyanAvailable = false; }
            catch (RuntimeException ignored) { /* 尝试严格匹配辅助来源。 */ }
            if (primary != null) {
                if (knownExtra != null) return tmdb.supplement(primary,knownExtra);
                if (tmdb.configured()) {
                    try {
                        var extra = tmdb.matchForRepair(movie,primary);
                        if (extra.isPresent()) return tmdb.supplement(primary,extra.get());
                    } catch (MaoyanClient.SourceUnavailableException ex) { throw ex; }
                    catch (RuntimeException ignored) { /* 保留猫眼已取得的资料。 */ }
                }
                return primary;
            }
        }
        if (knownExtra != null) return knownExtra;
        if (tmdb.configured()) {
            var extra = tmdb.matchForRepair(movie,null);
            if (extra.isPresent()) return extra.get();
        }
        throw new IllegalStateException("来源暂无可核实资料或匹配不唯一，请管理员核实补录");
    }

    public record Feed(List<MovieSyncCandidate> movies,String note) {}

    public Feed discover() {
        maoyanAvailable = true;
        try {
            return new Feed(maoyan.currentMovies().stream().map(MovieSyncCandidate::maoyan).toList(),"");
        } catch (RuntimeException ex) {
            if (!tmdb.configured()) throw ex;
            maoyanAvailable = false;
            return new Feed(tmdb.discover(),"猫眼暂不可用，本次使用 TMDB 大陆待映片单补充资料。");
        }
    }

    public MainlandMovieFetcher.Fetched fetch(MovieSyncCandidate candidate,Movie previous) {
        if ("TMDB".equals(candidate.source())) return tmdb.fetch(candidate);
        var original = candidate.maoyan();
        if (!maoyanAvailable) return fallback(original,previous);
        MainlandMovieFetcher.Fetched primary;
        try {
            primary = fetcher.fetch(original);
        } catch (RuntimeException ex) {
            if (!tmdb.configured()) throw ex;
            if (ex instanceof MaoyanClient.SourceUnavailableException) maoyanAvailable = false;
            return fallback(original,previous);
        }
        if (tmdb.configured() && TmdbMovieService.needsSupplement(primary)) {
            try {
                var extra = tmdb.match(original,primary,previous);
                if (extra.isPresent()) return tmdb.supplement(primary,extra.get());
            } catch (RuntimeException ignored) { /* 辅助来源失败不丢弃主来源的有效资料。 */ }
        }
        return primary;
    }

    private MainlandMovieFetcher.Fetched fallback(MaoyanClient.Candidate candidate,Movie previous) {
        return tmdb.match(candidate,null,previous).map(extra -> tmdb.fallback(candidate,extra))
                .orElseThrow(() -> new IllegalStateException("猫眼暂不可用，TMDB 未找到唯一且大陆上映日一致的影片：" + candidate.title()));
    }
}
