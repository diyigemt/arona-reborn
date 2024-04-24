@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuerySuspended
import com.diyigemt.arona.arona.database.image.ImageCacheSchema.Companion.findImage
import com.diyigemt.arona.arona.database.image.contactType
import com.diyigemt.arona.arona.database.image.update
import com.diyigemt.arona.arona.database.tarot.TarotRecordSchema
import com.diyigemt.arona.arona.database.tarot.TarotSchema
import com.diyigemt.arona.arona.tools.queryTeacherNameFromDB
import com.diyigemt.arona.arona.tools.randomBoolean
import com.diyigemt.arona.arona.tools.randomInt
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BaseConfig
import com.diyigemt.arona.command.BuildInCommandOwner
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrNull
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.currentLocalDateTime
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
enum class TarotCardType(val index: Int) {
  A(1),
  B(2);
}

@Serializable
data class TarotConfig(
  @EncodeDefault
  val fxxkDestiny: Boolean = true, // 是否启用逆天改命
  @EncodeDefault
  val dayOne: Boolean = true, // 每天最多抽一次?
  @EncodeDefault
  val cardType: TarotCardType = TarotCardType.A, // 卡面类型
) : PluginWebuiConfig() {
  override fun check() {}
}

private val kb by lazy {
  tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
    row {
      button("1") {
        render {
          label = "再抽一次"
        }
        action {
          data = "/塔罗牌"
          enter = true
        }
      }
    }
  }
}

@Suppress("unused")
class TarotCommand : AbstractCommand(
  Arona,
  "塔罗牌",
  description = "抽一张ba风格的塔罗牌",
  help = """
    提供两种不同的卡面
    
    可配置一天内结果是否相同
    
    都在webui里修改
  """.trimIndent()
) {
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
    val record = dbQuerySuspended {
      TarotRecordSchema.findById(id)
    }
    if (record != null && record.day == today && tarotConfig.dayOne) {
      val tarot = dbQuerySuspended {
        TarotSchema.findById(record.tarot)
      }!!
      send(tarot, record.positive, userTarotConfig.cardType)
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
          dbQuerySuspended {
            this@run.negativeCount++
            this@run.maxNegativeCount = max(this@run.maxNegativeCount, this.negativeCount)
          }
        }
      }
    }
    val tarot = dbQuerySuspended {
      TarotSchema.findById(tarotIndex)
    }!!
    send(tarot, positive, userTarotConfig.cardType)
    dbQuerySuspended {
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

  private suspend fun UserCommandSender.send(
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
    val teacherName = queryTeacherNameFromDB()
    val from = contactType()
    val url = "https://arona.cdn.diyigemt.com/image$path"

    val mdConfig = readUserPluginConfigOrDefault(BuildInCommandOwner, default = BaseConfig()).markdown
    if (mdConfig.enable) { // TODO remove旧版塔罗牌
      val dayOne = readPluginConfigOrDefault(Arona, default = TarotConfig()).dayOne
      val im = when {
        roll == 2 -> 416 to 640
        type == TarotCardType.A -> {
          416 to 817
        }
        roll == 1 || type == TarotCardType.B -> {
          990 to 1700
        }
        else -> { 416 to 640 }
      }
      val md = tencentCustomMarkdown {
        at()
        + "看看${teacherName}抽到了什么:"
        + "${cardName}(${resName})"
        + res
        image {
          href = url
          w = im.first
          h = im.second
        }
      }
      val m = MessageChainBuilder().append(md)
      if (!dayOne) {
        m.append(kb)
      }
      sendMessage(m.build())
    } else {
      val im = dbQuerySuspended {
        findImage(name, from)
      } ?: subject.uploadImage(url).also {
        dbQuerySuspended { it.update(name, from) }
      }
      val resp = MessageChainBuilder()
        .append("看看${teacherName}抽到了什么:\n${cardName}(${resName})\n${res}")
        .append(im)
        .build().let { ch -> sendMessage(ch) }
      if (resp.isFailed) {
        subject.uploadImage(url).also { image ->
          sendMessage(
            MessageChainBuilder()
              .append("看看${teacherName}抽到了什么:\n${cardName}(${resName})\n${res}")
              .append(im)
              .build()
          )
          dbQuerySuspended { image.update(name, from) }
        }
      }
    }
  }
  companion object {
    private const val TarotCount = 22
  }
}
