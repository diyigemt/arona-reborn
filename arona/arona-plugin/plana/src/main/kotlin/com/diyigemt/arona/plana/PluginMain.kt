package com.diyigemt.arona.plana

import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.event.TencentCallbackButtonEvent
import com.diyigemt.arona.communication.event.TencentCallbackButtonEventResult
import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.event.recall
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.plana.db.PlanaDatabase
import com.diyigemt.arona.plana.db.PlanaRepository
import com.diyigemt.arona.plana.service.CosAuditService
import com.diyigemt.arona.plana.service.ImageAssetService
import com.diyigemt.arona.plana.service.ImageDownloadService
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("unused")
object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION
  )
) {
  override fun onLoad() {
    PlanaDatabase.init()

    launch {
      runCatching { AuditSwitchService.preload() }
        .onFailure { logger.error("预热审查开关失败", it) }
    }

    // 审查开关仅经「色色」菜单的回调按钮触发(按钮已用腾讯原生 MANAGER 权限限定群管理员)。
    pluginEventChannel().subscribeAlways<TencentCallbackButtonEvent> { ev ->
      if (!ev.buttonData.startsWith(AUDIT_CALLBACK_PREFIX)) return@subscribeAlways
      handleAuditSwitchButton(ev)
    }

    pluginEventChannel().subscribeAlways<TencentGroupMessageEvent> { ev ->
      if (!AuditSwitchService.isEnabled(ev.subject.id)) return@subscribeAlways
      val images = ev.message.filterIsInstance<TencentImage>()
      if (images.isEmpty()) return@subscribeAlways
      // 审查涉及网络 IO, 放到独立协程, 不阻塞事件分发。
      launch {
        runCatching { auditMessage(ev, images) }
          .onFailure { logger.warn("图片审查处理失败", it) }
      }
    }
  }

  /**
   * 处理审查开关回调按钮。按钮已携带 MANAGER 原生权限, 非管理员一般被客户端拦截, 此处不再二次鉴权
   * (框架也无法从回调事件判定群管理员身份)。开关语义只对群有效, 其余 chatType 一律拒绝。
   */
  private suspend fun handleAuditSwitchButton(ev: TencentCallbackButtonEvent) {
    if (ev.chatType != TencentWebsocketCallbackButtonChatType.Group) {
      rejectQuietly(ev, TencentCallbackButtonEventResult.PermissionDeny)
      return
    }
    val enabled = when (ev.buttonData) {
      AUDIT_CALLBACK_ON -> true
      AUDIT_CALLBACK_OFF -> false
      else -> {
        rejectQuietly(ev, TencentCallbackButtonEventResult.Failed)
        return
      }
    }

    val applied = runCatching { AuditSwitchService.setEnabled(ev.contact.id, enabled) }
      .onFailure { logger.warn("更新审查开关失败, contact=${ev.contact.id}", it) }
      .isSuccess
    if (!applied) {
      rejectQuietly(ev, TencentCallbackButtonEventResult.Failed)
      return
    }

    // 开关已落库即视为成功; 客户端回执与确认消息都尽力而为, 失败只记日志不回滚已生效的开关。
    runCatching { ev.accept() }.onFailure { logger.warn("回执审查开关按钮事件失败", it) }
    runCatching {
      val reply = if (enabled) "不许色色" else "可以色色"
      ev.contact.sendMessage(MessageChainBuilder(eventId = ev.eventId).append(reply).build())
    }.onFailure { logger.warn("发送审查开关确认消息失败", it) }
  }

  /** 拒绝回调并吞掉拒绝本身的 IO 异常, 仅记日志(与 accept 失败处理一致, 避免静默)。 */
  private suspend fun rejectQuietly(ev: TencentCallbackButtonEvent, reason: TencentCallbackButtonEventResult) {
    runCatching { ev.reject(reason) }.onFailure { logger.warn("拒绝审查开关按钮事件失败, reason=$reason", it) }
  }

  /** 审查一条消息内的所有图片; 命中则回复一次 h.jpg 并按命中张数累计排行。 */
  private suspend fun auditMessage(ev: TencentGroupMessageEvent, images: List<TencentImage>) {
    if (!CosAuditService.isConfigured()) return

    val flagged = images.count { image ->
      val score = auditImage(ev.bot.client, image.url)
      score != null && score > Config.pornThreshold
    }
    if (flagged <= 0) return

    // 先落库计数(命中是要持久记录的事实), 再回复 h.jpg; 发送失败只记日志, 不影响排行。
    PlanaRepository.incrementRank(ev.sender.id, flagged)
    runCatching {
      val warning = ImageAssetService.getImage(ev.subject, "h.jpg")
      ev.subject.sendMessage(warning)
      ev.recall()
    }.onFailure { logger.warn("发送 h.jpg 失败", it) }
  }

  /**
   * 返回图片审查分数; 返回 null 表示"无需或无法审查"(过小/下载失败/COS 失败/超时), 一律按放行(fail-open)处理。
   * 命中缓存直接复用历史分数; 仅在成功取得分数后才写缓存。
   */
  private suspend fun auditImage(client: HttpClient, url: String): Int? {
    // Ktor 是协作式可取消的, withTimeoutOrNull 能真正中断卡住的下载。
    val downloaded = withTimeoutOrNull(Config.auditTimeoutMillis) {
      runCatching {
        ImageDownloadService.download(client, url, Config.minImageSizeBytes)
      }.onFailure { logger.warn("下载待审图片失败, url=$url", it) }
        .getOrNull()
    } ?: return null

    PlanaRepository.findScore(downloaded.sha256)?.let { return it }

    val result = withTimeoutOrNull(Config.auditTimeoutMillis) {
      runCatching { CosAuditService.audit(downloaded.sha256, downloaded.bytes) }
        .onFailure { logger.warn("COS 图片审查失败, hash=${downloaded.sha256}", it) }
        .getOrNull()
    }
    if (result == null) {
      logger.warn("图片审查超时或失败, hash=${downloaded.sha256}")
      return null
    }

    PlanaRepository.saveScore(
      hash = downloaded.sha256,
      score = result.score,
      label = result.label,
      state = result.state,
      result = result.result,
      sizeBytes = downloaded.bytes.size
    )
    return result.score
  }

  // 回调按钮 data 用本插件命名空间, 避免与共享事件通道上其他插件的回调串台。
  internal const val AUDIT_CALLBACK_PREFIX = "plana:audit:"
  internal const val AUDIT_CALLBACK_OFF = "plana:audit:off" // 可以色色: 关闭本群审查
  internal const val AUDIT_CALLBACK_ON = "plana:audit:on"   // 不许色色: 开启本群审查
}
