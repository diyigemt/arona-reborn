package com.diyigemt.arona.arona

import com.diyigemt.arona.communication.event.TencentBotUserChangeEvent
import com.diyigemt.arona.communication.event.TencentFriendAddEvent
import com.diyigemt.arona.communication.event.TencentGroupAddEvent
import com.diyigemt.arona.communication.event.TencentGuildAddEvent
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

object Arona : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona",
    name = "arona",
    author = "diyigemt",
    version = "1.0.1",
    description = "hello world"
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

  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> {
      when (it) {
        is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> {
          delay(2000L)
          it.subject.sendMessage("欢迎连接「シッテムの箱」，老师。\n使用手册：https://doc.arona.diyigemt.com/v2/manual/command")
        }

        else -> {
          //TODO 删除聊天事件
        }
      }
    }
  }
}
