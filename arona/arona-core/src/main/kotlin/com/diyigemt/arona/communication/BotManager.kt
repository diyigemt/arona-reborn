package com.diyigemt.arona.communication

object BotManager {
  private val bots = mutableListOf<TencentBot>()
  fun registerBot(bot: TencentBot) {
    if (bots.none { it.id == bot.id }) {
      bots.add(bot)
    }
  }

  fun getBot(id: String) = bots.first { it.id == id }
  fun getBot() = bots.first()
  fun close() {
    bots.forEach {
      it.close()
    }
  }
}
