package edu.czu.cinema.ai.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 小型业务指南完整载入，不因向量服务故障或检索未命中而丢失业务规则。 */
@Component
public class AiServiceGuide {
    private final String content;

    public AiServiceGuide() throws IOException {
        content = new ClassPathResource("rag/theater-service.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    public String content() { return content; }
}
