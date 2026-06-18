package com.diyigemt.arona.rollpig.service

import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.message.TencentImage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 预生成卡片的上传产物缓存(思路同 plana 的 ImageAssetService)。
 *
 * 腾讯上传接口返回的 [TencentImage] 自带 ttl(秒): 在「过期前半小时」内复用, 否则重传。
 * 缓存键 `botId:subjectId:pigId` —— 上传产物与 bot、会话绑定, 不跨 bot/会话复用, 但同会话
 * 不同用户抽到同一只猪可共享。每个键独立 [Mutex] 防缓存失效瞬间并发重复上传。
 *
 * 与 plana 的差异: 这里键空间可随群数量增长(bot × 会话 × 猪数), 故加 [MAX_ENTRIES] 上限做
 * 容量保护, 避免长期运行无界膨胀。
 */
internal object CardImageService {
  private const val SAFE_WINDOW_MILLIS = 30L * 60 * 1000 // 过期前半小时
  private const val MAX_ENTRIES = 4096

  private class CacheEntry(val image: TencentImage, val uploadedAtMillis: Long, val ttlMillis: Long) {
    fun reusable(now: Long): Boolean {
      if (ttlMillis <= SAFE_WINDOW_MILLIS) return false
      return now < uploadedAtMillis + ttlMillis - SAFE_WINDOW_MILLIS
    }
  }

  private val cache = ConcurrentHashMap<String, CacheEntry>()
  private val locks = ConcurrentHashMap<String, Mutex>()

  suspend fun getImage(subject: Contact, pigId: String, bytes: ByteArray): TencentImage {
    val key = "${subject.bot.id}:${subject.id}:$pigId"
    val now = System.currentTimeMillis()
    cache[key]?.takeIf { it.reusable(now) }?.let { return it.image }

    val lock = locks.computeIfAbsent(key) { Mutex() }
    try {
      return lock.withLock {
        cache[key]?.takeIf { it.reusable(System.currentTimeMillis()) }?.let { return@withLock it.image }

        val uploaded = subject.uploadImage(bytes)
        if (cache.size >= MAX_ENTRIES) evictStale()
        cache[key] = CacheEntry(uploaded, System.currentTimeMillis(), uploaded.ttl * 1000L)
        uploaded
      }
    } finally {
      // 上传完成(无论成败)即回收本 key 的锁, 避免上传持续失败时 locks 无界增长;
      // 缓存命中走顶部快路径不依赖锁常驻。极端并发下等待者与回收之间可能再建一把锁,
      // 至多导致同一张图额外上传一次(key 含 pigId, 内容一致), 不影响正确性。
      locks.remove(key, lock)
    }
  }

  /** 容量保护: 先清掉已不可复用的条目; 仍超限则整表清空(下次按需重新上传)。 */
  private fun evictStale() {
    val now = System.currentTimeMillis()
    cache.entries.removeIf { !it.value.reusable(now) }
    if (cache.size >= MAX_ENTRIES) cache.clear()
  }
}
