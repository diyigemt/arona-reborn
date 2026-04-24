package com.diyigemt.kivotos.inventory.market

import com.diyigemt.arona.communication.event.AbstractEvent

/**
 * 市场挂牌生命周期事件, 由 [MarketService] 在对应状态原子推进**之后**广播.
 *
 * 在 listing 终态后广播, 订阅方可以安全地读 listing 做后续动作 (发消息 / 通知卖家等),
 * 不必担心与主状态机竞争. 不 `@Serializable`, 事件不落库.
 */
sealed class MarketEvent : AbstractEvent() {
  abstract val uid: String
  abstract val listingId: String
  abstract val traceId: String
}

data class MarketListedEvent(
  override val uid: String,
  override val listingId: String,
  override val traceId: String,
) : MarketEvent()

data class MarketSoldEvent(
  override val uid: String,
  override val listingId: String,
  val buyerUid: String,
  override val traceId: String,
) : MarketEvent()

data class MarketSettledEvent(
  override val uid: String,
  override val listingId: String,
  val amount: Long,
  override val traceId: String,
) : MarketEvent()

data class MarketCancelledEvent(
  override val uid: String,
  override val listingId: String,
  override val traceId: String,
) : MarketEvent()

data class MarketExpiredEvent(
  override val uid: String,
  override val listingId: String,
  override val traceId: String,
) : MarketEvent()

data class MarketReturnFailedEvent(
  override val uid: String,
  override val listingId: String,
  val returnTarget: MarketStatus,
  override val traceId: String,
) : MarketEvent()
