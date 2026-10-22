package edu.czu.cinema.query;

import edu.czu.cinema.domain.movie.vo.MovieSoldVO;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeSalesQuery {
    private final JdbcTemplate jdbc;

    public List<MovieSoldVO> sales() {
        return jdbc.query(
                "SELECT m.id,m.title,m.poster_url,m.total_box,SUM(t.price) AS box_office,COUNT(*)"
                    + " AS sold FROM movie_snapshot m JOIN schedule s ON s.movie_id=m.id JOIN"
                    + " ticket t ON t.schedule_id=s.id JOIN `order` o ON o.id=t.order_id WHERE"
                    + " t.status=0 AND t.order_id<>-1 AND o.status IN(2,6) GROUP BY"
                    + " m.id,m.title,m.poster_url,m.total_box HAVING box_office>0 ORDER BY"
                    + " box_office DESC,sold DESC LIMIT 10",
                (rs, n) -> {
                    MovieSoldVO v = new MovieSoldVO();
                    v.setMovieId(rs.getLong("id"));
                    v.setTitle(rs.getString("title"));
                    v.setPosterUrl(rs.getString("poster_url"));
                    v.setTotalBox(rs.getBigDecimal("total_box"));
                    v.setBoxOffice(rs.getBigDecimal("box_office"));
                    v.setSoldCount(rs.getLong("sold"));
                    return v;
                });
    }
}
