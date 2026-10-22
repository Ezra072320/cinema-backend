package edu.czu.cinema.internal;

import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.movie.vo.*;
import edu.czu.cinema.domain.statistic.dto.RevenueReport.Distribution;
import edu.czu.cinema.query.CatalogQueryService;
import edu.czu.cinema.remote.OperationJournal;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/internal/catalog")
@RequiredArgsConstructor
public class CatalogController {
    private final MovieMapper mapper;
    private final MovieService movies;
    private final CatalogQueryService queries;
    private final JdbcTemplate jdbc;
    private final OperationJournal journal;

    @GetMapping("/base/{id}")
    public Movie base(@PathVariable Long id) {
        return mapper.selectById(id);
    }

    @GetMapping("/info/{id}")
    public MovieInfoVO info(@PathVariable Long id) {
        return movies.info(id);
    }

    @GetMapping("/sales")
    public List<MovieSoldVO> sales() {
        return movies.topSales();
    }

    @GetMapping("/box-office")
    public List<MovieSoldVO> boxOffice() {
        return movies.topBoxOffice();
    }

    @GetMapping("/want")
    public boolean want(@RequestParam Long movieId, @RequestParam Long userId) {
        return movies.isWant(movieId, userId);
    }

    @PostMapping("/want")
    public boolean toggle(
            @RequestParam Long movieId,
            @RequestParam Long userId,
            @RequestHeader("X-Operation-Id") String key) {
        return journal.run(
                key,
                userId,
                "SET_WANT",
                movieId,
                Boolean.class,
                () -> movies.handleWant(userId, movieId));
    }

    @PostMapping("/want/target")
    public boolean setWant(
            @RequestParam Long movieId,
            @RequestParam Long userId,
            @RequestParam boolean wanted,
            @RequestHeader("X-Operation-Id") String key) {
        return journal.run(
                key,
                userId,
                "SET_WANT",
                List.of(movieId, wanted),
                Boolean.class,
                () -> {
                    if (wanted)
                        jdbc.update(
                                "INSERT INTO user_want_movie(user_id,movie_id) VALUES(?,?) ON"
                                    + " DUPLICATE KEY UPDATE user_id=user_id",
                                userId,
                                movieId);
                    else
                        jdbc.update(
                                "DELETE FROM user_want_movie WHERE user_id=? AND movie_id=?",
                                userId,
                                movieId);
                    return wanted;
                });
    }

    @GetMapping("/wants")
    public List<Long> wants(@RequestParam Long userId) {
        return movies.userWantList(userId);
    }

    @GetMapping("/count")
    public long count() {
        return mapper.selectCount(null);
    }

    @GetMapping("/types")
    public List<Distribution> types() {
        return jdbc.query(
                "SELECT t.name,COUNT(DISTINCT mt.movie_id) AS n FROM movie_to_type mt JOIN `type` t"
                    + " ON t.id=mt.type_id JOIN movie m ON m.id=mt.movie_id GROUP BY t.id,t.name"
                    + " ORDER BY n DESC,t.id",
                (rs, row) -> new Distribution(rs.getString("name"), rs.getLong("n")));
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean showing,
            @RequestParam(required = false) Integer page) {
        return queries.movies(keyword, showing, page);
    }
}
