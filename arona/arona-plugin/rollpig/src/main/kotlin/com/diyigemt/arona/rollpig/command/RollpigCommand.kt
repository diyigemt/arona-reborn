package com.diyigemt.arona.rollpig.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.rollpig.PluginMain
import com.diyigemt.arona.rollpig.db.DailyPigRepository
import com.diyigemt.arona.rollpig.pool.PigPool
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「今日小猪」: 为当前用户抽取当天固定的一只猪并发送预生成卡片。
 *
 * 同一 bot 下用户跨群/私聊共享同一结果; 以北京时间为准, 次日 00:00 后重新抽取。
 *
 * 卡片以 Markdown 引用 CDN 图发送(见 [sendPigCard]), 资源由 `tools/rollpig-generator` 生成后
 * 上传到 COS; 运行期不再上传图片字节。
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
      sendMessage(EMPTY_POOL_TIP)
      return
    }

    val date = LocalDate.now(SHANGHAI).toString() // yyyy-MM-dd
    val candidate = PigPool.random().id
    val pigId = DailyPigRepository.resolveTodayPig(subject.bot.id, user.id, date, candidate)

    // 防御已落库但卡片被移除的旧猪(外置数据被替换): 本地卡片即 CDN 同步源, 缺失则降级提示,
    // 不发出大概率 404 的 CDN 图。
    if (!PigPool.hasCard(pigId)) {
      PluginMain.logger.warn("今日小猪卡片缺失, pigId=$pigId")
      sendMessage("今日小猪的卡片走丢了, 请联系管理员补齐资源~")
      return
    }
    sendPigCard(pigId, "今日小猪")
  }

  private companion object {
    val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
  }
}
