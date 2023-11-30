package com.diyigemt.arona.command

import com.diyigemt.arona.command.CommandManager.register
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.github.ajalt.clikt.parameters.arguments.argument

object BuiltInCommands {

  internal fun registerAll() {
    BuiltInCommands::class.nestedClasses.forEach {
      (it.objectInstance as? Command)?.register()
    }
  }

  object LoginCommand : AbstractCommand(
    ConsoleCommandOwner,
    "登录",
    help = "登录webui"
  ) {
    private val password by argument("登录凭证")
    suspend fun UserCommandSender.login() {
      when (val token = redisDbQuery {
        get(password)
      }) {
        is String -> {
          redisDbQuery {
            with(pipelined()) {
              del(password)
              set(token, user.id)
              expire(token, 3600u)
              execute()
            }
          }
          sendMessage("认证成功")
        }
        else -> {
          sendMessage("token无效")
        }
      }
    }
  }

}
