package edu.czu.cinema.ai.service;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;

/** Request-scoped business references; never shared through a thread-local or between users. */
public final class AiToolTrace {
    public static final String CONTEXT_KEY = AiToolTrace.class.getName();
    private static final int MAX_CHARS = 9_000;
    private final List<Entry> entries = new ArrayList<>();

    public record Entry(String tool, String arguments, String result, boolean truncated) {}

    public synchronized void record(String tool, String arguments, String result) {
        String args = bounded(arguments, 1_000);
        String output = bounded(result, 4_000);
        entries.add(new Entry(tool, args, output, result != null && result.length() > output.length()));
        while (entries.size() > 1 && size() > MAX_CHARS) entries.remove(0);
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(entries);
    }

    private int size() {
        return entries.stream().mapToInt(e -> e.tool().length() + e.arguments().length() + e.result().length()).sum();
    }

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public static ToolCallback observe(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String input) {
                return delegate.call(input);
            }

            @Override
            public String call(String input, ToolContext context) {
                String result = delegate.call(input, context);
                if (context != null && context.getContext().get(CONTEXT_KEY) instanceof AiToolTrace trace) {
                    trace.record(getToolDefinition().name(), input, result);
                }
                return result;
            }
        };
    }
}
