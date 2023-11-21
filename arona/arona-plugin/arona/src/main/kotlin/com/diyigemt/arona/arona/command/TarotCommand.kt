package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.command.TrainerCommand.trainer
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.tarot.Tarot
import com.diyigemt.arona.arona.database.tarot.TarotRecord
import com.diyigemt.arona.arona.tools.queryTeacherNameFromDB
import com.diyigemt.arona.arona.tools.randomBoolean
import com.diyigemt.arona.arona.tools.randomInt
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.utils.currentLocalDateTime

@Suppress("unused")
object TarotCommand : AbstractCommand(
  Arona,
  "塔罗牌",
  description = "抽一张ba风格的塔罗牌, 画师pixiv@Shi0n"
) {
  private const val TarotCount = 22
  suspend fun UserCommandSender.tarot() {
    val id = user.id
    val today = currentLocalDateTime().date.dayOfMonth
    val record = dbQuery {
      TarotRecord.findById(id)
    }
    if (record != null && record.day == today) {
      val tarot = dbQuery {
        Tarot.findById(record.tarot)
      }!!
      send(this, tarot, record.positive)
      return
    }
    val tarotIndex = randomInt(TarotCount) + 1
    val tarot = dbQuery {
      Tarot.findById(tarotIndex)
    }!!
    val positive = randomBoolean()
    send(this, tarot, positive)
    dbQuery {
      if (record != null) {
        record.day = today
        record.tarot = tarotIndex
        record.positive = positive
      } else {
        TarotRecord.new(id) {
          this@new.day = today
          this@new.tarot = tarotIndex
          this@new.positive = positive
        }
      }
    }
  }
  private suspend fun send(commandSender: UserCommandSender, tarot: Tarot, positive: Boolean) {
    val res = if (positive) tarot.positive else tarot.negative
    val resName = if (positive) "正位" else "逆位"
    val fileSuffix = if (positive) "up" else "down"
    val path = "/tarot/${tarot.id.value}-${fileSuffix}.png"
    val teacherName = queryTeacherNameFromDB(commandSender.user.id)
    // TODO 多次回复
    commandSender.sendMessage("看看${teacherName}抽到了什么:\n${tarot.name}(${resName})" +
        "\n${res}\n藤子抽风了，发图发不出去，第二句回复也发不出去，待会再用吧，唉")
//    commandSender.sendMessage("图片发送失败, 这是直链:\nhttps://arona.cdn.diyigemt.com/image$path")
//    MessageChainBuilder()
//      .append(
//        TencentImage(
//          url = "https://arona.cdn.diyigemt.com/image$path"
//        )
//      ).build().also { ch -> commandSender.sendMessage(ch) }
  }
}
