package com.chen.extraction.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单条对话轮次（用户或客服的一次发言）
 */
public record Turn(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content
) {
    public boolean isUser() {
        return "user".equalsIgnoreCase(role);
    }

    public boolean isAgent() {
        return "agent".equalsIgnoreCase(role);
    }
}
