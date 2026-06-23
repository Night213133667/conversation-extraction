package com.chen.extraction;

import com.chen.extraction.model.ExtractionResult;
import com.chen.extraction.service.ConversationLoader;
import com.chen.extraction.service.ExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 启动时自动加载 conversations.json，批量提取并输出结果
 *
 * <p>结果写入 {@code output/extraction_results.json}</p>
 */
@Component
public class ExtractionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRunner.class);

    private final ExtractionService extractionService;
    private final ConversationLoader conversationLoader;
    private final ObjectMapper objectMapper;

    public ExtractionRunner(ExtractionService extractionService, ConversationLoader conversationLoader,
                            ObjectMapper objectMapper) {
        this.extractionService = extractionService;
        this.conversationLoader = conversationLoader;
        this.objectMapper = objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("========== 开始批量提取 ==========");

        // 加载 conversations.json
        var conversations = conversationLoader.loadAll();

        log.info("共加载 {} 条对话", conversations.size());

        Instant start = Instant.now();

        // 逐条提取
        List<ExtractionResult> results = conversations.stream()
                .map(conv -> {
                    log.info("提取中: {} ...", conv.id());
                    return extractionService.extract(conv);
                })
                .toList();

        Instant end = Instant.now();
        long elapsedMs = Duration.between(start, end).toMillis();

        // 统计
        long resolvedCount = results.stream().filter(r -> Boolean.TRUE.equals(r.isResolved())).count();
        long unresolvedCount = results.size() - resolvedCount;

        // 写入输出文件
        Path outputDir = Path.of("output");
        Files.createDirectories(outputDir);
        File outputFile = outputDir.resolve("extraction_results.json").toFile();
        objectMapper.writeValue(outputFile, results);

        // 打印摘要
        log.info("========== 提取完成 ==========");
        log.info("总计: {} 条对话", results.size());
        log.info("已解决: {} 条 ({}%)", resolvedCount, resolvedCount * 100 / Math.max(results.size(), 1));
        log.info("未解决: {} 条 ({}%)", unresolvedCount, unresolvedCount * 100 / Math.max(results.size(), 1));
        log.info("耗时: {} ms", elapsedMs);
        log.info("结果已写入: {}", outputFile.getAbsolutePath());

        // 打印情绪分布
        long angryCount = results.stream().filter(r -> "angry".equals(r.userInitialSentiment())).count();
        long negativeCount = results.stream().filter(r -> "negative".equals(r.userInitialSentiment())).count();
        long improvedCount = results.stream().filter(r -> "improved".equals(r.sentimentTrend())).count();
        long worsenedCount = results.stream().filter(r -> "worsened".equals(r.sentimentTrend())).count();
        log.info("初始情绪: angry={}, negative={}", angryCount, negativeCount);
        log.info("情绪趋势: improved={}, worsened={}, stable={}",
                improvedCount, worsenedCount, results.size() - improvedCount - worsenedCount);

        log.info("结果已保存至 output/extraction_results.json，可直接查看");
    }
}
