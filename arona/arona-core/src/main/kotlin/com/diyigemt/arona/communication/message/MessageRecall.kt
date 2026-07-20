package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import io.ktor.http.HttpMethod

/**
 * 撤回在**本地**就能判定不可能成立时的失败原因.
 *
 * 仅用于客户端自身结构性约束 (无法映射出目标端点、公域 Bot 调用私域专属端点). 服务端判定的失败
 * ——权限不足、超出两分钟时限、消息不存在——不会被包装成本异常, 而是原样保留
 * [TencentBot.callOpenapi] 返回的 [com.diyigemt.arona.communication.TencentApiErrorException],
 * 让调用方能按 `source.code` 区分.
 */
class RecallUnsupportedException(message: String) : UnsupportedOperationException(message)

/**
 * 撤回目标, 即"这条消息该往哪个端点删".
 *
 * 存在的意义是把"场景 → 端点 + 占位符 + 可选参数"收敛成唯一一张表: 发送回执撤回与消息事件撤回
 * 都要做这次映射, 各写一份 `when` 迟早会像历史实现那样一边对一边错 (频道两个端点的占位符 key
 * 曾长期写反, 且因替换是字面量 replace 而静默发出坏 URL).
 */
internal sealed interface RecallDestination {
  val endpoint: TencentEndpoint
  val placeholders: Map<String, String>

  /**
   * 端点是否接受 `hidetip` (是否隐藏撤回提示小灰条). 仅频道与频道私信支持,
   * 群/单聊端点带上该参数没有意义.
   */
  val supportsHideTip: Boolean

  /**
   * 端点是否仅私域机器人可用. 公域/私域是本机配置, 稳定可知, 因此这一条允许本地拦截.
   */
  val privateDomainOnly: Boolean

  /**
   * 单聊. 只能撤回 Bot 自己发送的消息.
   */
  data class Friend(val openid: String) : RecallDestination {
    override val endpoint = TencentEndpoint.DeleteFriendMessage
    override val placeholders = mapOf("openid" to openid)
    override val supportsHideTip = false
    override val privateDomainOnly = false
  }

  /**
   * 群聊. Bot 被设为群管理员时可撤回成员消息.
   */
  data class GroupChat(val groupOpenid: String) : RecallDestination {
    override val endpoint = TencentEndpoint.DeleteGroupMessage
    override val placeholders = mapOf("group_openid" to groupOpenid)
    override val supportsHideTip = false
    override val privateDomainOnly = false
  }

  /**
   * 文字子频道. 管理员可撤回普通成员消息, 频道主可撤回所有人消息.
   */
  data class GuildChannel(val channelId: String) : RecallDestination {
    override val endpoint = TencentEndpoint.DeleteGuildMessage
    override val placeholders = mapOf("channel_id" to channelId)
    override val supportsHideTip = true
    override val privateDomainOnly = true
  }

  /**
   * 频道私信. 注意占位符要的是私信会话的 guild id (与 [TencentEndpoint.PostGuildMemberMessage] 同源),
   * 既不是成员 id 也不是私信 channel id.
   */
  data class GuildDirect(val guildId: String) : RecallDestination {
    override val endpoint = TencentEndpoint.DeleteGuildMemberMessage
    override val placeholders = mapOf("guild_id" to guildId)
    override val supportsHideTip = true
    override val privateDomainOnly = true
  }
}

/**
 * 撤回的唯一执行路径. [MessageReceipt.recall] 与
 * [com.diyigemt.arona.communication.event.recall] 都收敛到这里.
 *
 * 只做两项本地校验, 其余一律交给服务端裁决——能否撤回他人消息属于上游随时可能放宽的权限策略
 * (本次群管理员撤回能力就是这么放开的), 在客户端镜像一份必然过期.
 */
internal suspend fun TencentBot.recallMessage(
  destination: RecallDestination,
  messageId: String,
  hideTip: Boolean,
): Result<Unit> {
  // 占位符校验只能发现 key 缺失, 发现不了 key 在但 value 为空. 而空 messageId 是真实可达路径:
  // mockGroupMessage() 造出的事件 sourceId 就是空串, 放过去会拼出 `/messages/` 这种尾部空段的 URL.
  if (messageId.isBlank()) {
    // 只带 endpoint 不带 destination: 后者的 toString 含 openid/guild id 这类身份标识, 异常常被直接打日志.
    return Result.failure(
      IllegalArgumentException("messageId must not be blank, endpoint=${destination.endpoint}")
    )
  }
  if (destination.privateDomainOnly && isPublic) {
    return Result.failure(
      RecallUnsupportedException("endpoint ${destination.endpoint} is available to private-domain bots only")
    )
  }
  return callOpenapi(
    endpoint = destination.endpoint,
    urlPlaceHolder = destination.placeholders + ("message_id" to messageId),
  ) {
    method = HttpMethod.Delete
    if (destination.supportsHideTip) {
      // callOpenapi 先写入绝对 URL 再执行本 block, 这里是往已建好的 URLBuilder 追加 query.
      url.parameters.append("hidetip", hideTip.toString())
    }
  }
}
