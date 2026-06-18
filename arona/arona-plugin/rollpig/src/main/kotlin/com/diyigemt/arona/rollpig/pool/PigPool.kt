package com.diyigemt.arona.rollpig.pool

import com.diyigemt.arona.rollpig.PluginMain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ThreadLocalRandom

/** 猪池中的一只猪。[name] 仅用于日志排障, 不参与发送(卡片文字已烤进图片)。 */
internal data class Pig(val id: String, val name: String)

/**
 * 从插件 **dataFolder**(框架存储, 见 [com.diyigemt.arona.plugins.PluginFileExtensions])
 * 加载的猪池, 而非打进 jar 的 classpath, 这样换图/更新索引不必重新打包。
 *
 * 数据目录布局(`<工作目录>/data/com.diyigemt.arona.rollpig/`):
 * - `index.json`: `{"pigs":[{"id":"human","name":"人类"}, ...]}`
 * - `cards/<id>.png`: 预生成的 800x800 卡片。
 *
 * 加载策略:
 * - 索引文件缺失 → 空池 + 告警(可预期的部署状态, 不抛栈)。
 * - 索引格式非法(id 不合法/重复/名称空) → 抛出, 由 [PluginMain] 捕获, 保留上一份有效快照。
 * - 索引合法但**个别卡片缺失** → 跳过该只并告警, 其余照常可用(避免单图缺失拖垮整池,
 *   也保证 [random] 不会抽到没有卡片的猪)。
 * - 全量校验后一次性替换内存猪池, 避免中途失败暴露半截数据。
 *
 * 更新数据后需重启插件方能生效(内存快照 + 上传缓存均不热更新)。
 */
internal object PigPool {
  private const val INDEX_FILE = "index.json"

  // 与生成脚本保持一致: id 仅允许这些字符, 既防 index 脏数据, 也杜绝 `../` 等文件路径穿越。
  private val SAFE_ID = Regex("^[A-Za-z0-9_-]+$")
  private val json = Json { ignoreUnknownKeys = true }

  @Volatile
  private var pigs: List<Pig> = emptyList()

  @Serializable
  private data class IndexFile(val pigs: List<IndexEntry> = emptyList())

  @Serializable
  private data class IndexEntry(val id: String, val name: String)

  fun load() {
    val indexFile = PluginMain.resolveDataFile(INDEX_FILE)
    if (!indexFile.isFile) {
      pigs = emptyList()
      PluginMain.logger.error("今日小猪索引不存在, 请放置文件后重启: ${indexFile.absolutePath}")
      return
    }

    val index = json.decodeFromString<IndexFile>(indexFile.readText(Charsets.UTF_8))
    val seen = HashSet<String>(index.pigs.size)
    val missing = ArrayList<String>()
    val loaded = ArrayList<Pig>(index.pigs.size)

    index.pigs.forEach { entry ->
      require(SAFE_ID.matches(entry.id)) { "非法小猪 id: '${entry.id}', 仅允许 A-Za-z0-9_-" }
      require(seen.add(entry.id)) { "小猪 id 重复: ${entry.id}" }
      require(entry.name.isNotBlank()) { "小猪名称为空: ${entry.id}" }

      // 卡片缺失只跳过该只(部署可能分步覆盖), 不让单图缺失把整池清空。
      val card = cardFile(entry.id)
      if (card.isFile && card.length() > 0L) {
        loaded.add(Pig(entry.id, entry.name))
      } else {
        missing.add(entry.id)
      }
    }

    if (missing.isNotEmpty()) {
      PluginMain.logger.warn("以下 ${missing.size} 只小猪缺少卡片已跳过: ${missing.joinToString()}")
    }
    pigs = loaded
  }

  val size: Int get() = pigs.size

  fun isEmpty(): Boolean = pigs.isEmpty()

  /** 随机取一只。猪池为空时抛出, 调用方应先用 [isEmpty] 判定。 */
  fun random(): Pig {
    val snapshot = pigs
    check(snapshot.isNotEmpty()) { "今日小猪猪池为空" }
    return snapshot[ThreadLocalRandom.current().nextInt(snapshot.size)]
  }

  /** 读取某只猪的卡片字节。id 非法或卡片缺失(如已被移除的旧猪)时抛出, 由调用方降级处理。 */
  fun cardBytes(id: String): ByteArray {
    val file = cardFile(id)
    require(file.isFile && file.length() > 0L) { "卡片缺失或为空: ${file.absolutePath}" }
    return file.readBytes()
  }

  private fun cardFile(id: String): File {
    require(SAFE_ID.matches(id)) { "非法小猪 id: '$id'" }
    return PluginMain.resolveDataFile("cards/$id.png")
  }
}
