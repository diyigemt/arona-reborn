package com.diyigemt.kivotos.schedule

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
object ScheduleCommand : AbstractCommand(
  Kivotos,
  "课程表",
  description = "课程表系列指令",
) {
  suspend fun UserCommandSender.schedule() {
    sendMessage("还没做")
  }
}
