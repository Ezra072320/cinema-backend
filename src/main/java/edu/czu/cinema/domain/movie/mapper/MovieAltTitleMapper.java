package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.MovieAltTitle;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MovieAltTitleMapper extends BaseMapper<MovieAltTitle> {

    default List<MovieAltTitle> listByMovie(Long movieId) {
        return selectList(new LambdaQueryWrapper<MovieAltTitle>()
                .eq(MovieAltTitle::getMovieId, movieId).orderByAsc(MovieAltTitle::getId));
    }

    default int deleteByMovie(Long movieId) {
        return delete(new LambdaQueryWrapper<MovieAltTitle>().eq(MovieAltTitle::getMovieId, movieId));
    }
}