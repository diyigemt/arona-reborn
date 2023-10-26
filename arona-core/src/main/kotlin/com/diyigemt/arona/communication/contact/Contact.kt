package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.utils.childScopeContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

interface Contact : CoroutineScope {
  val bot: TencentBot // 与之关联的bot
  val id: String // 特定id
  val unionOpenid: String? // 统一id
  suspend fun sendMessage(message: Message)
}

internal abstract class AbstractContact(
  final override val bot: TencentBot,
  parentCoroutineContext: CoroutineContext
): Contact {
  final override val coroutineContext: CoroutineContext = parentCoroutineContext.childScopeContext()
}

interface Guild : Contact

interface Channel : Contact

interface Group : Contact

interface User : Contact

interface SingleUser : User // 单纯用户 私聊情况下

// 群组成员 群聊情况下
interface GroupMember : User {
  fun asSingleUser(): SingleUser
}

// 频道成员 频道聊天情况下
interface GuildMember : User {
  fun asGuildUser(): GuildUser
}
// 频道成员 私聊情况下
interface GuildUser : User
internal class SingleUserImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String?
) : SingleUser, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: Message) {
    // TODO 主动发送消息
  }
}
