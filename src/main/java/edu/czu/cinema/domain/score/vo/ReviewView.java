package edu.czu.cinema.domain.score.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.time.LocalDateTime;

/** 公共列表不返回他人的订单号、用户ID或版本号。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewView {
    private Long id;
    private Long orderId;
    private Long movieId;
    private String movieTitle;
    private String poster;
    private String author;
    private Integer score;
    private String content;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
    private Long version;
    private boolean mine;
    private boolean published;
    private boolean editable;
    private String reason;
}
