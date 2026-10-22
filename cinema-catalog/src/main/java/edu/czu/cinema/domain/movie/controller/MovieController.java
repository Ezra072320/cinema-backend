package edu.czu.cinema.domain.movie.controller;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.JwtUtil;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.movie.vo.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "03. 电影域")
@RestController
@RequestMapping("/api/movie")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;
    private final JwtUtil jwtUtil;

    @Operation(summary = "首页加载电影清单")
    @GetMapping("/loadMainPage")
    public R<List<Movie>> loadMainPage() {
        return R.ok(movieService.hotMovies());
    }

    @Operation(summary = "影院销售额榜 Top10（真实已支付票款）")
    @GetMapping("/topSales")
    public R<List<MovieSoldVO>> topSales() {
        return R.ok(movieService.topSales());
    }

    @Operation(summary = "公开票房榜 Top10（外部来源全国累计票房）")
    @GetMapping("/topBoxOffice")
    public R<List<MovieSoldVO>> topBoxOffice() {
        return R.ok(movieService.topBoxOffice());
    }

    @Operation(summary = "电影分页")
    @GetMapping("/list")
    public R<PageResult<Movie>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "3") Integer status,
            @RequestParam(required = false) Boolean showing,
            @RequestParam(required = false) Boolean incomplete) {
        return R.ok(movieService.page(page, limit, title, status, showing, incomplete));
    }

    @Operation(summary = "热映电影")
    @GetMapping("/list/hot")
    public R<List<Movie>> hot() {
        return R.ok(movieService.hotMovies());
    }

    @Operation(summary = "电影详情（含 actors / types）")
    @GetMapping("/info/{id}")
    public R<MovieInfoVO> info(@PathVariable Long id, HttpServletRequest req) {
        MovieInfoVO vo =
                cn.hutool.core.bean.BeanUtil.copyProperties(
                        movieService.info(id), MovieInfoVO.class);
        String token = req.getHeader("Authorization");
        if (token != null) {
            try {
                Map<String, Object> claims = jwtUtil.parseToMap(token);
                Long uid = Long.parseLong(claims.get("id").toString());
                vo.setIsWant(movieService.isWant(id, uid));
            } catch (Exception ignored) {
                // 无 token 时视为未登录
            }
        }
        return R.ok(vo);
    }

    // 资料写入统一在管理员接口，门户仅提供读取和想看操作。

    @Operation(summary = "设置/取消想看（DELETE 切换状态）")
    @DeleteMapping("/want/{id}")
    public R<Boolean> toggleWant(@PathVariable Long id) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(movieService.handleWant(uid, id));
    }

    @Operation(summary = "我的想看列表（电影 ID）")
    @GetMapping("/wants")
    public R<List<Long>> wants() {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(movieService.userWantList(uid));
    }

    /* ===== 2026-10-16 翻新: 卫星资源端点 ===== */

    @Operation(summary = "影片图片列表 (海报/剧照)")
    @GetMapping("/{id}/images")
    public R<List<MovieImageVO>> images(@PathVariable Long id) {
        return R.ok(movieService.images(id));
    }

    @Operation(summary = "影片视频列表 (预告片)")
    @GetMapping("/{id}/videos")
    public R<List<MovieVideoVO>> videos(@PathVariable Long id) {
        return R.ok(movieService.videos(id));
    }

    @Operation(summary = "影片演职 (导演/编剧/演员)")
    @GetMapping("/{id}/crew")
    public R<List<MovieCrewVO>> crew(
            @PathVariable Long id, @RequestParam(required = false) Integer roleType) {
        return R.ok(movieService.crew(id, roleType));
    }
}
