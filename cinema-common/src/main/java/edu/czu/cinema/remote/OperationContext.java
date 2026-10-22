package edu.czu.cinema.remote;

public final class OperationContext {
    private static final ThreadLocal<String> KEY = new ThreadLocal<>();

    private OperationContext() {}

    public static String get() {
        return KEY.get();
    }

    public static void set(String key) {
        KEY.set(key);
    }

    public static void clear() {
        KEY.remove();
    }
}
