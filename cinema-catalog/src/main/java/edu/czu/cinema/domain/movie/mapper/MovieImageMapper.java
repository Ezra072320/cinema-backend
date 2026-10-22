package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.MovieImage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MovieImageMapper extends BaseMapper<MovieImage> {

    default List<MovieImage> listByMovie(Long movieId) {
        return selectList(new LambdaQueryWrapper<MovieImage>()
                .eq(MovieImage::getMovieId, movieId).orderByAsc(MovieImage::getId));
    }

    default int deleteByMovie(Long movieId) {
        return delete(new LambdaQueryWrapper<MovieImage>().eq(MovieImage::getMovieId, movieId));
    }
}