package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.UserWant;

import java.util.List;

public interface UserWantMapper extends BaseMapper<UserWant> {

    default List<Long> movieIdsByUser(Long userId) {
        return selectList(new LambdaQueryWrapper<UserWant>().eq(UserWant::getUserId, userId))
                .stream().map(UserWant::getMovieId).toList();
    }

    default int countByMovie(Long movieId) {
        Long count = selectCount(new LambdaQueryWrapper<UserWant>().eq(UserWant::getMovieId, movieId));
        return count == null ? 0 : count.intValue();
    }
}