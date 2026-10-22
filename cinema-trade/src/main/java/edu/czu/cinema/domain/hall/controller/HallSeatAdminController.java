package edu.czu.cinema.domain.hall.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.hall.dto.SeatApplyResultVO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSaveDTO;
import edu.czu.cinema.domain.hall.dto.SeatDraftVO;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;
import edu.czu.cinema.domain.hall.service.SeatVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 影厅座位图草稿/发布接口 (Phase 3).
 *
 * <p>核心规则:
 * <ul>
 *   <li>每个厅最多 1 个草稿 (is_current=0 AND is_locked=0)</li>
 *   <li>改/存草稿用乐观锁, 客户端必须回传 expectedLockVersion, 不匹配 409</li>
 *   <li>发布草稿: 必须先无未放映场次, 否则 200 + ok=false + blockingScheduleIds 列表</li>
 *   <li>发布后老 current version 变 is_locked=1 (永冻结), seat 行不删</li>
 * </ul>
 */
@Tag(name = "06. 影厅座位图版本")
@RestController
@RequestMapping("/api/admin/hall/{hallId}/seats")
@RequiredArgsConstructor
public class HallSeatAdminController {

    private final SeatVersionService seatVersionService;

    @Operation(summary = "拉/建草稿 (没有则从 current 复制)")
    @GetMapping("/draft")
    public R<SeatDraftVO> getOrCreateDraft(@PathVariable Long hallId) {
        SecurityUtils.requireAdminId();
        return R.ok(seatVersionService.getOrCreateDraft(hallId));
    }

    @Operation(summary = "保存草稿 (带乐观锁)")
    @PutMapping("/draft")
    public R<Void> saveDraft(@PathVariable Long hallId,
                              @Valid @RequestBody SeatDraftSaveDTO dto) {
        SecurityUtils.requireAdminId();
        seatVersionService.saveDraft(hallId, dto);
        return R.ok();
    }

    @Operation(summary = "删草稿 (回退到 current)")
    @DeleteMapping("/draft")
    public R<Void> dropDraft(@PathVariable Long hallId) {
        SecurityUtils.requireAdminId();
        seatVersionService.dropDraft(hallId);
        return R.ok();
    }

    @Operation(summary = "发布草稿 (有未来场次会拒绝并返回冲突列表)")
    @PostMapping("/draft/apply")
    public R<SeatApplyResultVO> applyDraft(@PathVariable Long hallId) {
        Long adminId = SecurityUtils.requireAdminId();
        return R.ok(seatVersionService.applyDraft(hallId, adminId));
    }

    @Operation(summary = "历史版本列表 (新→旧)")
    @GetMapping("/versions")
    public R<List<HallSeatVersion>> versions(@PathVariable Long hallId) {
        SecurityUtils.requireAdminId();
        return R.ok(seatVersionService.listVersions(hallId));
    }
}
