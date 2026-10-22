package edu.czu.cinema.snapshot;

import edu.czu.cinema.domain.movie.entity.Movie;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class MovieSnapshotRepository {
    private final JdbcTemplate jdbc;

    public Movie selectById(Long id) {
        var rows = selectBatchIds(List.of(id));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Movie> selectBatchIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT id,title,poster_url,runtime_min,total_box FROM movie_snapshot WHERE id IN ("
                        + String.join(",", Collections.nCopies(ids.size(), "?"))
                        + ")",
                (rs, n) -> {
                    Movie m = new Movie();
                    m.setId(rs.getLong("id"));
                    m.setTitle(rs.getString("title"));
                    m.setPosterUrl(rs.getString("poster_url"));
                    m.setRuntimeMin(rs.getObject("runtime_min", Integer.class));
                    m.setTotalBox(rs.getBigDecimal("total_box"));
                    return m;
                },
                ids.toArray());
    }

    public void save(Movie m) {
        jdbc.update(
                "INSERT INTO movie_snapshot(id,title,poster_url,runtime_min,total_box)"
                    + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " title=VALUES(title),poster_url=VALUES(poster_url),runtime_min=VALUES(runtime_min),total_box=VALUES(total_box)",
                m.getId(),
                m.getTitle(),
                m.getPosterUrl(),
                m.getRuntimeMin(),
                m.getTotalBox());
    }
}
