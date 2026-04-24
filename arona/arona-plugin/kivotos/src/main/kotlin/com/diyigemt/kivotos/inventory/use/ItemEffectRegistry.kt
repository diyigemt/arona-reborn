package com.diyigemt.kivotos.inventory.use

import java.util.concurrent.ConcurrentHashMap

/**
 * 显式集中注册入口. 不使用类加载时 `init { register(this) }` 的自注册,
 * 因为它的顺序依赖 JVM 加载时机, 容易在不同启动路径下行为漂移.
 *
 * 重复注册同 key 视为编程错误, 直接抛出 — 比静默覆盖线上实现安全得多.
 */
object ItemEffectRegistry {
  private val map = ConcurrentHashMap<String, ItemEffect>()

  fun register(effect: ItemEffect) {
    val prev = map.putIfAbsent(effect.key, effect)
    require(prev == null) { "duplicate item effect key: ${effect.key}" }
  }

  fun get(key: String): ItemEffect? = map[key]

  fun keys(): Set<String> = map.keys.toSet()
}
