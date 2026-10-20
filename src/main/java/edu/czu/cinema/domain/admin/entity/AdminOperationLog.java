package edu.czu.cinema.domain.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 高风险管理操作日志（V23 迁移）：取消场次等不可逆操作留痕。
 */
@Data
@TableName("admin_operation_log")
public class AdminOperationLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ACTION_SCHEDULE_CANCEL = "SCHEDULE_CANCEL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminId;
    private String adminName;
    private String action;
    private String targetType;
    private Long targetId;
    private String detail;
    private LocalDateTime createdAt;
}
