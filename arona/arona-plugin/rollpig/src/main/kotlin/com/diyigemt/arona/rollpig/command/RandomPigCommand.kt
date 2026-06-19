package com.diyigemt.arona.rollpig.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.rollpig.PluginMain
import com.diyigemt.arona.rollpig.service.PigHubGallery

/**
 * 「随机小猪」: 每次从 pighub.top 图片列表随机取一张原图发送(单张)。
 *
 * 与「今日小猪」不同, 不按北京时间锁定、不落库、不走本地猪池/自有 CDN, 图片直接引用 pighub 原图 URL。
 */
@Suppress("unused")
class RandomPigCommand : AbstractCommand(
  PluginMain,
  "随机小猪",
  description = "从 pighub 随机抽取一只小猪",
  help = "/随机小猪"
) {
  suspend fun UserCommandSender.randomPig() {
    val image = PigHubGallery.randomImage()
    if (image == null) {
      sendMessage("随机小猪暂时跑丢了, 请稍后再试~")
      return
    }
    sendMarkdownCard("随机小猪", image.url, PIGHUB_IMG_SIZE, PIGHUB_IMG_SIZE, title = image.title)
  }
}
