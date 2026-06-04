package com.diyigemt.arona.arona

import com.diyigemt.arona.communication.TencentApiErrorException
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Arona : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION
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
  // 已发送过"错误发生"通知的 sourceId 哨兵: 防止错误通知本身再次失败时陷入重发死循环.
  // sourceId 是腾讯每条入站消息的唯一 id, 旧实现用无界 List 记录, 移除仅发生在"通知本身又失败"这一
  // 罕见分支; 通知发送成功时条目永久滞留 -> 单调内存泄漏(且 List 的 in/remove 还是 O(n) 扫描).
  // 改用有界 + 写后过期的 Caffeine: TTL 只需覆盖"原始发送失败 -> 发出通知 -> 通知失败回调"的短窗口,
  // 60s 足够且不让成功通知的标记久留; maximumSize 在异常风暴下封顶内存; asMap() 提供线程安全的原子
  // putIfAbsent, 适配并发事件回调(旧 MutableList 在协程回调中并发读写存在竞态).
  private const val ERROR_NOTICE_GUARD_MAX_SIZE = 4096L
  private const val ERROR_NOTICE_GUARD_TTL_SECONDS = 60L
  private val errorNoticeGuard: Cache<String, Boolean> = Caffeine.newBuilder()
    .maximumSize(ERROR_NOTICE_GUARD_MAX_SIZE)
    .expireAfterWrite(ERROR_NOTICE_GUARD_TTL_SECONDS, TimeUnit.SECONDS)
    .build()

  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> {
      when (it) {
        is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> {
          val md = tencentCustomMarkdown {
            +"欢迎连接「シッテムの箱」，老师。"
            +"使用手册：https://doc.arona.diyigemt.com/v2/manual/command"
          }
          val kb = tencentCustomKeyboard {
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
        if (sourceId.isBlank()) {
          // 空的 sourceId 应该是哪里出错了, 既无法定位也无法回复
          return@subscribeAlways
        }
        // 原子"检查并标记": putIfAbsent 返回非 null 表示此前已发过通知, 即这条正是"错误通知本身又失败"
        // 的回调 —— 撤销哨兵并停止, 不再二次发送, 避免错误通知无限重发.
        if (errorNoticeGuard.asMap().putIfAbsent(sourceId, true) != null) {
          errorNoticeGuard.invalidate(sourceId)
          return@subscribeAlways
        }
        MessageChainBuilder(sourceId)
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
