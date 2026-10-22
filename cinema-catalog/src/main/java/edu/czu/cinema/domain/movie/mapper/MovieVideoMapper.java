package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.MovieVideo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MovieVideoMapper extends BaseMapper<MovieVideo> {

    default List<MovieVideo> listByMovie(Long movieId) {
        return selectList(new LambdaQueryWrapper<MovieVideo>()
                .eq(MovieVideo::getMovieId, movieId).orderByAsc(MovieVideo::getId));
    }

    default int deleteByMovie(Long movieId) {
        return delete(new LambdaQueryWrapper<MovieVideo>().eq(MovieVideo::getMovieId, movieId));
    }
}