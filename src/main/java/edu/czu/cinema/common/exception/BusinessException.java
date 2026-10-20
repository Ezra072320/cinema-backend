package edu.czu.cinema.common.exception;

import edu.czu.cinema.common.api.ResultCode;
import lombok.Getter;

/**
 * 业务异常.
 * <p>
 * 抛出会被 {@link GlobalExceptionHandler} 捕获并转换为 {@link edu.czu.cinema.common.api.R} 结构化错误响应.
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    public BusinessException(ResultCode code, String message) {
        super(message);
        this.code = code.getCode();
    }

    public BusinessException(ResultCode code) {
        super(code.getMsg());
        this.code = code.getCode();
    }
}
