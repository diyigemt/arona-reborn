package com.diyigemt.arona.rollpig.command

import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*

// 卡片 CDN 基址, 与塔罗牌同源(/image/<namespace>/); 资源由 tools/rollpig-generator 生成后上传至此。
internal const val CARD_CDN_BASE = "https://arona.cdn.diyigemt.com/image/rollpig"

// 预生成卡片固定 800x800, 作为 Markdown 图片的显示尺寸提示。
internal const val CARD_SIZE = 800

// pighub 原图尺寸不一(方/长/横), 但带按钮必须走 markdown、markdown 图又必须写死宽高;
// 故随机小猪统一用此方形尺寸(已与用户确认接受由此带来的轻微变形)。
internal const val PIGHUB_IMG_SIZE = 300

// 猪池为空(资源未就绪)时的统一降级提示。
internal const val EMPTY_POOL_TIP = "小猪们还在睡觉, 请稍后再来~"

/**
 * 「今日小猪」「随机小猪」共用的按钮: 同一行两枚, 均为指令按钮且点击即发送(enter)。
 *
 * 内容静态且不绑定具体 bot, 故顶层 `lazy` 复用一份; 发送期由 core 按实际 bot 补齐 `bot_appid`
 * (见 [tencentCustomKeyboard])。权限默认 ANY_ONE: 谁点都只会触发自己的命令上下文, 无需 selfOnly。
 */
internal val pigKeyboard by lazy {
  tencentCustomKeyboard {
    row {
      button("1") {
        render {
          label = "我是猪"
        }
        action {
          data = "/今日小猪"
          enter = true
        }
      }
      button("2") {
        render {
          label = "随机小猪"
        }
        action {
          data = "/随机小猪"
          enter = true
        }
      }
    }
  }
}

/**
 * 以 Markdown 引用一张图片发送卡片, 并附 [pigKeyboard] 双按钮。
 *
 * [header] 为图片上方的说明文字(如「今日小猪」「随机小猪」), 同时保证 Markdown 内容非空;
 * [imageUrl] 为完整图片 URL; [width]/[height] 为 Markdown 图片的显示尺寸提示(需调用方按图源给出)。
 * `at()` 是 `context(sender: UserCommandSender)` DSL, 依赖本函数的 `UserCommandSender` receiver
 * 及模块的 `-Xcontext-parameters` 编译选项。
 */
internal suspend fun UserCommandSender.sendMarkdownCard(
  header: String,
  imageUrl: String,
  width: Int,
  height: Int,
) {
  val card = tencentCustomMarkdown {
    at()
    + header
    image {
      href = imageUrl
      w = width
      h = height
    }
  }
  sendMessage(MessageChainBuilder().append(card).append(pigKeyboard).build())
}

/**
 * 发送本地预生成猪卡片(自有 CDN, 固定 800x800)。调用方需自行保证 [pigId] 对应卡片存在。
 */
internal suspend fun UserCommandSender.sendPigCard(pigId: String, header: String) =
  sendMarkdownCard(header, "$CARD_CDN_BASE/$pigId.png", CARD_SIZE, CARD_SIZE)
