package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.tools.queryTeacherNameFromDB
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.diyigemt.arona.webui.event.isBlock
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

@Suppress("unused")
class CallMeCommand : AbstractCommand(
  Arona,
  "叫我",
  description = "记录用于称呼的名字",
  help = """
    /叫我, 回复当前昵称
    
    /叫我 <期望的称呼>, 记录昵称
  """.trimIndent()
) {
  private val expect by argument(name = "期望的称呼", help = "期望的称呼").optional()
  suspend fun UserCommandSender.callMe() {
    if (expect.isNullOrBlank()) {
      queryTeacherNameFromDB().also {
        sendMessage("怎么了, $it")
      }
      return
    }
    var name = expect as String
    if (name.length > 15) {
      sendMessage("名称不能超过15个字符")
      return
    }
    val ev = ContentAuditEvent(name, level = 80).broadcast()
    if (ev.isBlock) {
      sendMessage("违禁词: ${ev.message}")
      return
    }
    if (!name.endsWith("老师")) name = "${name}老师"
    userDocument().updateUsername(name)
    sendMessage("好的, $name")
  }
}
