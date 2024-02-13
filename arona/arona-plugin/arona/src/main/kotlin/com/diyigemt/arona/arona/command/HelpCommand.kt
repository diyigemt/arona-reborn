package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender

@Suppress("unused")
object HelpCommand : AbstractCommand(
  Arona, "帮助", description = "给出文档连接"
) {
  suspend fun UserCommandSender.help() {
    sendMessage("用户手册: \nhttps://doc.arona.diyigemt.com/v2/manual/command")
  }
}