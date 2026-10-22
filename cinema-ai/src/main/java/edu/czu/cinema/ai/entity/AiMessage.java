package edu.czu.cinema.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 客服消息(表 {@code ai_message}).
 *
 * <p>只存 user / assistant 两种角色 —— system 提示词与工具中间结果不落库,
 * 历史回放时也不需要它们(系统提示词每次现拼, 工具结果已融进回复).
 */
@Data
@TableName("ai_message")
public class AiMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chat_id")
    private String chatId;

    @TableField("user_id")
    private Long userId;

    /** user / assistant. */
    private String role;

    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("create_time")
    private LocalDateTime createTime;
}
