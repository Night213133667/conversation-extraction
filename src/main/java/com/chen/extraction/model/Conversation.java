package com.chen.extraction.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 原始客服对话数据
 */
public record Conversation(
        @JsonProperty("id") String id,
        @JsonProperty("channel") String channel,
        @JsonProperty("agent") String agent,
        @JsonProperty("turns") List<Turn> turns
) {
    /**
     * 计算对话轮次数（一问一答为一轮）
     */
    public int getTurnCount() {
        return turns != null ? turns.size() : 0;
    }

    /**
     * 获取所有用户发言
     */
    public List<Turn> getUserTurns() {
        return turns.stream().filter(Turn::isUser).toList();
    }

    /**
     * 获取所有客服发言
     */
    public List<Turn> getAgentTurns() {
        return turns.stream().filter(Turn::isAgent).toList();
    }

    /**
     * 将对话格式化为 LLM 可读的文本
     */
    public String toFormattedText() {
        StringBuilder sb = new StringBuilder();
        sb.append("对话ID: ").append(id).append("\n");
        sb.append("渠道: ").append(channel).append("\n");
        sb.append("客服: ").append(agent).append("\n\n");
        for (Turn turn : turns) {
            String roleLabel = "user".equals(turn.role()) ? "用户" : "客服";
            sb.append(roleLabel).append(": ").append(turn.content()).append("\n");
        }
        return sb.toString();
    }
}
