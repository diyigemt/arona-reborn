package com.diyigemt.arona.chatbot

import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.event.TencentUnresolvedCommandEvent
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.database.permission.findContactPluginDocumentOrNull
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import kotlinx.coroutines.launch
import java.util.Date

@Suppress("unused")
object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION,
  ),
) {
  override fun onLoad() {
    launch {
      runCatchingCancellable { ChatStore.ensureIndexes(ChatbotSecrets.contextTtlHours) }
        .onFailure { logger.warn("创建 chatbot 索引失败", it) }
    }

    // 观察: 启用群的全部消息 (含命中指令的) 落库. listener 立刻 launch 出去, 不拖慢 webhook 分发.
    pluginEventChannel().subscribeAlways<TencentGroupMessageEvent> { ev ->
      if (!ChatbotSecrets.enabled) return@subscribeAlways
      launch {
        runCatchingCancellable { observe(ev) }.onFailure { logger.warn("chatbot 观察落库失败", it) }
      }
    }

    // 回复: 指令未命中的群消息才进入. 复用事件携带的 sender, 见 TencentUnresolvedCommandEvent KDoc.
    pluginEventChannel().subscribeAlways<TencentUnresolvedCommandEvent> { ev ->
      val origin = ev.originEvent as? TencentGroupMessageEvent ?: return@subscribeAlways
      if (!ChatbotSecrets.enabled || ChatbotSecrets.apiKey.isBlank()) return@subscribeAlways
      launch {
        runCatchingCancellable {
          val cfg = groupConfig(origin.group.id)?.takeIf { it.enabled } ?: return@launch
          ChatbotPipeline.handle(ev.sender, origin, cfg)
        }.onFailure { logger.warn("chatbot 回复流水线异常", it) }
      }
    }
  }

  private suspend fun observe(ev: TencentGroupMessageEvent) {
    // P0 不看图: 纯图片消息记占位, 让上下文里"有人发了图"这一轮不缺席; 真看图是 P2.
    val text = ev.message.plainText().ifEmpty { if (ev.message.any { it is TencentImage }) IMAGE_PLACEHOLDER else return }
    if (groupConfig(ev.group.id)?.enabled != true) return
    ChatStore.upsert(
      ChatLine(
        id = ev.message.sourceId,
        groupId = ev.group.id,
        senderId = ev.sender.id,
        senderName = ev.platformUsername,
        content = text,
        fromBot = false,
        ts = parseTimestampMillis(ev.timestamp)?.let(::Date) ?: Date(),
      ),
    )
  }

  private const val IMAGE_PLACEHOLDER = "[图片]"

  // ponytail: 每条消息一次 Mongo 读; 全量消息下若成瓶颈, 加 30s 的 ConcurrentHashMap 缓存即可.
  private suspend fun groupConfig(groupId: String): ChatbotConfig? =
    findContactPluginDocumentOrNull(groupId)?.readPluginConfigOrNull<ChatbotConfig>(this)
}
