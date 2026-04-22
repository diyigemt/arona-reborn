package com.diyigemt.arona.utils

import com.charleskorn.kaml.Yaml
import com.diyigemt.arona.communication.TencentBotConfig
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.Charset

@Serializable
data class RedisConfig(
  val host: String,
  val port: Int,
  val db: ULong = 0u,
)

@Serializable
data class MongoConfig(
  val host: String,
  val port: Int,
  val user: String,
  val password: String,
  val db: String
) {
  companion object {
    fun MongoConfig.toConnectionString() = "mongodb://$user:$password@$host:$port"
  }
}

@Serializable
data class MariaDBConfig(
  val host: String = "127.0.0.1:3306",
  val db: String = "arona",
  val user: String = "arona",
  val password: String = "",
)

@Serializable
data class WebConfig(
  val port: Int,
  /**
   * CORS 白名单 (host, 不含 scheme). 空表示任意 host (开发期), 启动时会打 warn.
   */
  val allowedOrigins: List<String> = emptyList(),
  /**
   * 仅在确实部署在反向代理之后时打开. 打开后才信任 X-Forwarded-* / X-Real-IP.
   */
  val behindProxy: Boolean = false,
)

@Serializable
data class AronaConfig(
  val bot: TencentBotConfig,
  val adminToken: String,
  val redis: RedisConfig,
  val mongodb: MongoConfig,
  val mariadb: MariaDBConfig,
  val web: WebConfig,
  val superAdminUid : List<String>,
  val debug: Boolean = false,
) {
  val superAdminUidAsString by lazy {
    superAdminUid.joinToString(",")
  }
}

internal val aronaConfig: AronaConfig by lazy {
  val reader = File("config.yaml").bufferedReader(Charset.forName("UTF-8"))
  val input = reader.use { it.readText() }
  Yaml.default.decodeFromString(AronaConfig.serializer(), input)
}

internal val isDebug = aronaConfig.debug
