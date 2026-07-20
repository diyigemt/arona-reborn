package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.message.RecallDestination
import com.diyigemt.arona.communication.message.RecallUnsupportedException
import com.diyigemt.arona.communication.message.recallMessage

/**
 * 撤回触发本事件的那条消息——包括**他人发送**的消息.
 *
 * 与 [com.diyigemt.arona.communication.message.MessageReceipt.recall] 的区别只在于消息来源:
 * 那边撤回的是 Bot 自己发出的消息 (id 取自发送回执), 这边撤回的是收到的消息 (id 取自
 * [TencentMessageEvent.message] 的 `sourceId`). 能否撤回取决于场景与权限, 而非调用方指定作者.
 *
 * 权限与限制 (均由服务端裁决, 本地不预判):
 * - 群聊中撤回成员消息要求 Bot 已被设为**群管理员**;
 * - 群聊与单聊超过两分钟的消息不可撤回, 本地不做时钟校验;
 * - 单聊与频道私信目前只允许撤回 Bot 自己发送的消息, 但这属于上游权限策略, 因此这里仍会照常发出
 *   请求并把服务端结论原样返回, 不在客户端硬编码"永不可撤回".
 *
 * 本地直接失败的情况只有三种: 消息 id 为空 (如 [mockGroupMessage] 造出的事件)、公域 Bot 调用仅
 * 私域可用的频道类端点、事件子类无法映射到端点——后两者返回 [RecallUnsupportedException].
 *
 * @param hideTip 是否隐藏撤回提示小灰条, 仅频道与频道私信端点有效, 群/单聊请求不会携带该参数.
 *
 * 内容审核场景:
 * ```kotlin
 * pluginEventChannel().subscribeAlways<TencentGroupMessageEvent> {
 *   if (isViolating(message)) {
 *     recall().onFailure { logger.warn("撤回违规消息失败", it) }
 *   }
 * }
 * ```
 */
suspend fun TencentMessageEvent.recall(hideTip: Boolean = false): Result<Unit> {
  // 必须按事件类型分派, 不能按 subject 类型: TencentGuildMessageEvent 与
  // TencentGuildPrivateMessageEvent 的 subject 都是 Channel, 但前者删到 /channels/{channel_id},
  // 后者删到 /dms/{guild_id}——按 contact 分派必然把两者混为一谈.
  val destination = when (this) {
    is TencentGroupMessageEvent -> RecallDestination.GroupChat(sender.group.id)
    is TencentGuildMessageEvent -> RecallDestination.GuildChannel(sender.channel.id)
    is TencentFriendMessageEvent -> RecallDestination.Friend(sender.id)
    is TencentGuildPrivateMessageEvent -> RecallDestination.GuildDirect(sender.guild.id)
    // TencentMessageEvent 不是 sealed, 新增子类时宁可明确失败, 也不要退化成按 subject 猜端点.
    else -> return Result.failure(
      RecallUnsupportedException("message event ${this::class.qualifiedName} has no recall endpoint")
    )
  }
  return bot.recallMessage(destination, message.sourceId, hideTip)
}
