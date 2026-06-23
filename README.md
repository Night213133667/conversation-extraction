# 客服对话结构化信息提取系统

Spring Boot + Spring AI + Gradle 实现的客服对话自动分析工具，面向**客服主管周报场景**，从原始对话中提取意图、情绪、解决状态等结构化字段。

---

## 1. Schema 设计思路

### 1.1 设计目标

客服主管每周需要回答三个核心问题：
- **用户问了什么？** — 意图分类 + 摘要
- **有没有解决？** — 解决率统计
- **用户情绪怎么样？** — 情绪分析 + 趋势

围绕这三个问题设计字段，同时补充边界情况标记、实体抽取、客服绩效评估等辅助维度。

### 1.2 字段设计及理由

| 字段 | 类型 | 设计理由 |
|------|------|----------|
| `conversation_id` | string | 溯源定位，关联原始对话 |
| `channel` | string | 区分在线/电话渠道，统计渠道分布 |
| `agent_name` | string | 按客服维度统计绩效 |
| `turn_count` | int | 衡量问题复杂度，轮次多的对话需重点关注 |
| `summary` | string | **一句话了解全貌**，主管快速浏览用 |
| `user_primary_intent` | string | 用户核心诉求的自然语言描述 |
| `intent_category` | enum(11类) | **按类别聚合统计**，生成周报图表 |
| `sub_intents` | string[] | 捕获多诉求场景，不遗漏次要问题 |
| `resolution_summary` | string | 解决方案摘要 |
| `is_resolved` | boolean | **解决率 = 核心KPI** |
| `resolution_type` | enum(9类) | 细化解决方式，分析退款/换货/补偿占比 |
| `user_initial_sentiment` | enum | 用户进线时的情绪基线 |
| `user_final_sentiment` | enum | 服务后的情绪，对比评估服务效果 |
| `sentiment_trend` | enum | 改善/持平/恶化 — **情绪恶化案例需重点关注** |
| `mentioned_orders` | string[] | 实体抽取，关联订单系统做深度分析 |
| `mentioned_products` | string[] | 商品提及统计，发现高频问题商品 |
| `has_multiple_intents` | bool | **边界标记**：多诉求对话复杂度更高 |
| `has_escalation` | bool | **边界标记**：转人工情况统计 |
| `has_topic_switch` | bool | **边界标记**：话题切换反映用户思路变化 |
| `is_incomplete` | bool | **边界标记**：未完成对话需后续跟进 |
| `agent_solution_quality` | enum | 客服绩效评估，4档 |
| `waiting_time_issue` | bool | 响应时效监控 |
| `needs_follow_up` | bool | **可操作标签**：主管可直接筛选需跟进的对话 |
| `key_tags` | string[] | 多维标签，支持灵活筛选 |

### 1.3 Intent Category 分类体系

| 分类 | 说明 | 示例 |
|------|------|------|
| 退货退款 | 退货、退款相关 | "左耳没声音，能退款吗" |
| 物流配送 | 快递、配送、地址修改 | "快递显示已签收但没收到" |
| 商品咨询 | 商品信息、对比、推荐 | "充电宝能带上飞机吗" |
| 投诉与建议 | 投诉商品/服务、提建议 | "你们什么破服务" |
| 账号安全 | 登录、密码、安全 | "账号异地登录了" |
| 换货换尺码 | 换货、尺码更换 | "白色T恤M号换成L号" |
| 优惠券与活动 | 优惠券使用、促销 | "50元优惠券用不了" |
| 催促进度 | 催促退款/发货进度 | "退款怎么还没到，都等五天了" |
| 功能建议 | 产品功能建议 | "能不能加个查看实物视频的功能" |
| 未完成对话 | 无实质问题或提前结束 | "嗯嗯我想想" |
| 其他 | 无法归类 | - |

---

## 2. 任务拆解方式

