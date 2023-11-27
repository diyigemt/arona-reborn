package com.diyigemt.arona.command

import com.diyigemt.arona.command.CommandManager.register

object BuiltInCommands {

  internal fun registerAll() {
    BuiltInCommands::class.nestedClasses.forEach {
      (it.objectInstance as? Command)?.register()
    }
  }

  object PermissionCommand : AbstractCommand(
    ConsoleCommandOwner,
    ""
  )

}
