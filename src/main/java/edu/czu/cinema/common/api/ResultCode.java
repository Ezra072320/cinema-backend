package edu.czu.cinema.common.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务码枚举.
 * <pre>
 * 1xxx 成功
 * 2xxx 客户端错误
 * 3xxx 服务端错误
 * 4xxx 鉴权/会话
 * </pre>
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(1, "操作成功"),
    FAIL(0, "操作失败"),

    BAD_REQUEST(2000, "请求参数不合法"),
    UNAUTHORIZED(2001, "未登录或登录已过期"),
    FORBIDDEN(2002, "无权限访问"),
    NOT_FOUND(2003, "资源不存在"),
    CONFLICT(2004, "数据冲突"),

    USER_NOT_FOUND(2100, "用户不存在"),
    USER_PASSWORD_ERROR(2101, "用户名或密码错误"),
    USER_DISABLED(2102, "账号已被冻结"),
    USER_ALREADY_EXISTS(2103, "用户已存在"),

    ADMIN_NOT_FOUND(2200, "管理员不存在"),
    ADMIN_PASSWORD_ERROR(2201, "管理员名或密码错误"),

    ORDER_LOCK_FAILED(2300, "手速太慢了，座位被别人抢走了"),
    ORDER_REFUND_DENIED(2301, "距离开场不足三小时，不可退款"),

    SERVER_ERROR(3000, "服务异常"),
    REMOTE_ERROR(3001, "远端服务异常");

    private final int code;
    private final String msg;
}
