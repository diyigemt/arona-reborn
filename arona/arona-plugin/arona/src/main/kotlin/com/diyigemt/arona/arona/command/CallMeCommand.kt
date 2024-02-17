package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.name.TeacherNameSchema
import com.diyigemt.arona.arona.tools.queryTeacherNameFromDB
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.diyigemt.arona.webui.event.isBlock
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

@Suppress("unused")
object CallMeCommand : AbstractCommand(
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
      queryTeacherNameFromDB(user.id).also {
        sendMessage("怎么了, $it")
      }
      return
    }
    var name = expect as String
    val ev = ContentAuditEvent(name).broadcast()
    if (ev.isBlock) {
      sendMessage("违禁词: ${ev.message}")
      return
    }
    if (name.length > 20) {
      sendMessage("名称不能超过20个字符")
      return
    }
    if (!name.endsWith("老师")) name = "${name}老师"
    updateTeacherName(user.id, name)
    sendMessage("好的, $name")
  }

  private fun updateTeacherName(id: String, name: String) {
    dbQuery {
      val record = TeacherNameSchema.findById(id)
      if (record == null) {
        TeacherNameSchema.new(id) {
          this@new.name = name
        }
      } else {
        record.name = name
      }
    }
  }
}
