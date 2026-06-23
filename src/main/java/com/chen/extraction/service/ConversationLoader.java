package com.chen.extraction.service;

import com.chen.extraction.model.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 对话数据加载器 —— 从 classpath 或文件系统加载 conversations.json
 */
@Service
public class ConversationLoader {

    private static final Logger log = LoggerFactory.getLogger(ConversationLoader.class);

    private final ObjectMapper objectMapper;

    public ConversationLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 加载所有对话（优先 classpath → data/ → 根目录）
     */
    public List<Conversation> loadAll() throws Exception {
        try {
            ClassPathResource resource = new ClassPathResource("conversations.json");
            if (resource.exists()) {
                log.info("从 classpath 加载 conversations.json");
                return Arrays.asList(objectMapper.readValue(resource.getInputStream(), Conversation[].class));
            }
        } catch (Exception e) {
            log.warn("classpath 加载失败: {}", e.getMessage());
        }

        Path dataPath = Path.of("data", "conversations.json");
        if (Files.exists(dataPath)) {
            log.info("从 data/ 加载 conversations.json");
            return Arrays.asList(objectMapper.readValue(dataPath.toFile(), Conversation[].class));
        }

        Path rootPath = Path.of("conversations.json");
        if (Files.exists(rootPath)) {
            log.info("从根目录加载 conversations.json");
            return Arrays.asList(objectMapper.readValue(rootPath.toFile(), Conversation[].class));
        }

        throw new RuntimeException("未找到 conversations.json，请将其放到 src/main/resources/ 或 data/ 目录");
    }
}
