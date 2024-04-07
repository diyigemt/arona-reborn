package com.diyigemt.kivotos.arena

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.subButton
import com.github.ajalt.clikt.core.requireObject
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.Serializable

/**
 * kivotos.arena.match.list redis->List FIFO队列, 保存匹配请求 contactId.userId
 *
 * kivotos.arena.match.contact.contactId redis->Hash 保存contactId中userId的匹配请求或战局id userId -> bid
 *
 * kivotos.arena.battle.id redis->Hash 保存战局id对应的战局信息
 */


private fun redisKey(k: String) = "kivotos.arena.$k"
// 匹配列表
private fun redisMatchKey(k: String) = redisKey("match.$k")
private val matchListKey = redisMatchKey("list")
private fun matchContactUserKey(cid: String) = redisMatchKey("contact.$cid")
// 战局信息
private fun redisBattleKey(bid: String) = redisKey("battle.$bid")

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
object ArenaCommand : AbstractCommand(
  Kivotos,
  "竞技场",
  description = "竞技场系列指令"
) {
  private val md by requireObject<TencentCustomMarkdown>()
  suspend fun UserCommandSender.arena() {
    tencentCustomMarkdown {
      h1("竞技场")
    } insertTo md
    val kb = tencentCustomKeyboard {
      row {
        subButton("竞技场 匹配")
        subButton("竞技场 排行榜")
      }
    }
    if (currentContext.invokedSubcommand == null) {
      sendMessage(md + kb)
    }
  }
}

@SubCommand(forClass = ArenaCommand::class)
@Suppress("unused")
object ArenaMatchCommand : AbstractCommand(
  Kivotos,
  "匹配",
  description = "竞技场匹配指令"
) {
  private val md by requireObject<TencentCustomMarkdown>()
  suspend fun UserCommandSender.match() {
    val contactMatch = redisDbQuery {
      hgetAll(matchContactUserKey(subject.id))
    }
    if (contactMatch.isNotEmpty() && contactDocument().readPluginConfigOrDefault(Kivotos, ArenaConfig()).limit) {
      sendMessage(md + tencentCustomMarkdown {
        at()
        +"群配置为同时只能有一场对战"
        +"<@${contactMatch.first()}>正在对战中"
      })
      return
    }
  }
}

@SubCommand(forClass = ArenaCommand::class)
@Suppress("unused")
object ArenaRankCommand : AbstractCommand(
  Kivotos,
  "排行榜",
  description = "竞技场排行榜指令"
) {

}

@Serializable
data class ArenaConfig(
  val limit: Boolean = true, // 是否限制同一时间只能有一个战局
) : PluginWebuiConfig() {
  override fun check() {}
}
