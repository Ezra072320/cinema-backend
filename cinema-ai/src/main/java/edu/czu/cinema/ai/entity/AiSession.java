package edu.czu.cinema.ai.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 客服会话(表 {@code ai_session}).
 *
 * <p>2026-10-19 落库: 此前会话只存 JVM 内存, 重启即失, 前端侧栏要的
 * title/createTime/lastMessage 后端根本不返回(契约错位, 侧栏永远显示"新对话").
 * 落库后 title=首问截断, createTime/updateTime/lastMessage 都是真值.
 */
@Data
@TableName("ai_session")
public class AiSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话ID(前端生成的 UUID). */
    @TableId
    private String chatId;

    @TableField("user_id")
    private Long userId;

    /** 标题 = 首条提问截断. */
    private String title;

    /** 最近一条消息摘要(回复优先, 否则提问). */
    @TableField("last_message")
    private String lastMessage;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 历史消息的响应结构({role, content, ts}).
     *
     * <p>前端 AiMessage 契约就这三个字段; ts 是真实毫秒时间戳
     * (内存实现曾按"现在往前每条减 1 秒"伪造).
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Message implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String role;
        private String content;
        private Long ts;
    }
}
