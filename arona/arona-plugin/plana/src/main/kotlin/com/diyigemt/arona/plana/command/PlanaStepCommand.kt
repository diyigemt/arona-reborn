package com.diyigemt.arona.plana.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.GroupCommandSender
import com.diyigemt.arona.communication.message.image
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
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
  suspend fun GroupCommandSender.planaStep() {
    sendMessage(
      tencentCustomMarkdown { 
        image { 
          w = 1013
          h = 1847
          href = "https://arona.cdn.diyigemt.com/image/some/plana.png"
        }
      }
    )
    val image = ImageAssetService.getImage(subject, "plana.jpg")
    sendMessage(image)
  }
}
