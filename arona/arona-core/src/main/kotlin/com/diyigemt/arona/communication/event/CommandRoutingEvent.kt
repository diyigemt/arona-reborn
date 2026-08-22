package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.MessageChain

/**
 * 指令路由未命中任何指令 ([com.diyigemt.arona.command.CommandExecuteResult.UnresolvedCommand]) 时的兜底事件,
 * 由 chat-command 插件在路由结束后广播, 供闲聊类插件接管 "不是指令的消息".
 *
 * 只在**未命中**时广播: 命中但参数不匹配 / 权限不足 / 执行失败都不算, 那些消息已经由指令链路消费.
 *
 * [sender] 是路由阶段已经构造好的 sender, 序列号 (msg_seq) 尚未被消费; 消费方**必须复用它发送**, 不要再对
 * [originEvent] 调用 `toCommandSender()` —— 每个 sender 实例各自从 1 发号, 同一 msg_id 下两个 sender 会撞
 * MessageDuplicationException.
 *
 * [originEvent] 与 chat-command 订阅的类型一致 (含单聊/频道); 消费方按需 `is TencentGroupMessageEvent` 过滤.
 */
class TencentUnresolvedCommandEvent(
  val sender: UserCommandSender,
  val message: MessageChain,
  val originEvent: TencentMessageEvent,
) : TencentBotEvent, AbstractEvent() {
  override val bot get() = originEvent.bot
  override fun toString(): String = "TencentUnresolvedCommandEvent(${originEvent.subject.id}) ${originEvent.sender.id} -> $message"
}
