package com.diyigemt.arona.arona

import com.diyigemt.arona.communication.TencentApiErrorException
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.nio.file.Path

object Arona : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona",
    name = "arona",
    author = "diyigemt",
    version = "1.3.17",
    description = "arona plugin"
  )
) {
  private val json = Json {
    ignoreUnknownKeys = true
  }
  val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json
    }
  }
  private val errorList = mutableListOf<String>()

  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> {
      when (it) {
        is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> {
          val md = tencentCustomMarkdown {
            +"欢迎连接「シッテムの箱」，老师。"
            +"使用手册：https://doc.arona.diyigemt.com/v2/manual/command"
          }
          val kb = tencentCustomKeyboard(it.bot.unionOpenidOrId) {
            row {
              button(1) {
                render {
                  label = "菜单"
                }
                action {
                  data = "/菜单"
                  enter = true
                }
              }
              button(2) {
                render {
                  label = "帮助"
                }
                action {
                  data = "/帮助"
                  enter = true
                }
              }
            }
          }
          delay(2000L)
          it.subject.sendMessage(kb + md)
        }

        else -> {
          //TODO 删除聊天事件
        }
      }
    }
    // 全局异常处理
    pluginEventChannel().subscribeAlways<MessagePostSendEvent<*>> {
      if (it.isFailure && it.isTencentError) {
        val sourceId = it.message.sourceId
        if (sourceId in errorList) {
          // 在发送错误消息时出错, 不再发送
          errorList.remove(sourceId)
          return@subscribeAlways
        }
        if (sourceId.isBlank()) {
          // 空的sourceId 应该是哪里出错了
          return@subscribeAlways
        }
        errorList.add(sourceId)
        MessageChainBuilder(it.message.sourceId)
          .append("错误发生")
          .append("message: ${(it.exception as TencentApiErrorException).source.message}")
          .build()
          .also { ch ->
            it.target.sendMessage(ch, (100 .. 200).random())
          }
      }
    }
  }

  fun dataFolder(vararg paths: String): Path {
    var path = dataFolderPath
    paths.forEach {
      path = path.resolve(it)
    }
    return path
  }
}
