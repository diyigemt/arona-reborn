package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuerySuspended
import com.diyigemt.arona.arona.database.image.ImageCacheSchema
import com.diyigemt.arona.arona.database.image.ImageCacheSchema.Companion.findImage
import com.diyigemt.arona.arona.database.image.contactType
import com.diyigemt.arona.arona.database.image.update
import com.diyigemt.arona.arona.database.tarot.TarotRecordSchema
import com.diyigemt.arona.arona.database.tarot.TarotSchema
import com.diyigemt.arona.arona.tools.queryTeacherNameFromDB
import com.diyigemt.arona.arona.tools.randomBoolean
import com.diyigemt.arona.arona.tools.randomInt
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.isGroupOrPrivate
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.TencentGuildImage
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
      TarotRecordSchema.findById(id)
    }
    if (record != null && record.day == today) {
      val tarot = dbQuery {
        TarotSchema.findById(record.tarot)
      }!!
      send(this, tarot, record.positive)
      return
    }
    val tarotIndex = randomInt(TarotCount) + 1
    val tarot = dbQuery {
      TarotSchema.findById(tarotIndex)
    }!!
    val positive = randomBoolean()
    send(this, tarot, positive)
    dbQuery {
      if (record != null) {
        record.day = today
        record.tarot = tarotIndex
        record.positive = positive
      } else {
        TarotRecordSchema.new(id) {
          this@new.day = today
          this@new.tarot = tarotIndex
          this@new.positive = positive
        }
      }
    }
  }

  private suspend fun send(commandSender: UserCommandSender, tarot: TarotSchema, positive: Boolean) {
    val from = commandSender.contactType()
    val res = if (positive) tarot.positive else tarot.negative
    val resName = if (positive) "正位" else "逆位"
    val fileSuffix = if (positive) "up" else "down"
    val name = "${tarot.id.value}-${fileSuffix}"
    val path = "/tarot/$name.png"
    val teacherName = queryTeacherNameFromDB(commandSender.user.id)
    if (commandSender.isGroupOrPrivate()) {
      val im = dbQuery {
        findImage(name, from)
      } ?: commandSender.subject.uploadImage("https://arona.cdn.diyigemt.com/image$path").also {
        dbQuery { it.update(name, from) }
      }
      MessageChainBuilder()
        .append("看看${teacherName}抽到了什么:\n${tarot.name}(${resName})\n${res}")
        .append(im)
        .build().also { ch -> commandSender.sendMessage(ch) }
    } else {
      MessageChainBuilder()
        .append("看看${teacherName}抽到了什么:\n${tarot.name}(${resName})\n${res}")
        .append(
          TencentGuildImage(
            url = "https://arona.cdn.diyigemt.com/image$path"
          )
        ).build().also { ch -> commandSender.sendMessage(ch) }
    }
  }
}
