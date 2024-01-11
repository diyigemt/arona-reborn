package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
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
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrDefault
import com.diyigemt.arona.communication.command.isGroupOrPrivate
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.MessageReceipt
import com.diyigemt.arona.communication.message.TencentGuildImage
import com.diyigemt.arona.utils.currentLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class TarotConfig(
  val fxxkDestiny: Boolean = false, // 是否启用逆天改命
  val dayOne: Boolean = true // 每天最多抽一次?
)
@Suppress("unused")
object TarotCommand : AbstractCommand(
  Arona,
  "塔罗牌",
  description = "抽一张ba风格的塔罗牌, 画师pixiv@Shi0n"
) {
  private const val TarotCount = 22
  private val PositiveMap = mapOf(
    0 to true,
    1 to true,
    2 to true,
    3 to true,
    4 to true,
    5 to true,
    6 to true,
    7 to true,
    8 to true,
    9 to true,
    10 to true,
    11 to true,
    13 to false,
    14 to true,
    15 to false,
    17 to true,
    18 to false,
    19 to true,
    20 to true,
    21 to true,
  )
  suspend fun UserCommandSender.tarot() {
    val tarotConfig = readPluginConfigOrDefault(Arona, default = TarotConfig())
    val id = userDocument().id
    val today = currentLocalDateTime().date.dayOfMonth
    val record = dbQuery {
      TarotRecordSchema.findById(id)
    }
    if (record != null && record.day == today && tarotConfig.dayOne) {
      val tarot = dbQuery {
        TarotSchema.findById(record.tarot)
      }!!
      send(this, tarot, record.positive)
      return
    }
    var tarotIndex = randomInt(TarotCount) + 1
    var positive = randomBoolean()
    // 启用保底
    if (tarotConfig.fxxkDestiny && (tarotIndex - 1 !in PositiveMap || PositiveMap[tarotIndex - 1] != positive)) {
      val rate = (record?.negativeCount ?: 0) + 1
      // 改命
      if (randomInt(101) >= 50 + 5 * rate) {
        val t = PositiveMap.keys.toList()[randomInt(PositiveMap.size)]
        tarotIndex = t
        positive = PositiveMap[t] ?: true
      } else {
        record?.run {
          dbQuery {
            this@run.negativeCount++
            this@run.maxNegativeCount = max(this@run.maxNegativeCount, this.negativeCount)
          }
        }
      }
    }
    val tarot = dbQuery {
      TarotSchema.findById(tarotIndex)
    }!!
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
      val mayFail = MessageChainBuilder()
        .append("看看${teacherName}抽到了什么:\n${tarot.name}(${resName})\n${res}")
        .append(im)
        .build().let { ch -> commandSender.sendMessage(ch) }
      if (mayFail == MessageReceipt.ErrorMessageReceipt) {
        commandSender.subject.uploadImage("https://arona.cdn.diyigemt.com/image$path").also { image ->
          commandSender.sendMessage(
            MessageChainBuilder()
              .append("看看${teacherName}抽到了什么:\n${tarot.name}(${resName})\n${res}")
              .append(im)
              .build()
          )
          dbQuery { image.update(name, from) }
        }
      }
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
