package com.diyigemt.arona.chatbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * 表情文件落在插件数据目录 `data/com.diyigemt.arona.chatbot/sticker/<sha256>.<ext>`, 随机器走 (迁移/备份要带上这个目录).
 * 出站: 读字节 → [com.diyigemt.arona.communication.contact.Contact.uploadImage]. 运营页: 运维用 nginx 把**只这一个子目录** (autoindex off)
 * 映射到 [ChatbotSecrets.stickerPublicBaseUrl], 后端只拼 URL. URL 是永久公开链接 (文件名是内容哈希, 猜不到但可转发; hidden/rejected 的图知道链接也能看),
 * 已接受这个边界; 要过期签名再上 nginx secure_link.
 */
internal object StickerFiles {
  private val dir: Path by lazy { PluginMain.resolveDataPath("sticker").toAbsolutePath().normalize().also { Files.createDirectories(it) } }
  private val POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
  private val WORLD_READABLE = PosixFilePermissions.fromString("rw-r--r--")

  /** 文件名只认 `<sha256 十六进制>.<小写扩展名>`: 它会被拼进公开 URL 和本地路径, 两头都不给任何别的形状机会. */
  private val NAME = Regex("[0-9a-f]{64}\\.[a-z0-9]{2,5}")

  fun nameFor(image: DownloadedImage) = "${image.sha256}.${image.extension}"

  /** 前缀没配或文件名不合形状 → null (运营页显示"无图"), 不拼出奇怪的 URL. */
  fun publicUrl(name: String): String? {
    if (!NAME.matches(name)) return null
    return ChatbotSecrets.stickerPublicBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/$name" }
  }

  /**
   * 先写 `<name>.part` 再改名: 进程中途挂掉不会留下半张图被当成表情发出去. 文件系统不支持原子改名 (某些 overlay/NFS) 就退化成普通覆盖.
   * 不用 `Files.createTempFile`: 它在 Linux 上固定建 600 (不看 umask), 改名后保留, nginx 读不了. 同名并发由 claim() 的哈希抢占挡掉.
   * 权限显式设成 644 (POSIX 上), 不依赖运行用户的 umask —— 目录由 nginx 直接读, 这是部署边界不是实现细节.
   */
  suspend fun put(name: String, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
      val target = resolve(name)
      val tmp = dir.resolve("$name.part")
      try {
        Files.write(tmp, bytes)
        if (POSIX) Files.setPosixFilePermissions(tmp, WORLD_READABLE)
        try {
          Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        Files.deleteIfExists(tmp)
      }
    }
  }

  suspend fun get(name: String): ByteArray = withContext(Dispatchers.IO) { Files.readAllBytes(resolve(name)) }

  suspend fun delete(name: String) {
    withContext(Dispatchers.IO) { Files.deleteIfExists(resolve(name)) }
  }

  /** 文件名是从 Mongo 读回来的; 那个库被黑过, 不信它: 形状不对直接拒绝, 不存在穿越出本目录的可能. */
  private fun resolve(name: String): Path {
    require(NAME.matches(name)) { "非法表情文件名: $name" }
    return dir.resolve(name)
  }
}
