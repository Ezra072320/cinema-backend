package edu.czu.cinema.domain.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单资金操作审计流水（V23 迁移）。
 * pay/refund/close/cancel 每笔状态变更留痕：谁、何时、何因、金额。
 */
@Data
@TableName("order_action")
public class OrderAction implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_PAY = "PAY";
    public static final String ACTION_REFUND = "REFUND";
    public static final String ACTION_CLOSE = "CLOSE";
    public static final String ACTION_CANCEL_REFUND = "CANCEL_REFUND";

    public static final String OP_USER = "USER";
    public static final String OP_ADMIN = "ADMIN";
    public static final String OP_SYSTEM = "SYSTEM";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderNumber;
    private String action;
    private Integer fromStatus;
    private Integer toStatus;
    private BigDecimal amount;
    private String operatorType;
    private Long operatorId;
    private String reason;
    private LocalDateTime createdAt;
}
