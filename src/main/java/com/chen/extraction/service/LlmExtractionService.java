package com.chen.extraction.service;

import com.chen.extraction.model.Conversation;
import com.chen.extraction.model.ExtractionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 Spring AI + LLM 的结构化信息提取服务
 *
 * <p>使用 ChatClient 调用大模型，通过精心设计的 system prompt
 * 让模型输出符合 ExtractionResult schema 的 JSON。</p>
 */
@Service
@ConditionalOnProperty(name = "app.extraction.mode", havingValue = "llm")
public class LlmExtractionService implements ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(LlmExtractionService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public LlmExtractionService(ChatClient.Builder chatClientBuilder,
                                ObjectMapper objectMapper,
                                @Value("classpath:prompts/extraction-system-prompt.md") Resource promptResource) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        try {
            this.systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load system prompt", e);
        }
    }

    @Override
    public ExtractionResult extract(Conversation conversation) {
        String conversationText = conversation.toFormattedText();

        try {
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user("请从以下客服对话中提取结构化信息，严格按照 JSON schema 输出：\n\n" + conversationText)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("LLM returned empty response for conversation {}", conversation.id());
                return ExtractionResult.createErrorResult(conversation.id(), "LLM 返回空结果");
            }

            // 清洗响应：去除可能的 markdown 代码块标记
            String json = cleanJsonResponse(response);
            log.debug("Extracted JSON for {}: {}", conversation.id(), json);

            return objectMapper.readValue(json, ExtractionResult.class);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM response for {}: {}", conversation.id(), e.getMessage());
            return ExtractionResult.createErrorResult(conversation.id(), "JSON 解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("LLM extraction failed for {}: {}", conversation.id(), e.getMessage(), e);
            return ExtractionResult.createErrorResult(conversation.id(), "LLM 调用失败: " + e.getMessage());
        }
    }

    /**
     * 清洗 LLM 返回的 JSON：去除 markdown 代码块和前后空白
     */
    private String cleanJsonResponse(String raw) {
        String cleaned = raw.trim();
        // 去除 ```json ... ``` 包裹
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
