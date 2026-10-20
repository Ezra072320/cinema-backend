package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.MovieTypeBind;

import java.util.List;

public interface MovieTypeBindMapper extends BaseMapper<MovieTypeBind> {

    default List<Long> typeIdsByMovie(Long movieId) {
        return selectList(new LambdaQueryWrapper<MovieTypeBind>().eq(MovieTypeBind::getMovieId, movieId))
                .stream().map(MovieTypeBind::getTypeId).toList();
    }

    default int removeByMovie(Long movieId) {
        return delete(new LambdaQueryWrapper<MovieTypeBind>().eq(MovieTypeBind::getMovieId, movieId));
    }

    default int removeByType(Long typeId) {
        return delete(new LambdaQueryWrapper<MovieTypeBind>().eq(MovieTypeBind::getTypeId, typeId));
    }
}