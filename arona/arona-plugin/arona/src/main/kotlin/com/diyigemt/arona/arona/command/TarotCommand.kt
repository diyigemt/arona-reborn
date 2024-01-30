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
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrNull
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.MessageReceipt
import com.diyigemt.arona.utils.currentLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
enum class TarotCardType(val index: Int) {
  A(1),
  B(2);
}

@Serializable
data class TarotConfig(
  val fxxkDestiny: Boolean = false, // 是否启用逆天改命
  val dayOne: Boolean = true, // 每天最多抽一次?
  val cardType: TarotCardType = TarotCardType.A, // 卡面类型
)

@Suppress("unused")
object TarotCommand : AbstractCommand(
  Arona,
  "塔罗牌",
  description = "抽一张ba风格的塔罗牌, 画师pixiv@Shi0n"
) {
  private const val TarotCount = 22
  private val PositiveMap = mapOf(
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
    12 to true,
    14 to false,
    15 to true,
    16 to false,
    18 to true,
    19 to false,
    20 to true,
    21 to true,
    22 to true,
  )

  suspend fun UserCommandSender.tarot() {
    val tarotConfig = readPluginConfigOrDefault(Arona, default = TarotConfig())
    val userTarotConfig =
      readUserPluginConfigOrNull(Arona) ?: contactDocument().readPluginConfigOrDefault(Arona, default = TarotConfig())
    val id = userDocument().id
    val today = currentLocalDateTime().date.dayOfMonth
    val record = dbQuery {
      TarotRecordSchema.findById(id)
    }
    if (record != null && record.day == today && tarotConfig.dayOne) {
      val tarot = dbQuery {
        TarotSchema.findById(record.tarot)
      }!!
      send(this, tarot, record.positive, userTarotConfig.cardType)
      return
    }
    var tarotIndex = randomInt(TarotCount) + 1
    var positive = randomBoolean()
    // 启用保底
    if (tarotConfig.fxxkDestiny && (tarotIndex - 1 !in PositiveMap || PositiveMap[tarotIndex - 1] != positive)) {
      val rate = (record?.negativeCount ?: 0) + 1
      // 改命
      if (randomInt(101) >= 55 - 5 * rate) {
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
    send(this, tarot, positive, userTarotConfig.cardType)
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

  private suspend fun send(
    commandSender: UserCommandSender,
    tarot: TarotSchema,
    positive: Boolean,
    type: TarotCardType,
  ) {
    var res = if (positive) tarot.positive else tarot.negative
    var resName = if (positive) "正位" else "逆位"
    val fileSuffix = if (positive) "up" else "down"
    var name = "${tarot.id.value}-${fileSuffix}" + if (type == TarotCardType.B) "-2" else ""
    var cardName = tarot.name
    // 随机数
    val roll = randomInt(100)
    if (roll == 1) {
      resName = "正位"
      name = "23"
      res = "꒰ঌ(\uD83C\uDF80 ᗜ`˰´ᗜ \uD83C\uDF38)໒꒱\n"
      cardName = "Azusa"
    } else if (roll == 2) {
      resName = "正位"
      name = "0"
      res = "草莓牛奶！"
      cardName = "Arona"
    }
    val path = "/tarot/$name.png"
    val teacherName = queryTeacherNameFromDB(commandSender.user.id)
    val from = commandSender.contactType()
    val url = "https://arona.cdn.diyigemt.com/image$path"
    val im = dbQuery {
      findImage(name, from)
    } ?: commandSender.subject.uploadImage(url).also {
      dbQuery { it.update(name, from) }
    }
    val mayFail = MessageChainBuilder()
      .append("看看${teacherName}抽到了什么:\n${cardName}(${resName})\n${res}")
      .append(im)
      .build().let { ch -> commandSender.sendMessage(ch) }
    if (mayFail == MessageReceipt.ErrorMessageReceipt) {
      commandSender.subject.uploadImage(url).also { image ->
        commandSender.sendMessage(
          MessageChainBuilder()
            .append("看看${teacherName}抽到了什么:\n${cardName}(${resName})\n${res}")
            .append(im)
            .build()
        )
        dbQuery { image.update(name, from) }
      }
    }
  }
}
