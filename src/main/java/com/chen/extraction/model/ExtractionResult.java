package com.chen.extraction.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 从客服对话中提取的结构化信息 —— 面向客服主管周报场景设计
 *
 * <h3>Schema 设计理由</h3>
 * <ul>
 *   <li><b>对话标识</b>（id/channel/agent/turnCount）—— 基础溯源，便于定位具体对话</li>
 *   <li><b>summary + primaryIntent + intentCategory</b> —— 快速了解"用户问了什么"，支持按类别聚合统计</li>
 *   <li><b>resolutionSummary + isResolved + resolutionType</b> —— 回答"有没有解决"，衡量解决率</li>
 *   <li><b>initialSentiment + finalSentiment + sentimentTrend</b> —— 量化"用户情绪"，识别情绪恶化案例</li>
 *   <li><b>mentionedOrders / mentionedProducts</b> —— 实体抽取，便于关联订单系统</li>
 *   <li><b>边界标记</b>（multi-intent/escalation/topic-switch/incomplete）—— 标记复杂对话，主管可重点关注</li>
 *   <li><b>agentSolutionQuality + waitingTimeIssue</b> —— 客服绩效评估维度</li>
 *   <li><b>needsFollowUp + keyTags</b> —— 可操作的后处理标签</li>
 * </ul>
 */
@JsonPropertyOrder({
        "conversationId", "channel", "agentName", "turnCount",
        "summary", "userPrimaryIntent", "intentCategory", "subIntents",
        "resolutionSummary", "isResolved", "resolutionType",
        "userInitialSentiment", "userFinalSentiment", "sentimentTrend",
        "mentionedOrders", "mentionedProducts",
        "hasMultipleIntents", "hasEscalation", "hasTopicSwitch", "isIncomplete",
        "agentSolutionQuality", "waitingTimeIssue",
        "needsFollowUp", "keyTags"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionResult(

        // ========== 基础标识 ==========
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("channel") String channel,
        @JsonProperty("agent_name") String agentName,
        @JsonProperty("turn_count") int turnCount,

        // ========== 核心提取 ==========
        @JsonProperty("summary") String summary,
        @JsonProperty("user_primary_intent") String userPrimaryIntent,
        @JsonProperty("intent_category") String intentCategory,
        @JsonProperty("sub_intents") List<String> subIntents,

        // ========== 解决情况 ==========
        @JsonProperty("resolution_summary") String resolutionSummary,
        @JsonProperty("is_resolved") Boolean isResolved,
        @JsonProperty("resolution_type") String resolutionType,

        // ========== 情绪分析 ==========
        @JsonProperty("user_initial_sentiment") String userInitialSentiment,
        @JsonProperty("user_final_sentiment") String userFinalSentiment,
        @JsonProperty("sentiment_trend") String sentimentTrend,

        // ========== 实体抽取 ==========
        @JsonProperty("mentioned_orders") List<String> mentionedOrders,
        @JsonProperty("mentioned_products") List<String> mentionedProducts,

        // ========== 边界情况标记 ==========
        @JsonProperty("has_multiple_intents") boolean hasMultipleIntents,
        @JsonProperty("has_escalation") boolean hasEscalation,
        @JsonProperty("has_topic_switch") boolean hasTopicSwitch,
        @JsonProperty("is_incomplete") boolean isIncomplete,

        // ========== 客服评估 ==========
        @JsonProperty("agent_solution_quality") String agentSolutionQuality,
        @JsonProperty("waiting_time_issue") boolean waitingTimeIssue,

        // ========== 主管标签 ==========
        @JsonProperty("needs_follow_up") boolean needsFollowUp,
        @JsonProperty("key_tags") List<String> keyTags

) {

    /**
     * 创建一个 "未完成提取" 的占位结果（用于异常情况）
     */
    public static ExtractionResult createErrorResult(String conversationId, String error) {
        return new ExtractionResult(
                conversationId, null, null, 0,
                "提取失败: " + error, null, "提取异常",
                null, null, false, null,
                null, null, null,
                null, null,
                false, false, false, false,
                null, false, false,
                List.of("extraction_error")
        );
    }

    // ====== 字段取值范围常量（便于 LLM prompt 引用） ======

    public static final class IntentCategories {
        public static final String REFUND_RETURN = "退货退款";
        public static final String LOGISTICS = "物流配送";
        public static final String PRODUCT_INQUIRY = "商品咨询";
        public static final String COMPLAINT_SUGGESTION = "投诉与建议";
        public static final String ACCOUNT_SECURITY = "账号安全";
        public static final String EXCHANGE = "换货换尺码";
        public static final String COUPON_PROMOTION = "优惠券与活动";
        public static final String URGE_PROGRESS = "催促进度";
        public static final String FEATURE_REQUEST = "功能建议";
        public static final String INCOMPLETE = "未完成对话";
        public static final String OTHER = "其他";
    }

    public static final class ResolutionTypes {
        public static final String REFUND = "退款";
        public static final String EXCHANGE = "换货";
        public static final String COMPENSATION_COUPON = "补偿优惠券";
        public static final String INFO_ANSWER = "信息解答";
        public static final String ESCALATED = "转派处理";
        public static final String UNRESOLVED = "未解决";
        public static final String USER_ABANDONED = "用户主动放弃";
        public static final String FEEDBACK_ONLY = "仅建议收集";
        public static final String REPLACEMENT_RESEND = "补发/重发";
    }

    public static final class Sentiments {
        public static final String POSITIVE = "positive";
        public static final String NEUTRAL = "neutral";
        public static final String NEGATIVE = "negative";
        public static final String ANGRY = "angry";
    }

    public static final class SentimentTrends {
        public static final String IMPROVED = "improved";
        public static final String STABLE = "stable";
        public static final String WORSENED = "worsened";
    }

    public static final class AgentQualities {
        public static final String EXCELLENT = "优秀";
        public static final String GOOD = "良好";
        public static final String AVERAGE = "一般";
        public static final String POOR = "较差";
    }
}
