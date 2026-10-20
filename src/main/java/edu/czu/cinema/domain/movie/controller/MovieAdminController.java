package edu.czu.cinema.domain.movie.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.movie.sync.MovieSyncService;
import edu.czu.cinema.domain.movie.sync.MovieSyncStatus;
import edu.czu.cinema.domain.movie.service.MovieMetadataService;
import edu.czu.cinema.domain.movie.entity.Movie;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 影片管理 - 管理员专用端点.
 *
 * <p>更新近期片单、全片库补缺以及管理员核实补录；人工确认字段受到同步保护。
 */
@Tag(name = "03. 电影域 - 管理员")
@RestController
@RequestMapping("/api/admin/movie")
@RequiredArgsConstructor
public class MovieAdminController {

    private final MovieSyncService syncService;
    private final MovieMetadataService metadataService;

    @Operation(summary = "补全全片库缺失资料（异步）")
    @PostMapping("/repair")
    public R<Map<String,Object>> repair(@RequestParam(value="batchSize",required=false) Integer batchSize) {
        SecurityUtils.requireAdminId();
        if (!syncService.startRepair(batchSize)) return R.fail("已有更新或补缺任务正在进行中");
        return R.ok(Map.of("started",true));
    }

    @Operation(summary = "管理员核实补录并保护字段")
    @PutMapping("/{id}/metadata")
    public R<Movie> metadata(@PathVariable Long id, @RequestBody MovieMetadataService.Edit request) {
        SecurityUtils.requireAdminId();
        return R.ok(metadataService.edit(id,request));
    }

    /**
     * 新增大陆待映影片：猫眼待映片单 / TMDB 辅助 → 图片转存 OSS → 仅入库库中没有的影片，已有影片不修改。
     *
     * <p>立刻返回，不等待同步跑完。前端拿
     * {@code /sync/status} 轮询进度。
     */
    @Operation(summary = "同步影片数据(异步, 立刻返回)")
    @PostMapping("/sync")
    public R<Map<String, Object>> sync(
            @RequestParam(value = "batchSize", required = false) Integer batchSize) {
        // 必须在请求线程上校验：异步线程里 ThreadLocal 的 principal 是空的
        SecurityUtils.requireAdminId();
        if (!syncService.start(batchSize)) {
            return R.fail("已有同步任务正在进行中");
        }
        return R.ok(Map.of("started", true));
    }

    /** 查询同步进度，供前端轮询。 */
    @Operation(summary = "查询同步进度")
    @GetMapping("/sync/status")
    public R<MovieSyncStatus> syncStatus() {
        SecurityUtils.requireAdminId();
        return R.ok(syncService.status());
    }

    @GetMapping("/sync/overview")
    public R<Map<String, Object>> overview() {
        SecurityUtils.requireAdminId();
        return R.ok(syncService.overview());
    }
}
