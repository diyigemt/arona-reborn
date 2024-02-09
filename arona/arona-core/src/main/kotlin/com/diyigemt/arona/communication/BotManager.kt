package com.diyigemt.arona.communication

object BotManager {
  private val bots = mutableListOf<TencentBot>()
  fun registerBot(bot: TencentBot) {
    bots.add(bot)
  }
  fun getBot(id: String) = bots.first { it.id == id }
  fun getBot() = bots.first()
}