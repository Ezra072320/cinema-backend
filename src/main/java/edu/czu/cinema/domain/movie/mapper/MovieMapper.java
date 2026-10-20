package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.vo.MovieSoldVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MovieMapper extends BaseMapper<Movie> {
    /** 行锁取影片（同步/补缺写路径串行化）。FOR UPDATE 是唯一允许保留的手写 SQL 片段。 */
    default Movie lockById(Long id) {
        return selectOne(new LambdaQueryWrapper<Movie>().eq(Movie::getId, id).last("FOR UPDATE"));
    }

    /** 影院销售额榜: 只算真实已支付票款(ticket.status=0 且 order.status IN(2,6)). */
    List<MovieSoldVO> topSales(@Param("limit") int limit);

    /** 公开票房榜: 按 movie.total_box(外部来源全国累计票房)降序, 不含无票房数据的影片. */
    List<MovieSoldVO> topBoxOffice(@Param("limit") int limit);
}
