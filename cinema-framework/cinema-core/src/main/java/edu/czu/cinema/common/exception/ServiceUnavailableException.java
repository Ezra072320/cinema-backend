package edu.czu.cinema.common.exception;

public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException() {
        super("账号服务暂不可用，请稍后重试");
    }
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
