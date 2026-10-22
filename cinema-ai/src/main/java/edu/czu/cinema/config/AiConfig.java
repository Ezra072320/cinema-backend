package edu.czu.cinema.config;

import edu.czu.cinema.AiTool;
import edu.czu.cinema.ai.service.AiToolTrace;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.support.ToolCallbacks;
import java.util.Arrays;

/** 权威本地业务指南 + 实时工具，无须调用远程嵌入服务才能启动/问答。 */
@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(OpenAiChatModel model, AiTool aiTool) {
        return ChatClient.builder(model)
                .defaultToolCallbacks(Arrays.stream(ToolCallbacks.from(aiTool))
                        .map(AiToolTrace::observe).toList())
                .build();
    }
}
