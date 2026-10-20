package edu.czu.cinema.domain.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 影厅座位图版本快照.
 *
 * <p>每次管理员在 Phase 3 的「草稿/发布」流程里点发布, 就产生一条新版本:
 * 老版本 {@code is_current=0, is_locked=1}, 新版本 {@code is_current=1, is_locked=0}.
 * 冻结的版本永不能改、永不能删 —— 它们的 seat 行是「历史场次」还可能引用到的对象.
 *
 * <p>{@code (hall_id, version)} 唯一; 每个 hall 在任意时刻至多 1 条 {@code is_current=1}.
 */
@Data
@TableName("hall_seat_version")
@Schema(description = "影厅座位图版本")
public class HallSeatVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("hall_id")
    private Long hallId;

    /** 版本号, 从 1 起, 同 hall 自增. */
    private Integer version;

    /** 基于哪个模板. NULL = 完全自定义(暂未支持, 留作 Phase 7+). */
    @TableField("template_id")
    private Long templateId;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField("applied_by")
    private Long appliedBy;

    /** 备注, 例「维修调整」「情人节加情侣位」. */
    private String note;

    /** 1=当前生效, 同 hall 仅 1 条=1. */
    @TableField("is_current")
    private Integer isCurrent;

    /** 1=冻结, 永不能改不能删. */
    @TableField("is_locked")
    private Integer isLocked;

    /**
     * 草稿乐观锁版本. 0 = 已发布版本(无意义); 草稿每次保存 +1.
     * 客户端编辑时拿到当前值, 保存时回传; 不匹配则提示「已被改, 请刷新」.
     */
    @TableField("draft_lock_version")
    private Integer draftLockVersion;
}
