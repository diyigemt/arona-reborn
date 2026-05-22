package com.diyigemt.utils

import com.diyigemt.arona.communication.message.TencentCustomKeyboard
import com.diyigemt.arona.communication.message.TencentGroupMessage
import com.diyigemt.arona.communication.message.TencentMessageBuilder
import com.diyigemt.arona.communication.message.button
import com.diyigemt.arona.communication.message.row
import com.diyigemt.arona.communication.message.tencentCustomKeyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * 钉住本轮改造的核心不变量: 顶层 lazy 模板的 `bot_appid` 在发送时按调用 bot 临时补齐,
 * 但**绝不回填到原 keyboard 对象** —— 这是同一份模板被多个 bot 安全复用的前提.
 *
 * 旧实现 `tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) { ... }` 直接把全局默认 bot 的
 * appId 早绑定到模板, 第二个 bot 发送时只能用别人的 appId, 这里的测试覆盖正是替换后的行为.
 */
class KeyboardSendTimeResolveTest {
  @Test
  fun `build resolves botAppId without mutating original keyboard`() {
    val template = tencentCustomKeyboard {
      row { button("hello") }
    }
    assertNull(template.content.botAppid, "模板构造期不应绑定 botAppid")

    val builder = TencentMessageBuilder().append(template)

    val sentForBotA = builder.build(isPrivateChannel = false, botAppId = "bot-A") as TencentGroupMessage
    val keyboardForBotA = sentForBotA.keyboard as TencentCustomKeyboard
    assertEquals("bot-A", keyboardForBotA.content.botAppid, "wire 副本应携带发送 bot 的 appId")
    assertNull(template.content.botAppid, "发送解析不得回填原模板")
    assertNotSame(template, keyboardForBotA, "应产生独立 wire 副本而不是返回原对象")

    val sentForBotB = builder.build(isPrivateChannel = false, botAppId = "bot-B") as TencentGroupMessage
    val keyboardForBotB = sentForBotB.keyboard as TencentCustomKeyboard
    assertEquals("bot-B", keyboardForBotB.content.botAppid, "同模板换 bot 后应得到新的 appId")
    assertNull(template.content.botAppid, "二次发送仍不得回填原模板")
  }

  @Test
  fun `build keeps explicit botAppId when keyboard already set one`() {
    val explicit = tencentCustomKeyboard(botAppId = "explicit-app") {
      row { button("x") }
    }

    val builder = TencentMessageBuilder().append(explicit)
    val sent = builder.build(isPrivateChannel = false, botAppId = "send-bot") as TencentGroupMessage
    val keyboard = sent.keyboard as TencentCustomKeyboard

    // 显式入口的 appId 是调用方对 wire payload 的承诺, 发送路径不应静默覆盖.
    assertEquals("explicit-app", keyboard.content.botAppid)
  }
}
