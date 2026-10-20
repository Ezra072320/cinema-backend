package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import edu.czu.cinema.domain.movie.entity.MovieCrew;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MovieCrewMapper extends BaseMapper<MovieCrew> {

    /**
     * 列出某部影片的全部 crew 行,按 role_type 升序 + sort_order 升序.
     */
    default List<MovieCrew> listByMovie(Long movieId) {
        return selectList(new LambdaQueryWrapper<MovieCrew>()
                .eq(MovieCrew::getMovieId, movieId)
                .orderByAsc(MovieCrew::getRoleType).orderByAsc(MovieCrew::getSortOrder).orderByAsc(MovieCrew::getId));
    }

    /**
     * 分页(后台 RoleManage 用)：走 MP 分页，count 由拦截器计算。
     */
    default IPage<MovieCrew> pageByMovie(IPage<MovieCrew> page, Long movieId) {
        return selectPage(page, new LambdaQueryWrapper<MovieCrew>()
                .eq(MovieCrew::getMovieId, movieId)
                .orderByAsc(MovieCrew::getRoleType).orderByAsc(MovieCrew::getSortOrder));
    }

    default int deleteByMovie(Long movieId) {
        return delete(new LambdaQueryWrapper<MovieCrew>().eq(MovieCrew::getMovieId, movieId));
    }
}