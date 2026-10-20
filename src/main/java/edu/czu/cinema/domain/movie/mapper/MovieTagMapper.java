package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.MovieTag;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MovieTagMapper extends BaseMapper<MovieTag> {

    default List<MovieTag> listByMovie(Long movieId) {
        return selectList(new LambdaQueryWrapper<MovieTag>()
                .eq(MovieTag::getMovieId, movieId).orderByAsc(MovieTag::getId));
    }

    default int deleteByMovie(Long movieId) {
        return delete(new LambdaQueryWrapper<MovieTag>().eq(MovieTag::getMovieId, movieId));
    }
}