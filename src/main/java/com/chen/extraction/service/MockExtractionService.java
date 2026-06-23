package com.chen.extraction.service;

import com.chen.extraction.model.Conversation;
import com.chen.extraction.model.ExtractionResult;
import com.chen.extraction.model.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 模式提取服务 —— 基于规则的关键词匹配，无需调用真实 LLM API
 *
 * <p>当配置 {@code app.extraction.mode=mock} 或未配置 API key 时启用。
 * 通过对每条对话的文本进行关键词扫描来判断意图、情绪、解决状态等。</p>
 *
 * <h3>边界情况处理策略</h3>
 * <ul>
 *   <li><b>多诉求</b>：扫描所有用户 turn，检测多个关键词类别 → hasMultipleIntents=true</li>
 *   <li><b>转人工</b>：检测"转人工"/"转接"等关键词 → hasEscalation=true</li>
 *   <li><b>话题切换</b>：检测"对了"/"另外"/"还有"及意图类别变化 → hasTopicSwitch=true</li>
 *   <li><b>未完成对话</b>：检测对话过早结束 / 无明确结论 → isIncomplete=true</li>
 *   <li><b>信息缺失</b>：如实标记为 false/null，不编造数据</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.extraction.mode", havingValue = "mock", matchIfMissing = true)
