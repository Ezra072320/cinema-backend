package edu.czu.cinema.common.exception;

public class CapacityException extends RuntimeException {
    public CapacityException() {
        super("购票人数较多，请稍后重试");
    }
}
