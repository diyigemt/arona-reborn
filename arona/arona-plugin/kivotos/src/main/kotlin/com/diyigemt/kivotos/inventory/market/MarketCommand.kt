package com.diyigemt.kivotos.inventory.market

import com.diyigemt.kivotos.setObject
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.ItemTemplateCache
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import io.github.crackthecodeabhi.kreds.args.SetOption

/**
 * 市场命令入口. 与 EquipmentCommand 同构:
 *  - 父命令只装载 clikt context, 不做业务
 *  - 每个子命令独立 clikt 子类, 通过 @SubCommand(forClass = MarketCommand::class) 挂上
 *
 * 列表类命令 (搜 / 我的) 各自维护独立的短号空间, 避免互相覆盖:
 *  - "搜" 之后的短号只对 "买" 有效
 *  - "我的" 之后的短号只对 "取消/领钱/重发" 有效
 */
@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class MarketCommand : AbstractCommand(
  Kivotos,
  "市场",
  description = "挂牌交易市场",
  help = tencentCustomMarkdown {
    list {
      +"/${KivotosCommand.primaryName} 市场 挂牌 <道具名> <数量> <货币名> <单价>"
      +"/${KivotosCommand.primaryName} 市场 搜 <道具名>"
      +"/${KivotosCommand.primaryName} 市场 买 <短号>"
      +"/${KivotosCommand.primaryName} 市场 我的"
      +"/${KivotosCommand.primaryName} 市场 取消 <短号>"
      +"/${KivotosCommand.primaryName} 市场 领钱 <短号>"
      +"/${KivotosCommand.primaryName} 市场 重发 <短号>"
    }
  }.content,
) {
  suspend fun UserCommandSender.market0() {
    currentContext.setObject("md", tencentCustomMarkdown { })
    currentContext.setObject("kb", tencentCustomKeyboard { })
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketPostCommand : AbstractCommand(
  Kivotos,
  "挂牌",
  description = "挂牌出售道具",
  help = "/${KivotosCommand.primaryName} 市场 挂牌 <道具名> <数量> <货币名> <单价>",
) {
  private val itemName by argument("道具名").optional()
  private val countArg by argument("数量").optional()
  private val priceName by argument("货币名").optional()
  private val priceUnitArg by argument("单价").optional()

  suspend fun UserCommandSender.post() {
    if (itemName == null || countArg == null || priceName == null || priceUnitArg == null) {
      sendMessage("用法: /${KivotosCommand.primaryName} 市场 挂牌 <道具名> <数量> <货币名> <单价>")
      return
    }
    val item = ItemTemplateCache.getByName(itemName!!) ?: run {
      sendMessage("没有找到道具: $itemName"); return
    }
    val price = ItemTemplateCache.getByName(priceName!!) ?: run {
      sendMessage("没有找到货币: $priceName"); return
    }
    val count = countArg!!.toIntOrNull() ?: run {
      sendMessage("数量必须是整数"); return
    }
    val priceUnit = priceUnitArg!!.toLongOrNull() ?: run {
      sendMessage("单价必须是整数"); return
    }

    val uid = userDocument().id
    val postKey = uuid("market.post")
    val traceId = uuid("market")
    val ctx = GrantContext(
      reason = "market.post.${item.id}",
      sourceType = "command",
      sourceId = item.id.toString(),
      traceId = traceId,
    )
    when (val r = MarketService.post(uid, item.id, count, price.id, priceUnit, MarketService.defaultExpiresAt(), postKey, ctx)) {
      is PostResult.Ok -> sendMessage(
        "挂牌成功 listingId=${r.listing.id}\n${item.name} x${r.listing.count}, 单价 ${price.name} ${r.listing.priceUnit}, 总价 ${r.listing.totalPrice}"
      )
      is PostResult.BadParam -> sendMessage(r.reason)
      is PostResult.InsufficientStock -> sendMessage(
        "库存不足: " + r.shortages.joinToString(", ") { "itemId=${it.itemId} 缺 ${it.amount}" }
      )
      is PostResult.TooManyActive -> sendMessage("当前在售挂牌已达上限 ${MarketService.MAX_ACTIVE_PER_UID}")
      is PostResult.Untradable -> sendMessage(r.reason)
      is PostResult.WriteFailed -> sendMessage("挂牌写库失败, 请联系管理员 (traceId=$traceId)")
    }
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketSearchCommand : AbstractCommand(
  Kivotos,
  "搜",
  description = "搜索市场在售道具",
  help = "/${KivotosCommand.primaryName} 市场 搜 <道具名>",
) {
  private val itemName by argument("道具名").optional()

  suspend fun UserCommandSender.search() {
    if (itemName == null) {
      sendMessage("用法: /${KivotosCommand.primaryName} 市场 搜 <道具名>")
      return
    }
    val item = ItemTemplateCache.getByName(itemName!!) ?: run {
      sendMessage("没有找到道具: $itemName"); return
    }
    val uid = userDocument().id
    val listings = MarketService.search(item.id, 10)
    if (listings.isEmpty()) {
      sendMessage("暂无在售 ${item.name}")
      return
    }
    MarketSearchShortIdCache.store(uid, listings.map { it.id })
    val lines = listings.mapIndexed { index, listing ->
      val priceName = ItemTemplateCache.get(listing.priceItemId)?.name ?: "item#${listing.priceItemId}"
      "#${index + 1} ${item.name} x${listing.count}, 单价 $priceName ${listing.priceUnit}, 总价 ${listing.totalPrice}"
    }
    sendMessage(tencentCustomMarkdown {
      h1("市场搜索 ${item.name}")
      list { lines.forEach { +it } }
      +"购买: /${KivotosCommand.primaryName} 市场 买 <短号>"
      at()
    })
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketBuyCommand : AbstractCommand(
  Kivotos,
  "买",
  description = "购买市场挂牌",
  help = "/${KivotosCommand.primaryName} 市场 买 <短号>",
) {
  private val shortId by argument("短号").optional()

  suspend fun UserCommandSender.buy() {
    val sid = shortId ?: run {
      sendMessage("用法: /${KivotosCommand.primaryName} 市场 买 <短号>"); return
    }
    val uid = userDocument().id
    val listingId = MarketSearchShortIdCache.resolve(uid, sid) ?: run {
      sendMessage("短号 $sid 无效, 请先 /${KivotosCommand.primaryName} 市场 搜 <道具名> 刷新"); return
    }
    val listing = MarketService.findById(listingId) ?: run {
      sendMessage("挂牌不存在"); return
    }
    val itemName = ItemTemplateCache.get(listing.itemId)?.name ?: "item#${listing.itemId}"
    val priceName = ItemTemplateCache.get(listing.priceItemId)?.name ?: "item#${listing.priceItemId}"

    val traceId = uuid("market.buy")
    val confirmData = "market.buy.confirm:$traceId"
    val cancelData = "market.buy.cancel:$traceId"
    val msg = tencentCustomMarkdown {
      h2("确认购买")
      +"$itemName x${listing.count}"
      +"单价: $priceName ${listing.priceUnit}"
      +"总价: $priceName ${listing.totalPrice}"
      at()
    } + tencentCustomKeyboard {
      row {
        button {
          render { label = "确认" }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = confirmData
          }
          selfOnly()
        }
        button {
          render { label = "取消" }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = cancelData
          }
          selfOnly()
        }
      }
    }
    val sent = sendMessage(msg)
    val next = runCatching {
      nextButtonInteraction(timeoutMillis = 25_000L) { filter ->
        filter.buttonData == confirmData || filter.buttonData == cancelData
      }
    }.getOrNull()
    val decision = when (next?.buttonData) {
      confirmData -> { next.accept(); true }
      cancelData -> { next.accept(); false }
      else -> null
    }
    sent?.recall()

    when (decision) {
      null -> sendMessage("操作超时, 已取消")
      false -> sendMessage("已取消")
      true -> {
        val ctx = GrantContext(
          reason = "market.buy.$listingId",
          sourceType = "command",
          sourceId = listingId,
          traceId = traceId,
        )
        when (val r = MarketService.buy(uid, listingId, ctx)) {
          is BuyResult.Ok -> sendMessage("购买成功: $itemName x${r.listing.count}")
          is BuyResult.NotFound -> sendMessage("挂牌不存在")
          is BuyResult.NotActive -> sendMessage("挂牌已不可购买 (可能已售出或取消)")
          is BuyResult.Expired -> sendMessage("挂牌已过期")
          is BuyResult.SelfTrade -> sendMessage("不能购买自己的挂牌")
          is BuyResult.Insufficient -> sendMessage(
            "货币不足: " + r.shortages.joinToString(", ") { "itemId=${it.itemId} 缺 ${it.amount}" }
          )
          is BuyResult.Unsupported -> sendMessage("库存路径不支持: ${r.reason}")
          is BuyResult.SettlementStuck -> sendMessage(
            "已扣款但挂牌状态推进失败, 请联系管理员对账 (traceId=${r.traceId})"
          )
          is BuyResult.WriteFailed -> sendMessage("购买写库失败: ${r.reason}")
        }
      }
    }
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketMineCommand : AbstractCommand(
  Kivotos,
  "我的",
  description = "查看自己的市场挂牌",
  help = "/${KivotosCommand.primaryName} 市场 我的",
) {
  suspend fun UserCommandSender.mine() {
    val uid = userDocument().id
    val statuses = listOf(
      MarketStatus.ACTIVE, MarketStatus.BUYING, MarketStatus.SOLD,
      MarketStatus.CANCELLING, MarketStatus.EXPIRING, MarketStatus.RETURN_FAILED,
    )
    val listings = MarketService.listByOwner(uid, statuses)
    if (listings.isEmpty()) {
      sendMessage("暂无市场挂牌")
      return
    }
    MarketMineShortIdCache.store(uid, listings.map { it.id })
    val lines = listings.mapIndexed { index, listing ->
      val itemName = ItemTemplateCache.get(listing.itemId)?.name ?: "item#${listing.itemId}"
      val priceName = ItemTemplateCache.get(listing.priceItemId)?.name ?: "item#${listing.priceItemId}"
      val status = when {
        listing.status == MarketStatus.SOLD && listing.settlementStatus == SettlementStatus.PENDING -> "已售待领钱"
        listing.status == MarketStatus.SOLD && listing.settlementStatus == SettlementStatus.PAID -> "已领钱"
        listing.status == MarketStatus.SOLD -> "已售:${listing.settlementStatus}"
        listing.status == MarketStatus.RETURN_FAILED -> "返还失败(可重发)"
        else -> listing.status.name
      }
      "#${index + 1} [$status] $itemName x${listing.count}, $priceName ${listing.totalPrice}"
    }
    sendMessage(tencentCustomMarkdown {
      h1("我的市场")
      list { lines.forEach { +it } }
      +"领钱/取消/重发: /${KivotosCommand.primaryName} 市场 <动作> <短号>"
      at()
    })
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketCancelCommand : AbstractCommand(
  Kivotos,
  "取消",
  description = "取消自己的在售挂牌",
  help = "/${KivotosCommand.primaryName} 市场 取消 <短号>",
) {
  private val shortId by argument("短号").optional()

  suspend fun UserCommandSender.cancel() {
    val (uid, id) = resolveMineShortId(shortId) ?: return
    val traceId = uuid("market.cancel")
    val ctx = GrantContext(
      reason = "market.cancel.$id",
      sourceType = "command",
      sourceId = id,
      traceId = traceId,
    )
    when (val r = MarketService.cancel(uid, id, ctx)) {
      is CancelResult.Ok -> sendMessage("已取消并返还道具")
      is CancelResult.NotFound -> sendMessage("挂牌不存在")
      is CancelResult.NotOwner -> sendMessage("该挂牌不属于你")
      is CancelResult.NotActive -> sendMessage("挂牌非在售状态, 不可取消")
      is CancelResult.ReturnFailed -> sendMessage(
        "取消状态已推进但返还道具失败: ${r.reason}\n请稍后用 /${KivotosCommand.primaryName} 市场 重发 <短号> 重试 (traceId=$traceId)"
      )
    }
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketSettleCommand : AbstractCommand(
  Kivotos,
  "领钱",
  description = "领取已售出挂牌的货款",
  help = "/${KivotosCommand.primaryName} 市场 领钱 <短号>",
) {
  private val shortId by argument("短号").optional()

  suspend fun UserCommandSender.settle() {
    val (uid, id) = resolveMineShortId(shortId) ?: return
    val traceId = uuid("market.settle")
    val ctx = GrantContext(
      reason = "market.settle.$id",
      sourceType = "command",
      sourceId = id,
      traceId = traceId,
    )
    when (val r = MarketService.settle(uid, id, ctx)) {
      is SettleResult.Ok -> sendMessage("领取成功: ${r.amount}")
      is SettleResult.NotFound -> sendMessage("挂牌不存在")
      is SettleResult.NotOwner -> sendMessage("该挂牌不属于你")
      is SettleResult.NotSettlable -> sendMessage("该挂牌无需领取或已在结算中")
      is SettleResult.PayFailed -> sendMessage("发放失败: ${r.reason}, 请稍后重试")
      is SettleResult.WriteFailed -> sendMessage("结算状态推进失败, 请联系管理员 (traceId=$traceId)")
    }
  }
}

@SubCommand(forClass = MarketCommand::class)
@Suppress("unused")
class MarketRetryReturnCommand : AbstractCommand(
  Kivotos,
  "重发",
  description = "重发失败的返还道具",
  help = "/${KivotosCommand.primaryName} 市场 重发 <短号>",
) {
  private val shortId by argument("短号").optional()

  suspend fun UserCommandSender.retry() {
    val (uid, id) = resolveMineShortId(shortId) ?: return
    val traceId = uuid("market.retryReturn")
    val ctx = GrantContext(
      reason = "market.retryReturn.$id",
      sourceType = "command",
      sourceId = id,
      traceId = traceId,
    )
    when (val r = MarketService.retryReturn(uid, id, ctx)) {
      is RetryReturnResult.Ok -> sendMessage("返还成功")
      is RetryReturnResult.NotFound -> sendMessage("挂牌不存在")
      is RetryReturnResult.NotOwner -> sendMessage("该挂牌不属于你")
      is RetryReturnResult.NotReturnFailed -> sendMessage("该挂牌没有待重发的返还")
      is RetryReturnResult.ReturnStillFailed -> sendMessage("返还仍失败: ${r.reason}, 请稍后再试")
      is RetryReturnResult.WriteFailed -> sendMessage("重试状态推进失败: ${r.reason}")
    }
  }
}

/** 提取共用逻辑: 接受短号, 返回 (uid, listingId) 或发失败消息后返回 null. */
private suspend fun UserCommandSender.resolveMineShortId(shortId: String?): Pair<String, String>? {
  val sid = shortId ?: run {
    sendMessage("用法: /${KivotosCommand.primaryName} 市场 <动作> <短号>"); return null
  }
  val uid = userDocument().id
  val listingId = MarketMineShortIdCache.resolve(uid, sid) ?: run {
    sendMessage("短号 $sid 无效, 请先 /${KivotosCommand.primaryName} 市场 我的 刷新"); return null
  }
  return uid to listingId
}

/** 搜索上下文的短号缓存 (只供 /市场 买 使用), 与 "我的" 分离避免互相覆盖. */
private object MarketSearchShortIdCache {
  private const val PREFIX = "kivotos.market.shortid.search"
  private const val SEPARATOR = "|"
  private const val TTL_SECONDS: ULong = 300u

  suspend fun store(uid: String, orderedIds: List<String>) {
    redisDbQuery {
      set("$PREFIX.$uid", orderedIds.joinToString(SEPARATOR), SetOption.Builder(exSeconds = TTL_SECONDS).build())
    }
  }

  suspend fun resolve(uid: String, input: String): String? {
    val index = input.removePrefix("#").toIntOrNull() ?: return null
    if (index <= 0) return null
    val raw = redisDbQuery { get("$PREFIX.$uid") } ?: return null
    return raw.split(SEPARATOR).getOrNull(index - 1)?.takeIf { it.isNotBlank() }
  }
}

/** 我的挂牌上下文的短号缓存 (只供 /市场 取消/领钱/重发 使用). */
private object MarketMineShortIdCache {
  private const val PREFIX = "kivotos.market.shortid.mine"
  private const val SEPARATOR = "|"
  private const val TTL_SECONDS: ULong = 300u

  suspend fun store(uid: String, orderedIds: List<String>) {
    redisDbQuery {
      set("$PREFIX.$uid", orderedIds.joinToString(SEPARATOR), SetOption.Builder(exSeconds = TTL_SECONDS).build())
    }
  }

  suspend fun resolve(uid: String, input: String): String? {
    val index = input.removePrefix("#").toIntOrNull() ?: return null
    if (index <= 0) return null
    val raw = redisDbQuery { get("$PREFIX.$uid") } ?: return null
    return raw.split(SEPARATOR).getOrNull(index - 1)?.takeIf { it.isNotBlank() }
  }
}