public class MockExtractionService implements ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MockExtractionService.class);

    // 订单号正则
    private static final Pattern ORDER_PATTERN = Pattern.compile("DD\\d{8}-\\d{4}");

    // 关键词 → 意图类别映射
    private static final Map<String, String> INTENT_KEYWORDS = new LinkedHashMap<>() {{
        put("退款|退钱|退.*款", ExtractionResult.IntentCategories.REFUND_RETURN);
        put("退货|退掉|退.*货|寄回|退回", ExtractionResult.IntentCategories.REFUND_RETURN);
        put("换货|换新|换.*尺码|换.*码|换.*号|换一个", ExtractionResult.IntentCategories.EXCHANGE);
        put("快递|物流|配送|派送|没收到|签收|快递柜|转运|改地址|改派", ExtractionResult.IntentCategories.LOGISTICS);
        put("成分|材质|规格|参数|带上飞机|能不能.*带|多大|多少钱|什么颜色|有没有.*色|有货|补货|推荐|哪个好", ExtractionResult.IntentCategories.PRODUCT_INQUIRY);
        put("投诉|举报|差评|太.*差|什么.*服务|破服务|烂", ExtractionResult.IntentCategories.COMPLAINT_SUGGESTION);
        put("建议|能不能.*加.*功能|希望.*功能|提个建议", ExtractionResult.IntentCategories.FEATURE_REQUEST);
        put("异地登录|账号.*安全|密码|盗号|验证|登录记录", ExtractionResult.IntentCategories.ACCOUNT_SECURITY);
        put("优惠券|用不了.*券|券.*用|满减|折扣|活动", ExtractionResult.IntentCategories.COUPON_PROMOTION);
        put("还没到|怎么还没|等.*天|处理.*没|进度|催|快点|搞快|半天", ExtractionResult.IntentCategories.URGE_PROGRESS);
        put("取消.*订单|取消.*还没发|不想要了|买错了|取消", ExtractionResult.IntentCategories.REFUND_RETURN);
        put("破损|碎了|坏了|不工作|问题|品控|质量|坏的|是碎的", ExtractionResult.IntentCategories.COMPLAINT_SUGGESTION);
    }};

    // 情绪关键词
    private static final Map<String, String> SENTIMENT_KEYWORDS = new LinkedHashMap<>() {{
        put("投诉|气|太过分|垃圾|智障|破|烂|差|太.*差|什么.*服务|破服务|等了.*没人|浪费|烦|无语|坑|骗子|假货|搞快点|搞半天|半天了|不回|不.*理", "angry");
        put("失望|没信心|不行|不能|没法|取不出来|怎么办|害怕|着急|担心|终于", "negative");
        put("谢谢|好的|好吧|行吧|可以|辛苦了|麻烦|不错|挺好", "positive");
    }};

    @Override
    public ExtractionResult extract(Conversation conversation) {
        String fullText = conversation.toFormattedText();
        String userText = String.join(" ", conversation.getUserTurns().stream()
                .map(Turn::content).toList());
        String agentText = String.join(" ", conversation.getAgentTurns().stream()
                .map(Turn::content).toList());

        // === 意图识别 ===
        List<String> detectedIntents = detectIntents(userText);
        String primaryIntent = detectedIntents.isEmpty() ? "其他" : detectedIntents.get(0);
        List<String> subIntents = detectedIntents.size() > 1
                ? detectedIntents.subList(1, detectedIntents.size())
                : List.of();

        // === 解决状态 ===
        boolean isResolved = detectResolution(agentText, userText);
        String resolutionType = detectResolutionType(agentText, isResolved);
        String resolutionSummary = buildResolutionSummary(agentText, isResolved);

        // === 情绪分析 ===
        String initialSentiment = detectSentiment(conversation.getUserTurns().get(0).content());
        String finalSentiment = detectSentiment(
                conversation.getUserTurns().get(conversation.getUserTurns().size() - 1).content());
        String sentimentTrend = computeSentimentTrend(initialSentiment, finalSentiment);

        // === 实体抽取 ===
        List<String> orders = extractOrders(fullText);
        List<String> products = extractProducts(userText);

        // === 边界情况 ===
        boolean hasMultipleIntents = detectedIntents.size() > 1;
        boolean hasEscalation = containsAny(fullText, "转人工|转接|转.*客服|帮您转");
        boolean hasTopicSwitch = detectTopicSwitch(userText);
        boolean isIncomplete = detectIncomplete(conversation);
        boolean waitingTimeIssue = containsAny(userText, "等了|半天|好久|不回|没人|不.*理|没人理");

        // === 客服评估 ===
        String agentQuality = evaluateAgent(isResolved, sentimentTrend, conversation, hasEscalation);

        // === 主管标签 ===
        boolean needsFollowUp = !isResolved || "negative".equals(finalSentiment)
                || "angry".equals(finalSentiment) || hasEscalation || hasMultipleIntents;
        List<String> keyTags = buildKeyTags(primaryIntent, isResolved, sentimentTrend, hasEscalation);

        // === 生成摘要 ===
        String summary = buildSummary(conversation, primaryIntent, isResolved);

        log.info("Mock extraction complete for {}: intent={}, resolved={}, sentiment={}→{}",
                conversation.id(), primaryIntent, isResolved, initialSentiment, finalSentiment);

        return new ExtractionResult(
                conversation.id(),
                conversation.channel(),
                conversation.agent(),
                conversation.getTurnCount(),
                summary,
                primaryIntent,
                primaryIntent,  // intentCategory = primaryIntent for mock
                subIntents.isEmpty() ? null : subIntents,
                resolutionSummary,
                isResolved,
                resolutionType,
                initialSentiment,
                finalSentiment,
                sentimentTrend,
                orders.isEmpty() ? null : orders,
                products.isEmpty() ? null : products,
                hasMultipleIntents,
                hasEscalation,
                hasTopicSwitch,
                isIncomplete,
                agentQuality,
                waitingTimeIssue,
                needsFollowUp,
                keyTags
        );
    }

    // ======================== 私有方法 ========================

    private List<String> detectIntents(String text) {
        List<String> intents = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var entry : INTENT_KEYWORDS.entrySet()) {
            if (containsAny(text, entry.getKey())) {
                String cat = entry.getValue();
                if (seen.add(cat)) {
                    intents.add(cat);
                }
            }
        }
        return intents;
    }

    private boolean detectResolution(String agentText, String userText) {
        return containsAny(agentText, "已.*帮|已.*申请|已.*发起|已.*取消|已.*提交|帮您.*申请|帮您.*处理|帮您.*查|帮您.*安排|处理.*了|解决了|好的.*谢谢|可以.*谢谢|行.*谢谢|好吧|行吧|好的|可以")
                && !containsAny(userText, "算了|不用了|不看了");
    }

    private String detectResolutionType(String agentText, boolean isResolved) {
        if (!isResolved) {
            if (containsAny(agentText, "不用了|算了|不看了|用户主动")) {
                return ExtractionResult.ResolutionTypes.USER_ABANDONED;
            }
            if (containsAny(agentText, "建议.*记录|反馈.*产品|反馈.*团队|记录下来")) {
                return ExtractionResult.ResolutionTypes.FEEDBACK_ONLY;
            }
            return ExtractionResult.ResolutionTypes.UNRESOLVED;
        }
        if (containsAny(agentText, "退款")) return ExtractionResult.ResolutionTypes.REFUND;
        if (containsAny(agentText, "换新|换货|换.*码|发出新")) return ExtractionResult.ResolutionTypes.EXCHANGE;
        if (containsAny(agentText, "补发|重发|重新.*发")) return ExtractionResult.ResolutionTypes.REPLACEMENT_RESEND;
        if (containsAny(agentText, "优惠券|补偿|赠送.*券")) return ExtractionResult.ResolutionTypes.COMPENSATION_COUPON;
        if (containsAny(agentText, "转接|转.*人工|转.*客服")) return ExtractionResult.ResolutionTypes.ESCALATED;
        if (containsAny(agentText, "到货后|通知|短信|消息|提醒")) return ExtractionResult.ResolutionTypes.INFO_ANSWER;
        return ExtractionResult.ResolutionTypes.INFO_ANSWER;
    }

    private String buildResolutionSummary(String agentText, boolean isResolved) {
        if (!isResolved) return "问题未完全解决";
        // 截取 agent 最后一条有实质内容的回复
        String[] lines = agentText.split("。|；|，");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() > 10 && !line.contains("联系") && !line.contains("随时")) {
                return line;
            }
        }
        return "已处理";
    }

    private String detectSentiment(String text) {
        for (var entry : SENTIMENT_KEYWORDS.entrySet()) {
            if (containsAny(text, entry.getKey())) {
                return entry.getValue();
            }
        }
        return "neutral";
    }

    private String computeSentimentTrend(String initial, String final_) {
        int initialScore = sentimentScore(initial);
        int finalScore = sentimentScore(final_);
        if (finalScore > initialScore) return ExtractionResult.SentimentTrends.IMPROVED;
        if (finalScore < initialScore) return ExtractionResult.SentimentTrends.WORSENED;
        return ExtractionResult.SentimentTrends.STABLE;
    }

    private int sentimentScore(String s) {
        return switch (s) {
            case "angry" -> 0;
            case "negative" -> 1;
            case "neutral" -> 2;
            case "positive" -> 3;
            default -> 2;
        };
    }

    private List<String> extractOrders(String text) {
        Matcher m = ORDER_PATTERN.matcher(text);
        List<String> orders = new ArrayList<>();
        while (m.find()) {
            orders.add(m.group());
        }
        return orders;
    }

    private List<String> extractProducts(String userText) {
        // 简单商品名提取
        Map<String, String> productPatterns = new LinkedHashMap<>() {{
            put("蓝牙耳机", "蓝牙耳机");
            put("手机壳", "手机壳");
            put("充电宝", "充电宝");
            put("杯子", "杯子");
            put("碗", "碗");
            put("衣服|T恤|衬衫|裙子|裤子|外套", "服装");
            put("面膜", "面膜");
            put("扫地机器人", "扫地机器人");
            put("双肩包|背包|包", "包");
            put("手机", "手机");
            put("蓝色.*杯|蓝.*杯", "蓝色杯子");
        }};

        List<String> products = new ArrayList<>();
        for (var entry : productPatterns.entrySet()) {
            if (containsAny(userText, entry.getKey()) && !products.contains(entry.getValue())) {
                products.add(entry.getValue());
            }
        }
        return products;
    }

    private boolean detectTopicSwitch(String userText) {
        return containsAny(userText, "对了|另外|还有.*也|同时|顺便|帮我看看.*和.*哪个");
    }

    private boolean detectIncomplete(Conversation conv) {
        // 对话轮次过少或用户最后一条是放弃性表述
        if (conv.getTurnCount() <= 4) {
            String lastUser = conv.getUserTurns().get(conv.getUserTurns().size() - 1).content();
            if (containsAny(lastUser, "算了|不用了|不看了|我想想|嗯嗯")) {
                return true;
            }
        }
        // 转人工后无后续
        if (containsAny(conv.toFormattedText(), "转接人工|转人工客服") && conv.getTurnCount() <= 6) {
            return true;
        }
        return false;
    }

    private String evaluateAgent(boolean isResolved, String sentimentTrend,
                                  Conversation conv, boolean hasEscalation) {
        if (isResolved && "improved".equals(sentimentTrend)) return ExtractionResult.AgentQualities.EXCELLENT;
        if (isResolved && "stable".equals(sentimentTrend)) return ExtractionResult.AgentQualities.GOOD;
        if (!isResolved && hasEscalation) return ExtractionResult.AgentQualities.AVERAGE;
        if ("worsened".equals(sentimentTrend)) return ExtractionResult.AgentQualities.POOR;
        return ExtractionResult.AgentQualities.GOOD;
    }

    private List<String> buildKeyTags(String intent, boolean resolved, String trend, boolean escalation) {
        List<String> tags = new ArrayList<>();
        tags.add(intent);
        tags.add(resolved ? "已解决" : "未解决");
        if ("worsened".equals(trend)) tags.add("情绪恶化");
        if ("improved".equals(trend)) tags.add("情绪改善");
        if (escalation) tags.add("转人工");
        return tags;
    }

    private String buildSummary(Conversation conv, String primaryIntent, boolean resolved) {
        String firstUser = conv.getUserTurns().get(0).content();
        String brief = firstUser.length() > 40 ? firstUser.substring(0, 40) + "…" : firstUser;
        return String.format("用户咨询[%s]: %s —— %s",
                primaryIntent, brief, resolved ? "已解决" : "未解决");
    }

    private boolean containsAny(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }
}
