package edu.czu.cinema.ai;

import edu.czu.cinema.ai.service.AiIdentity;
import edu.czu.cinema.ai.service.AiToolTrace;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolTraceTest {
    public static class Lookup {
        @Tool(description = "Look up a movie using trusted identity")
        public String lookup(Long movieId, ToolContext context) {
            return "movie=" + movieId + ";owner=" + AiIdentity.from(context).userId();
        }
    }

    @Test
    void actualMethodCallbackKeepsSchemaIdentityAndResultWhileRecordingOnlyItsOwnTurn() {
        var delegate = ToolCallbacks.from(new Lookup())[0];
        var callback = AiToolTrace.observe(delegate);
        var trace = new AiToolTrace();
        var otherTurn = new AiToolTrace();
        var context = new ToolContext(Map.of(AiIdentity.CONTEXT_KEY, new AiIdentity(7L, "chat"),
                AiToolTrace.CONTEXT_KEY, trace));

        assertThat(callback.getToolDefinition()).isSameAs(delegate.getToolDefinition());
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("userId", "context");
        assertThat(callback.call("{\"movieId\":916}", context)).contains("movie=916;owner=7");
        assertThat(trace.snapshot()).singleElement().satisfies(entry -> {
            assertThat(entry.tool()).isEqualTo("lookup");
            assertThat(entry.arguments()).isEqualTo("{\"movieId\":916}");
            assertThat(entry.result()).contains("movie=916");
        });
        assertThat(otherTurn.snapshot()).isEmpty();
    }

    @Test
    void historyBudgetKeepsTheNewestReferencesAndMarksTruncatedResults() {
        var trace = new AiToolTrace();
        for (int i = 0; i < 100; i++) trace.record("lookup", "{\"movieId\":" + i + "}", "x".repeat(5000));
        assertThat(trace.snapshot()).hasSize(2);
        assertThat(trace.snapshot().getLast().arguments()).contains("99");
        assertThat(trace.snapshot().getLast().truncated()).isTrue();
        assertThat(trace.snapshot().getLast().result()).hasSize(4000);
    }
}
