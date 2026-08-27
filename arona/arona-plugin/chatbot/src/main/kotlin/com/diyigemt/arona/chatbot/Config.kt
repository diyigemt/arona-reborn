@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.chatbot

import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import com.diyigemt.arona.webui.pluginconfig.ConfigEnumEntry
import com.diyigemt.arona.webui.pluginconfig.ConfigItem
import com.diyigemt.arona.webui.pluginconfig.FieldError
import com.diyigemt.arona.webui.pluginconfig.PluginConfigCheckResult
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * 进程级配置, 落在 `config/com.diyigemt.arona.chatbot/config.yml`. 不分群: 密钥、模型、时间预算、全局限流.
 * [apiKey] 为空时整个插件按未启用处理 (观察仍落库, 但不会调模型).
 */
object ChatbotSecrets : AutoSavePluginData("config") {
  /** 总开关. 关闭后两个 listener 直接返回, 不读群配置. */
  val enabled by value(false)
  val apiKey by value("")
  val baseUrl by value("https://api.deepseek.com")
  val chatModel by value("deepseek-v4-flash-vision-exp")

  /** 单次聊天模型调用超时, 不重试. */
  val llmTimeoutMillis by value(8_000L)
  /** 内容审核超时, 超时即判拒 (fail-closed), 不重试. */
  val auditTimeoutMillis by value(3_000L)
  /** 发送超时, 不重试 (重试 = 抢同 msg_id 的 5 次被动回复配额). */
  val sendTimeoutMillis by value(3_000L)
  /** 等待类步骤 (gate → 模型 → 审核 → 配图) 的硬顶. 发送阶段每步有独立超时, 在预算之外. 30s 是体验上限, 不是平台 5 分钟窗口. */
  val totalBudgetMillis by value(30_000L)

  // ---- 分段回复: 进程级打字速度调参; 是否启用与最大段数是群配置 ----
  /** 段间延迟按上一段字数模拟打字: 中日韩每字毫秒数, 其它字符减半. */
  val segmentPerCharDelayMillis by value(300L)
  val segmentMinDelayMillis by value(500L)
  val segmentMaxDelayMillis by value(3_000L)

  /** 消息创建时间距今超过此秒数视为陈旧 (重启 / webhook 重投), 直接不回. 同时覆盖 5 分钟被动回复窗口. */
  val staleSec by value(60L)
  /** 观察库 `chatContext` 的 TTL (Mongo 过期索引, 改动需手动重建索引). */
  val contextTtlHours by value(24L)
  /** 装配 prompt 时取最近多少条群消息. */
  val historyLimit by value(20)

  /** 全局按群限流: 每群每分钟最多开始多少轮回复 (每秒 1 轮); 分段回复一轮只消耗一次令牌, 底层消息数可达轮数 × 段数. */
  val rateLimitPerMinute by value(10L)

  // ---- 记忆压缩 (P1): 每群一条滚动摘要, 回复成功后评估, 条数 OR usage 任一达标即压缩 ----
  /** 记忆总开关. 关闭后不读摘要也不压缩, 只剩 historyLimit 时间窗. */
  val memoryEnabled by value(true)
  /** 未压缩行数达到此值即压缩. */
  val memoryCompressAfterLines by value(60)
  /** 本轮对话响应的 usage.prompt_tokens 达到此值即压缩 (含人设; usage 缺失时只看条数). */
  val memoryCompressPromptTokens by value(6_000)
  /** 摘要最大字数: 提示模型, 落库前也硬截断. */
  val memoryMaxChars by value(600)
  /** 单次压缩最多吃多少行, 其余留到下次回复后. */
  val memoryBatchLimit by value(200)
  /** 摘要模型调用超时, 独立于对话超时 (输入更长, 且不占回复的 30s 预算). */
  val memoryTimeoutMillis by value(15_000L)
  /** 摘要闲置过期天数 (按 updatedAt 的 TTL 索引, 改动需手动重建索引). raw 只留 24h, 摘要不该永生. */
  val memoryTtlDays by value(7L)

  // ---- 看图 (P2): 当前消息里的图片以 base64 交给视觉模型 ----
  val visionEnabled by value(true)
  /** 回复路径单张图片字节上限, 超过的图忽略 (仍按纯文本回复). */
  val imageMaxBytes by value(4L * 1024 * 1024)
  /** 回复路径图片下载整批超时 (≤2 张并发). */
  val imageDownloadTimeoutMillis by value(5_000L)
  /** 带图的模型调用超时, 比纯文本长. */
  val visionTimeoutMillis by value(12_000L)

  // ---- 表情库 (P2): 观察路径抓取群里的表情包, 模型打标后存进数据目录, 回复时可配图 ----
  val stickerCaptureEnabled by value(true)
  /** true 时模型判定 nsfw_risk=low 的表情直接 ready; 否则一律 pending 等人工审核 (P3 webui). */
  val stickerAutoApprove by value(false)
  /** true 时选图不分来源群; 默认只在本群见过的表情里选, 避免 A 群的图出现在 B 群. */
  val stickerShared by value(false)
  /** 抓取的单张上限: 长截图通常更大, 超过直接放弃, 不下载全量. */
  val stickerMaxBytes by value(2L * 1024 * 1024)
  /** 最长边超过视为截图/照片, 不入库. */
  val stickerMaxSide by value(1024)
  /** 长宽比超过视为长截图, 不入库. */
  val stickerMaxAspect by value(3.0)
  /** 全局每小时最多分析多少张候选表情 (视觉模型调用预算). */
  val stickerCapturePerHour by value(60L)

