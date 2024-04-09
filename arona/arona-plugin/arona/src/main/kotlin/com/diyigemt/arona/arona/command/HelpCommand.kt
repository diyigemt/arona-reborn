package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BaseConfig
import com.diyigemt.arona.command.BuildInCommandOwner
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.message.*

private val md by lazy {
  tencentCustomMarkdown {
    h1("Arona帮助")
    list {
      CommandManager.getRegisteredCommands().sortedBy { it.primaryName.length }.forEach {
        list(it.primaryName) {
          +it.description
        }
      }
    }
    +"在指令后加 -h 参数能查看更详细的用法"
    +"如 /攻略 -h"
    link {
      href = "https://doc.arona.diyigemt.com/v2/manual/command"
      placeholder = "用户手册"
    }
  }
}

private val kb by lazy {
  tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
    row {
      button(1) {
        render {
          label = "攻略"
        }
        action {
          data = "/攻略 -h"
          enter = true
        }
      }
    }
    row {
      button(2) {
        render {
          label = "十连"
        }
        action {
          data = "/十连 -h"
          enter = true
        }
      }
    }
  }
}

@Suppress("unused")
class HelpCommand : AbstractCommand(
  Arona, "帮助", description = "给出文档连接"
) {
  suspend fun UserCommandSender.help() {
    val mdConfig = readUserPluginConfigOrDefault(BuildInCommandOwner, default = BaseConfig()).markdown
    if (mdConfig.enable) {
      MessageChainBuilder().append(md).append(kb).also {
        sendMessage(it.build())
      }
    } else {
      MessageChainBuilder()
        .append("\n")
        .append(
          CommandManager.getRegisteredCommands().sortedBy { it.primaryName.length }.joinToString("\n") {
            "${it.primaryName}: ${it.description}"
          }
        ).append(
          "\n用户手册: \nhttps://doc.arona.diyigemt.com/v2/manual/command"
        ).also {
          sendMessage(it.build())
        }
    }

  }
}
