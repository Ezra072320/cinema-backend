package edu.czu.cinema.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_action")
public class AiAction {
    @TableId
    private String id;
    private Long userId;
    private String chatId;
    private String type;
    private String title;
    private String summary;
    private String payload;
    private String status;
    private String result;
    private String path;
    private LocalDateTime createTime;
    private LocalDateTime expiresAt;
}