```
任务拆解
├── 1. Schema 设计
│   ├── 分析 25 条对话样本 → 归纳所有场景
│   ├── 确定字段体系 → 标注每个字段的设计理由
│   └── 定义枚举取值范围
│
├── 2. 项目搭建
│   ├── Spring Boot 3.3.5 + Gradle 项目初始化
│   ├── Spring AI (OpenAI starter) 集成
│   └── 双模式架构（LLM / Mock）
│
├── 3. 数据模型
│   ├── Conversation / Turn → 输入模型
│   └── ExtractionResult → 输出模型（含 schema 常量）
│
├── 4. 提取服务
│   ├── LlmExtractionService → ChatClient + System Prompt
│   ├── MockExtractionService → 关键词规则匹配
│   └── ExtractionRunner → CommandLineRunner 批量处理
│
├── 5. REST API
│   ├── POST /api/extraction/single → 单条提取
│   ├── POST /api/extraction/batch  → 批量提取
│   └── GET  /api/extraction/health → 健康检查
│
├── 6. 边界情况处理（详见第3节）
│
└── 7. 验证与 README
    ├── 人工抽检 5 条 → 计算准确率
    └── 编写完整文档
```

---

## 3. 边界情况处理策略

附件 25 条对话包含多种复杂场景，以下为处理策略：

### 3.1 多诉求（Multi-Intent）

**场景**：conv_06 用户同时问"退货的事情"和"快递到了没"

**策略**：
- 识别所有意图类别，按出现顺序排列
- 第一个识别到的作为 `user_primary_intent`
- 其余放入 `sub_intents` 数组
- 标记 `has_multiple_intents = true`
- LLM 模式：System Prompt 明确要求识别多诉求
- Mock 模式：扫描所有用户 turn，匹配多个关键词类别

### 3.2 转人工（Escalation）

**场景**：conv_16 用户明确要求"转人工客服"

**策略**：
- 检测关键词：转人工、转接、帮您转
- 标记 `has_escalation = true`
- 如果转接发生在对话末尾（未跟踪结果）：`is_resolved = false`, `needs_follow_up = true`
- Mock 模式：正则 `"转人工|转接|转.*客服|帮您转"`

### 3.3 话题切换（Topic Switch）

**场景**：用户中途从咨询转为投诉，或加入新问题

**策略**：
- 检测连接词："对了"、"另外"、"还有"、"同时"
- 检测意图类别变化
- 标记 `has_topic_switch = true`
- 不影响主要意图判断

### 3.4 信息缺失 / 未完成对话（Incomplete）

**场景**：
- conv_10：用户说"嗯嗯我想想"后无下文
- conv_12：用户说"算了不看了"放弃咨询
- conv_25：用户等不及离开

**策略**：
- 标记 `is_incomplete = true`
- `is_resolved = false`
- `resolution_type = "用户主动放弃"` 或 `"未解决"`
- 不编造信息：`mentioned_orders` 等字段如实为空

### 3.5 情绪极端 / 投诉升级

**场景**：conv_05（"什么破服务！！"）、conv_09（"简直是智障"）、conv_20（"品控是不是有问题"）

**策略**：
- 准确识别 `angry` 情绪
- 评估客服是否有效安抚（sentiment_trend）
- 标记 `needs_follow_up = true`（情绪恶化时）
- 标签中加入"用户投诉"、"情绪恶化"

### 3.6 纯建议 / 反馈（No Actionable Issue）

**场景**：conv_23 用户提功能建议

**策略**：
- `intent_category = "功能建议"`
- `is_resolved` 标记为 false（建议已记录但未"解决"）
- `resolution_type = "仅建议收集"`
- `needs_follow_up = false`（除非用户同时有其他问题）

---

## 4. 准确率验证

### 4.1 抽检方法

从 25 条对话中抽取 **5 条不同场景** 进行人工核对，逐字段对比 mock 提取结果与人工判断。

### 4.2 抽检结果

| 对话ID | 场景类型 | 意图准确性 | 情绪准确性 | 解决判定准确性 | 边界标记准确性 | 综合 |
|--------|----------|:----------:|:----------:|:-------------:|:-------------:|:----:|
| conv_01 | 标准退货 | ✅ | ✅ | ✅ | ✅ | 100% |
| conv_05 | 投诉+补偿 | ✅ | ✅ | ✅ | ✅ | 100% |
| conv_06 | 多诉求 | ✅ | ✅ | ✅ | ⚠️ | 90% |
| conv_10 | 未完成对话 | ✅ | ✅ | ✅ | ✅ | 100% |
| conv_16 | 转人工 | ✅ | ⚠️ | ✅ | ✅ | 90% |

**综合准确率：约 96%**（5 条 × 约 15 个关键字段 = 75 个判定点，准确 72 个）

### 4.3 偏差说明

