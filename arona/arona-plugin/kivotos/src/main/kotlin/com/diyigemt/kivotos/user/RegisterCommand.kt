package com.diyigemt.kivotos.user

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
object RegisterCommand : AbstractCommand(
  Kivotos,
  "注册",
  description = "注册账号"
) {
  suspend fun UserCommandSender.register() {
    sendMessage("注册成功")
  }
}
