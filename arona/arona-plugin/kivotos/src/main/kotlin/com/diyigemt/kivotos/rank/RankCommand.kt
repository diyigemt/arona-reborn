package com.diyigemt.kivotos.rank

import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.name.TeacherNameSchema
import com.diyigemt.arona.arona.database.name.TeacherNameTable
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.subButton
import com.diyigemt.kivotos.tools.normalizeStudentName
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

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
  suspend fun UserCommandSender.rank() {
    if (currentContext.invokedSubcommand == null) {
      sendMessage(md + kb)
    }
  }

  companion object {
    private val md by lazy {
      tencentCustomMarkdown {
        h1("排行榜")
      }
    }

    private val kb by lazy {
      tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
        row {
          subButton("好感度排行", "排行榜 好感度", enter = true)
          button("没做,放着好看", "")
        }
      }
    }
  }
}

data class FavorRankData(
  val rank: Long,
  val uid: String,
  val sid: Int,
  val favor: Pair<Int, Int>,
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
    if (student == "我的") {
      sendRank(
        "我的总榜",
        RankManager.getUserStudentFavorRank(userDocument().id),
        showSelfPosition = false
      )
      return
    }
    val sid = student?.let {
      normalizeStudentName(it)?.let { name ->
        StudentSchema.StudentCache.values.firstOrNull { st -> name == st.name }
      }
    }
    if (sid == null) {
      sendRank(
        "好感度总榜",
        RankManager.getFavorRank(),
        RankManager.getUserFavorRank(userDocument().id)
      )
    } else {
      sendRank(
        "${sid.name}好感度榜",
        RankManager.getFavorRank(sid.id.value),
        RankManager.getUserFavorRank(userDocument().id, sid.id.value)
      )
    }
  }

  private suspend fun UserCommandSender.sendRank(
    title: String,
    ranks: List<FavorRankData>,
    self: FavorRankData? = null,
    showSelfPosition: Boolean = true,
  ) {
    val ids = (ranks.map { it.uid } + listOf(self?.uid ?: userDocument().id))
    // 根据uid反查botId
    val usernames = dbQuery {
      TeacherNameSchema.find {
        TeacherNameTable.id inList
          ids
      }.toList()
    }.let {
      it.associateBy {
        ids.first { s -> s == it.id.value }
      }
    }
    val students = StudentSchema.StudentCache
      .filter { it.key in (ranks.map { r -> r.sid } + listOf(self?.sid)) }
      .values
    val studentNames = (ranks.map { it.sid } + listOf(self?.sid))
      .associateWith {
        students
          .firstOrNull { s -> s.id.value == it }
      }

    fun toString(data: FavorRankData) = "${studentNames[data.sid]?.name ?: "-"}(${data.favor.first}级/${
      data.favor
        .second
    })" +
      "\t" + usernames[data.uid]?.name
    ranks.let {
      tencentCustomMarkdown {
        h1(title)
        indexedList {
          it.forEach { data ->
            +toString(data)
          }
        }
        if (showSelfPosition) {
          +"当前位置:"
          if (self != null) {
            +(if (self.rank >= 0) {
              "${self.rank + 1}. ${toString(self)}"
            } else {
              "无记录,请先至少进行一次摸头"
            })
          } else {
            +"无记录,请先至少进行一次摸头"
          }
        }
        at()
      } + quickKb
    }.also {
      sendMessage(it)
    }
  }

  companion object {
    private val quickKb by lazy {
      tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
        row {
          subButton("看看我的", "排行榜 好感度", enter = true)
          subButton("看看子榜", "排行榜 好感度 日奈")
        }
        row {
          subButton("看看我的学生", "排行榜 好感度 我的")
        }
      }
    }
  }
}
