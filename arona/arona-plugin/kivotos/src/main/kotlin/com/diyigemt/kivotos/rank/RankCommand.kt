package com.diyigemt.kivotos.rank

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.communication.message.TencentCustomMarkdown.Companion.EmptyMarkdown
import com.diyigemt.arona.database.permission.SimplifiedUserDocument
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.arona.arona.database.DatabaseProvider
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.kivotos.subButton
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class RankCommand : AbstractCommand(
  Kivotos,
  "排行榜",
  description = "排行榜系列指令",
  help = tencentCustomMarkdown {
    list {
      +"/赛博基沃托斯 排行榜 好感度 ,查看好感度排行榜"
    }
  }.content
) {
  private val kb by lazy {
    tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
      row {
        subButton("好感度排行", "排行榜 好感度", enter = true)
        button("没做,放着好看", "")
      }
    }
  }
  suspend fun UserCommandSender.rank() {
    if (currentContext.invokedSubcommand == null) {
      sendMessage(EmptyMarkdown + kb)
    }
  }
}

data class FavorRankData(
  val uid: String,
  val sid: Int,
  val favor: Pair<Int, Int>
)

@SubCommand(forClass = RankCommand::class)
@Suppress("unused")
class RankFavorCommand : AbstractCommand(
  Kivotos,
  "好感度",
  description = "好感度排行榜查看",
  help = tencentCustomMarkdown {
    list {
      +"/赛博基沃托斯 排行榜 好感度 ,查看好感度排行总榜"
      +"/赛博基沃托斯 排行榜 好感度 星野,查看星野好感度排行榜"
    }
  }.content
) {
  private val student by argument("学生名").optional()
  suspend fun UserCommandSender.favorRank() {
    val ranks = RankManager.getFavorRank()
    val usernames = SimplifiedUserDocument.queryUsername(ranks.map { it.uid })
    val students = DatabaseProvider.dbQuery {
      StudentSchema.find( StudentTable.id inList ranks.map { it.sid } ).toList()
    }
    val studentNames = ranks.map { it.sid }.associateWith { students.first { s -> s.id.value == it } }
    if (student == null) {
      // 总榜
      RankManager.getFavorRank().let {
        tencentCustomMarkdown {
          list {
            it.forEachIndexed { index, data ->
              +("${index + 1}.\t${studentNames[data.sid]?.name}(${data.favor.first}级/${data.favor.second})\t" +
                  "${usernames[index].username}\t")
            }
          }
        }
      }.also {
        sendMessage(it)
      }
    }
  }
}