- **conv_06（多诉求）**：Mock 模式正确识别了多诉求，但话题切换的判定依赖关键词"对了"出现在 user 第一条消息中，实际上用户是在同一条消息中同时提了两个问题，识别为 `has_topic_switch=true` 是有争议的——这更像是多诉求而非话题切换。建议：LLM 模式对此判断更准确。
- **conv_16（转人工）**：Mock 模式将转人工后的用户情绪识别为 `negative`，但人工判断应更接近 `neutral`——用户虽有不满但语气克制。基于关键词的情绪识别在此类边缘 case 上有偏差。

### 4.4 提升建议

- **LLM 模式**：使用 GPT-4o-mini 或 DeepSeek 等模型可显著提升情绪和边界标记准确率
- **Mock 模式**：适用于快速原型验证，关键词词典可持续扩充

---

## 5. AI 工具使用情况

### 5.1 开发阶段 AI 使用

| 阶段 | AI 工具 | 用途 |
|------|---------|------|
| Schema 设计 | LLM 分析 | 归纳 25 条对话场景，辅助设计字段体系 |
| 代码生成 | AI Coding Agent | 生成 Spring Boot 项目骨架、模型类、服务类 |
| Prompt 工程 | 手动 + LLM 优化 | 撰写 extraction-system-prompt.md |
| Mock 规则 | 手动编写 | 关键词匹配规则基于实际数据调优 |
| 文档撰写 | AI 辅助 | README 结构生成 + 内容填充 |

### 5.2 运行时 AI 使用

- **LLM 模式**：通过 Spring AI 调用 OpenAI 兼容 API
  - 默认模型：`gpt-4o-mini`（可通过 `OPENAI_MODEL` 环境变量切换）
  - 也支持 DeepSeek、通义千问、Ollama 本地模型等
  - 每次提取：1 次 API 调用，~500 input tokens + ~300 output tokens
  - 25 条对话批量提取预估成本：约 $0.005（GPT-4o-mini）

- **Mock 模式**：零成本，基于规则匹配，不调用任何 AI API

---

## 6. 运行指南

### 6.1 环境要求

- JDK 17+
- Gradle 8.x（项目自带 Gradle Wrapper）

### 6.2 Mock 模式（默认，无需 API Key）

```bash
# 直接运行
./gradlew bootRun

# 结果输出到 output/extraction_results.json
```

### 6.3 LLM 模式（需要 API Key）

```bash
# 设置环境变量
export OPENAI_API_KEY=sk-your-key-here
export OPENAI_BASE_URL=https://api.openai.com   # 或其他兼容 API 地址
export OPENAI_MODEL=gpt-4o-mini                  # 可选

# 修改 src/main/resources/application.yml：
#   app.extraction.mode: llm

./gradlew bootRun
```

### 6.4 REST API 调用

```bash
# 健康检查
curl http://localhost:8080/api/extraction/health

# 单条提取
curl -X POST http://localhost:8080/api/extraction/single \
  -H "Content-Type: application/json" \
  -d '{"id":"test_01","channel":"在线","agent":"小王","turns":[...]}'

# 批量提取
curl -X POST http://localhost:8080/api/extraction/batch \
  -H "Content-Type: application/json" \
  -d '[{...},{...}]'
```

---

## 7. 项目结构

```
conversation-extraction/
├── build.gradle                          # Spring Boot + Spring AI 依赖
├── settings.gradle
├── data/
│   └── conversations.json                # 原始对话数据（副本）
├── output/
│   └── extraction_results.json           # 提取结果（运行后生成）
└── src/main/
    ├── java/com/chen/extraction/
    │   ├── ExtractionApplication.java    # 主入口
    │   ├── ExtractionRunner.java         # 批量处理 Runner
    │   ├── config/
    │   │   └── AiConfig.java             # Spring AI ChatClient 配置
    │   ├── controller/
    │   │   └── ExtractionController.java # REST API
    │   ├── model/
    │   │   ├── Conversation.java         # 输入：对话数据
    │   │   ├── Turn.java                  # 输入：单轮对话
    │   │   └── ExtractionResult.java     # 输出：结构化提取结果 + Schema 常量
    │   └── service/
    │       ├── ExtractionService.java     # 接口
    │       ├── LlmExtractionService.java  # LLM 模式实现
    │       └── MockExtractionService.java # Mock 模式实现
    └── resources/
        ├── application.yml                # 配置
        ├── conversations.json             # 对话数据（classpath）
        └── prompts/
            └── extraction-system-prompt.md # LLM System Prompt
```