  /**
   * 表情文件存在插件数据目录 `data/com.diyigemt.arona.chatbot/sticker/` (见 [StickerFiles]); 这是运营页展示用的公网前缀,
   * 由运维用 nginx 只把该子目录映射过来 (autoindex off), 如 `https://example.com/chatbot-sticker`. 为空则运营页不显示图, 其它功能不受影响.
   */
  val stickerPublicBaseUrl by value("")
}

enum class ProbabilityMode {
  /** 抽卡累加: 初始 [ChatbotConfig.pityBase], 每条未中 +[ChatbotConfig.pityStep], 发出后重置. 源项目实际行为. */
  @ConfigEnumEntry("抽卡累加")
  PITY,

  /** 每条独立掷骰 [ChatbotConfig.fixedProbability]. 源项目文档描述的行为. */
  @ConfigEnumEntry("固定概率")
  FIXED,
}

/**
 * 按群配置 (contact 层), webui 表单自动生成. 每个群自己开关、自己的人设.
 */
@Serializable
data class ChatbotConfig(
  @EncodeDefault
  @ConfigItem(label = "启用闲聊", group = "基础")
  val enabled: Boolean = false,

  @EncodeDefault
  @ConfigItem(label = "人设提示词", group = "基础", widget = "textarea", placeholder = "你是一只猫娘群友……")
  val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,

  @EncodeDefault
  @ConfigItem(label = "唤起先导词", group = "基础", description = "消息以「先导词+空格」开头必答; @机器人 亦必答")
  val mustPrefixes: List<String> = listOf("阿罗娜"),

  @EncodeDefault
  @ConfigItem(label = "概率模式", group = "概率")
  val probabilityMode: ProbabilityMode = ProbabilityMode.PITY,

  @EncodeDefault
  @ConfigItem(label = "固定概率", group = "概率", description = "FIXED 模式下每条消息的回复概率 (0~1)")
  val fixedProbability: Double = 0.1,

  @EncodeDefault
  @ConfigItem(label = "累加初始概率", group = "概率", description = "PITY 模式初始值 (0~1)")
  val pityBase: Double = 0.0005,

  @EncodeDefault
  @ConfigItem(label = "累加步长", group = "概率", description = "PITY 模式每条未中累加 (0~1)")
  val pityStep: Double = 0.0001,

  @EncodeDefault
  @ConfigItem(label = "回复冷却(秒)", group = "节奏", description = "两次回复的最小间隔; 必答不受限")
  val cooldownSec: Int = 10,

  @EncodeDefault
  @ConfigItem(label = "闭嘴关键词", group = "节奏", description = "消息等于关键词时本群静默一段时间")
  val muteKeywords: List<String> = listOf("闭嘴"),

  @EncodeDefault
  @ConfigItem(label = "闭嘴时长(秒)", group = "节奏")
  val muteDurationSec: Int = 600,

  @EncodeDefault
  @ConfigItem(label = "单条消息最大字数", group = "节奏", description = "超过不回 (也不计入概率)")
  val maxUserChars: Int = 500,

  @EncodeDefault
  @ConfigItem(label = "分段回复", group = "分段", description = "把回复按语义标点拆成多条依次发送, 模拟真人打字; 会消耗更多被动回复配额. 默认人设只要求一句话, 想稳定多段请同步放宽人设")
  val segmentReply: Boolean = false,

  @EncodeDefault
  @ConfigItem(label = "最大分段数", group = "分段", description = "2~4; 上限 4 是为同一条消息的 5 次被动回复配额留余量")
  val segmentMaxCount: Int = 3,

  @EncodeDefault
  @ConfigItem(label = "收集表情包", group = "表情", description = "把群里发的表情包存进图库 (需管理员审核后才会被使用)")
  val stickerCapture: Boolean = true,

  @EncodeDefault
  @ConfigItem(label = "配图概率", group = "表情", description = "每次回复附带一张表情包的概率 (0~1)")
  val stickerReplyProbability: Double = 0.3,
) : PluginWebuiConfig() {
  override fun check(): PluginConfigCheckResult {
    val errors = buildList {
      fun unit(name: String, v: Double) { if (v !in 0.0..1.0) add(FieldError(name, "必须在 0~1 之间")) }
      fun nonNegative(name: String, v: Int) { if (v < 0) add(FieldError(name, "不能为负")) }
      unit("fixedProbability", fixedProbability)
      unit("pityBase", pityBase)
      unit("pityStep", pityStep)
      unit("stickerReplyProbability", stickerReplyProbability)
      nonNegative("cooldownSec", cooldownSec)
      nonNegative("muteDurationSec", muteDurationSec)
      if (maxUserChars !in 1..MAX_USER_CHARS_CEILING) add(FieldError("maxUserChars", "必须在 1~$MAX_USER_CHARS_CEILING 之间"))
      if (segmentMaxCount !in 2..4) add(FieldError("segmentMaxCount", "必须在 2~4 之间"))
      if (systemPrompt.length > MAX_PROMPT_CHARS) add(FieldError("systemPrompt", "不能超过 $MAX_PROMPT_CHARS 字"))
    }
    return if (errors.isEmpty()) PluginConfigCheckResult.PluginConfigCheckAccept()
    else PluginConfigCheckResult.PluginConfigCheckReject("配置不合法", errors)
  }

  companion object {
    const val MAX_USER_CHARS_CEILING = 4_000
    const val MAX_PROMPT_CHARS = 4_000
    const val DEFAULT_SYSTEM_PROMPT =
      "你是群里的一位普通群友, 性格像一只慵懒的猫娘, 说话简短口语化, 偶尔在句尾加“喵”. " +
        "只回复一句话, 不要复述别人的话, 不要提及自己是 AI."
  }
}
