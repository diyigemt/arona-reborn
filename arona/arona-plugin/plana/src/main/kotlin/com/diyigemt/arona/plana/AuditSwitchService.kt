package com.diyigemt.arona.plana

import com.diyigemt.arona.plana.db.PlanaRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * 群审查开关的内存视图 + 写穿 sqlite.
 *
 * 审查在每条群消息热路径上读取, 故用 [ConcurrentHashMap] 缓存避免逐条查库;
 * 开关变更先落库成功再更新缓存, 保证重启后能 [preload] 出真实状态。
 */
object AuditSwitchService {
  private val cache = ConcurrentHashMap<String, Boolean>()

  /** 启动时一次性把已持久化的开关载入内存. */
  suspend fun preload() {
    val persisted = PlanaRepository.listSwitches()
    cache.clear()
    cache.putAll(persisted)
  }

  /** 群默认关闭审查; 仅显式开启过的群返回 true. */
  fun isEnabled(contactId: String): Boolean = cache[contactId] ?: false

  /** 设置并持久化某群的审查开关; 落库失败则抛出, 缓存不被污染. */
  suspend fun setEnabled(contactId: String, enabled: Boolean) {
    PlanaRepository.setSwitch(contactId, enabled)
    cache[contactId] = enabled
  }
}
