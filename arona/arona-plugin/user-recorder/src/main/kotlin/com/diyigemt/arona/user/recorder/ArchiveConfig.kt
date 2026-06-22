package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value

/**
 * user-recorder 的 MongoDB 归档配置 (配置文件: config/<pluginId>/archive.*, 由框架反射自动加载)。
 *
 * 该插件历史上仅依赖 Redis, 因此 [enabled] 默认 false: 必须由部署方在 MongoDB 就绪后显式开启。
 * 关闭时插件完全不接触 MongoDB; 即使开启后连接失败, 也不会阻塞插件加载或当天的 Redis 记录路径。
 */
object ArchiveConfig : AutoSavePluginData("archive") {
  /**
   * 归档总开关。false 时不创建 MongoClient、不启动归档调度, 历史查询仅读 Redis。
   *
   * 注意: 一旦启用并归档过 (往日数据已从 Redis 删除), 不应再关闭——关闭后历史查询不会回退 Mongo,
   * 那些已删除的往日数据会显示为 0/未找到。即 enabled 在已产生归档后视为单向开关。
   */
  val enabled by value(false)
  val host by value("127.0.0.1")
  val port by value(27017)

  /** 用户名为空表示免认证连接, 此时不附加 MongoCredential。 */
  val user by value("")
  val password by value("")
  val db by value("arona")

  /** 认证库。留空跟随 [db], 避免改 db 后仍指向旧默认值。 */
  val authSource by value("")

  /** 归档集合名。 */
  val collection by value("dau_archive")

  /** 每日归档触发的本地时刻 (系统默认时区)。默认 00:10, 留出跨午夜写入的宽限窗口。 */
  val archiveHour by value(0)
  val archiveMinute by value(10)

  /** SCAN 每批 COUNT 提示。越大批次越少、单次阻塞略增; 对共享 Redis 取中庸默认。 */
  val scanCount by value(200)

  /** 实际生效的认证库。 */
  internal val effectiveAuthSource: String
    get() = authSource.ifBlank { db }
}
