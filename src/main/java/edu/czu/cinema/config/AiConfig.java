package edu.czu.cinema.config;

import edu.czu.cinema.AiTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 权威本地业务指南 + 实时工具，无须调用远程嵌入服务才能启动/问答。 */
@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(OpenAiChatModel model, AiTool aiTool) {
        return ChatClient.builder(model).defaultTools(aiTool).build();
    }
}
