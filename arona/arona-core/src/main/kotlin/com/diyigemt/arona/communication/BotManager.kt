package com.diyigemt.arona.communication

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

// Sprint 3.3: 仅升级存储并发安全 + 默认 bot 引用稳定化, 不在本轮引入真正的多 bot 路由.
//  - 旧 mutableListOf + linear scan 不是线程安全的, 注册路径在 SupervisorJob 异步分派下会撞车.
//  - 改 ConcurrentHashMap, key 沿用 bot.id (即 config.id, 不是 appId; appId 暴露在 unionOpenid).
//  - 无参 getBot() 不能用 values.firstOrNull(), 因为 CHM 的迭代顺序对调用方不稳定. 单独追踪
//    第一个被注册的 bot id, 多 bot 场景下行为可重现.
//  - WebhookEndpoint / CommandMain / BuiltInCommands / Event.kt 仍然走 getBot() 拿默认 bot;
//    多租户路由 (按 appId/path 分发) 是另一个 sprint.
object BotManager {
  private val bots = ConcurrentHashMap<String, TencentBot>()
  private val firstBotId = AtomicReference<String?>(null)

  fun registerBot(bot: TencentBot) {
    if (bots.putIfAbsent(bot.id, bot) == null) {
      firstBotId.compareAndSet(null, bot.id)
    }
  }

  fun getBot(id: String): TencentBot =
    bots[id] ?: throw NoSuchElementException("Bot $id not found.")

  fun getBot(): TencentBot {
    val defaultId = firstBotId.get() ?: throw NoSuchElementException("No bot registered.")
    return bots[defaultId] ?: throw IllegalStateException("Default bot $defaultId no longer registered.")
  }

  // close 不再只关 bot 实例, 还顺手清 registry. 旧实现关完不清, 调用方 close 后再 registerBot 同 id 会
  // 因 putIfAbsent 命中残留 entry 而无声丢失. 测试场景下也省一次反射清状态.
  fun close() {
    bots.values.forEach {
      it.close()
    }
    bots.clear()
    firstBotId.set(null)
  }
}
