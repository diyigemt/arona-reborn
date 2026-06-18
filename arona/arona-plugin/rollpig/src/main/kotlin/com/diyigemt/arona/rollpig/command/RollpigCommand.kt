package com.diyigemt.arona.rollpig.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.rollpig.PluginMain
import com.diyigemt.arona.rollpig.db.DailyPigRepository
import com.diyigemt.arona.rollpig.pool.PigPool
import com.diyigemt.arona.rollpig.service.CardImageService
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「今日小猪」: 为当前用户抽取当天固定的一只猪并发送预生成卡片。
 *
 * 同一 bot 下用户跨群/私聊共享同一结果; 以北京时间为准, 次日 00:00 后重新抽取。
 */
@Suppress("unused")
class RollpigCommand : AbstractCommand(
  PluginMain,
  "今日小猪",
  secondaryNames = arrayOf("本日小猪", "当日小猪"),
  description = "抽取今天属于你的小猪",
  help = "/今日小猪"
) {
  suspend fun UserCommandSender.todayPig() {
    if (PigPool.isEmpty()) {
      sendMessage("小猪们还在睡觉, 请稍后再来~")
      return
    }

    val date = LocalDate.now(SHANGHAI).toString() // yyyy-MM-dd
    val candidate = PigPool.random().id
    val pigId = DailyPigRepository.resolveTodayPig(subject.bot.id, user.id, date, candidate)

    // 防御已落库但卡片被移除的旧猪(外置数据被替换): 读不到就降级提示, 不让异常冒泡。
    val bytes = runCatching { PigPool.cardBytes(pigId) }.getOrElse {
      PluginMain.logger.warn("今日小猪卡片缺失, pigId=$pigId", it)
      sendMessage("今日小猪的卡片走丢了, 请联系管理员补齐资源~")
      return
    }
    sendMessage(CardImageService.getImage(subject, pigId, bytes))
  }

  private companion object {
    val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
  }
}
