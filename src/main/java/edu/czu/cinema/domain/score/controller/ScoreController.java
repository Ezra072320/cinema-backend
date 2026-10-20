package edu.czu.cinema.domain.score.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.score.entity.Score;
import edu.czu.cinema.domain.score.service.ScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "09. 评分域")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreService scoreService;

    @ExceptionHandler(edu.czu.cinema.common.exception.BusinessException.class)
    public org.springframework.http.ResponseEntity<R<Void>> businessError(edu.czu.cinema.common.exception.BusinessException e) {
        var status = switch (e.getCode()) {
            case 2001 -> org.springframework.http.HttpStatus.UNAUTHORIZED;
            case 2002 -> org.springframework.http.HttpStatus.FORBIDDEN;
            case 2003 -> org.springframework.http.HttpStatus.NOT_FOUND;
            case 2004 -> org.springframework.http.HttpStatus.CONFLICT;
            default -> org.springframework.http.HttpStatus.BAD_REQUEST;
        };
        return org.springframework.http.ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    @Operation(summary = "发表或修改本人评价，修改须携带expectedVersion")
    @PostMapping("/score")
    public R<Boolean> submit(@Valid @RequestBody ScoreSubmitDTO dto) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(scoreService.submit(uid, dto));
    }

    @Operation(summary = "订单评分详情")
    @GetMapping("/score/info/{orderId}")
    public R<Score> info(@PathVariable Long orderId) {
        return R.ok(scoreService.ownedReview(SecurityUtils.requireUserId(), orderId));
    }

    @Operation(summary = "本人评价编辑上下文及可评价时间")
    @GetMapping("/score/context/{orderId}")
    public R<edu.czu.cinema.domain.score.vo.ReviewContext> context(@PathVariable Long orderId) {
        return R.ok(scoreService.context(SecurityUtils.requireUserId(), orderId));
    }

    @Operation(summary = "我的评价分页")
    @GetMapping("/scores")
    public R<edu.czu.cinema.common.api.PageResult<edu.czu.cinema.domain.score.vo.ReviewView>> mine(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int limit) {
        return R.ok(scoreService.mine(SecurityUtils.requireUserId(), page, limit));
    }

    @Operation(summary = "删除本人评价，不修改订单状态")
    @DeleteMapping("/score/{orderId}")
    public R<Void> remove(@PathVariable Long orderId, @RequestParam Long version) {
        scoreService.remove(SecurityUtils.requireUserId(), orderId, version);
        return R.ok();
    }
}
