package com.diyigemt.arona.plana.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.plana.PluginMain
import com.diyigemt.arona.plana.service.ImageAssetService

/**
 * 发送 plana.jpg。图片经上传接口缓存, 过期前半小时内复用, 之后重传。
 */
@Suppress("unused")
class PlanaStepCommand : AbstractCommand(
  PluginMain,
  "普拉娜踩我",
  description = "发送普拉娜图片",
  help = "/普拉娜踩我"
) {
  suspend fun UserCommandSender.planaStep() {
    val image = ImageAssetService.getImage(subject, "plana.jpg")
    sendMessage(image)
  }
}
