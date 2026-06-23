package com.chen.extraction.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置 —— 仅在 LLM 模式下激活
 * <p>使用通用的 ChatModel 接口，兼容 DeepSeek / OpenAI / 通义千问 等所有适配器。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.extraction.mode", havingValue = "llm", matchIfMissing = false)
public class AiConfig {

    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
