package com.diyigemt.kivotos.rank

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.student.StudentSchema
import net.coobird.thumbnailator.Thumbnails;
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BaseConfig
import com.diyigemt.arona.command.BuildInCommandOwner
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.message.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.subButton
import com.diyigemt.kivotos.tools.normalizeStudentName
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.diyigemt.arona.database.permission.SimplifiedUserDocument
import com.diyigemt.arona.utils.runSuspend
import com.diyigemt.arona.utils.uuid
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream

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

  /**
   * 排行榜拓展: 生成榜单可视化
   *
   * 基本逻辑偷自GachaCommand
   * @param title 榜单标题
   * @param ranks 排行榜
   * @param self 我的排名
   * @param showSelfPosition 是否【我的】
   */
  private suspend fun UserCommandSender.sendImageRank(
    title: String,
    ranks: List<FavorRankData>,
    self: FavorRankData? = null,
    showSelfPosition: Boolean = true,
  ){
    // copy by @UserCommandSender.sendRank
    val ids = (ranks.map { it.uid } + listOf(self?.uid ?: userDocument().id))
    // 根据uid反查botId
    val usernames = SimplifiedUserDocument.querySimplifiedUser(ids).let {
      it.values.associateBy {
        ids.first { s -> s == it.id }
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
    val randomFileName = "${uuid()}.jpg"
    val randomFile = Arona.dataFolder("gacha_result", randomFileName).toFile()
    FavorRankTool.generateFavorRankImage(
      ranks,
      self,
      showSelfPosition,
      usernames,
      studentNames
    ).also {
      it.makeImageSnapshot().encodeToData(format = EncodedImageFormat.PNG)?.bytes?.also { arr ->
        ByteArrayInputStream(arr).use { input ->
          Thumbnails
            .of(input)
            .scale(1.0)
            .outputQuality(0.6)
            .outputFormat("jpg")
            .toFile(randomFile)
        }
      }
    }
    val mdConfig = readUserPluginConfigOrDefault(BuildInCommandOwner, default = BaseConfig()).markdown
    if (mdConfig.enable) {
      val md = tencentCustomMarkdown {
        h1(title)
        image {
          href = "https://arona.diyigemt.com/image/gacha_result/$randomFileName"
          w = 1600
          h = 1200
        }
        at()
      }
      val kb = tencentCustomKeyboard {
        row {
          subButton("看看我的", "排行榜 好感度", enter = true)
        }
      }
      MessageChainBuilder().append(md).append(kb).also { sendMessage(it.build()) }
    } else {
      subject.uploadImage("https://arona.diyigemt.com/image/gacha_result/$randomFileName").also {
        sendMessage(it)
      }
    }
    runSuspend {
      delay(30000)
      randomFile.delete()
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
    val usernames = SimplifiedUserDocument.querySimplifiedUser(ids).let {
      it.values.associateBy {
        ids.first { s -> s == it.id }
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
      "\t" + usernames[data.uid]?.username
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
