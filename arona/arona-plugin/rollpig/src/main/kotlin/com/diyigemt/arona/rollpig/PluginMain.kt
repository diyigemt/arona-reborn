package com.diyigemt.arona.rollpig

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.rollpig.db.RollpigDatabase
import com.diyigemt.arona.rollpig.pool.PigPool
import java.nio.file.Files

/**
 * 今日小猪插件。
 *
 * 卡片图为离线预生成的静态资源(见 tools/rollpig-generator), 经框架 dataFolder 外置存储,
 * 换图/更新索引不必重打包 jar。运行期只负责按「用户 + 北京时间日期」确定并发送当天固定的
 * 一只猪, 不下载任何上游资源(发图所需的腾讯上传请求由 host 统一处理)。
 */
@Suppress("unused")
object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION
  )
) {
  override fun onLoad() {
    // 外置数据目录: <工作目录>/data/com.diyigemt.arona.rollpig/ ; 预创建 cards 子目录, 方便投放卡片。
    val dataDir = dataFolderPath.toAbsolutePath()
    val cardsReady = runCatching { Files.createDirectories(resolveDataPath("cards")) }
      .onFailure { logger.error("创建今日小猪数据目录失败: $dataDir", it) }
      .isSuccess

    RollpigDatabase.init()

    // 资源缺失/损坏只让本插件功能降级(指令回维护提示), 不抛异常拖垮整个 bot 的加载。
    // 目录不可用时跳过加载, 避免再刷一串"全部卡片缺失/猪池为空"的次生告警。
    if (cardsReady) {
      runCatching { PigPool.load() }
        .onFailure { logger.error("加载今日小猪外置资源失败, dataFolder=$dataDir", it) }
    }

    if (PigPool.isEmpty()) {
      logger.error(
        "今日小猪猪池为空。请用 tools/rollpig-generator 生成资源, " +
          "并将 index.json 与 cards/*.png 放入 $dataDir 后重启插件。"
      )
    } else {
      logger.info("今日小猪资源加载完成, 共 ${PigPool.size} 只 (dataFolder=$dataDir)")
    }
  }
}
