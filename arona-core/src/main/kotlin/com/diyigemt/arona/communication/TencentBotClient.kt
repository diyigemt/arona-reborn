package com.diyigemt.arona.communication

data class TencentBotConfig(
  val appId: String,
  val secret: String
)

class TencentBotClient private constructor(val config: TencentBotConfig) {
  companion object {
    operator fun invoke(config: TencentBotConfig): TencentBotClient {
      return TencentBotClient(config)
    }
  }
}
