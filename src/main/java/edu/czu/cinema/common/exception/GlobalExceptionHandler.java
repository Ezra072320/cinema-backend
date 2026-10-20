package edu.czu.cinema.common.exception;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理.
 * <p>
 * 业务异常保持 HTTP 200 + 业务码的既有契约（README「接口约定」）；
 * 未知异常返回 HTTP 500 且只回固定文案——绝不把异常消息（可能含 SQL、表结构）外发。
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：透传异常上携带的业务码. */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("业务异常 [{}] {} - {}", req.getRequestURI(), ex.getCode(), ex.getMessage());
        return R.fail(ex.getCode(), ex.getMessage());
    }

    /** @Valid 校验失败. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return R.fail(msg.isEmpty() ? "请求参数不合法" : msg);
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return R.fail(msg.isEmpty() ? "请求参数不合法" : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return R.fail("缺少参数：" + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return R.fail("参数类型错误：" + ex.getName());
    }

    /** Spring Security：凭证错误. */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<R<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail("用户名或密码错误"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail("未登录或登录已过期"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.fail("无权限访问"));
    }

    /** 兜底：固定文案 + traceId，异常细节只进日志. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleAny(Exception ex, HttpServletRequest req) {
        String traceId = MDC.get("traceId") == null ? "-" : MDC.get("traceId");
        log.error("服务异常 traceId={} [{}]", traceId, req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.SERVER_ERROR, "服务异常，请稍后重试（traceId=" + traceId + "）"));
    }
}
