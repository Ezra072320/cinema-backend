package edu.czu.cinema.domain.score.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.score.service.ReviewQueryService;
import edu.czu.cinema.domain.score.vo.MovieReviewPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/movie")
@RequiredArgsConstructor
public class MovieReviewController {
    private final ReviewQueryService queries;
    private final MovieMapper movies;

    @GetMapping("/{id}/reviews")
    public R<MovieReviewPage> reviews(@PathVariable Long id, @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "10") int limit,
                                     @RequestParam(defaultValue = "newest") String sort) {
        if (movies.selectById(id) == null) throw new BusinessException(ResultCode.NOT_FOUND, "影片不存在");
        Long viewer = "USER".equals(ContextHolder.currentRole()) ? ContextHolder.currentId() : null;
        return R.ok(queries.movie(id, viewer, page, limit, sort));
    }
}
