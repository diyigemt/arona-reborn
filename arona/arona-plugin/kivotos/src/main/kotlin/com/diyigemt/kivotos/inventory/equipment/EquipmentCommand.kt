package com.diyigemt.kivotos.inventory.equipment

import com.diyigemt.kivotos.setObject
import com.diyigemt.arona.arona.database.student.StudentSchema
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
import com.diyigemt.kivotos.tools.normalizeStudentName
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import io.github.crackthecodeabhi.kreds.args.SetOption

/**
 * 装备子命令入口. clikt 需要 parent 命令暴露 `md`/`kb` 对象给子命令, 这里只做 context 装配.
 *
 * 装备命令用"短号"替代 uuid 让 QQ 用户输入, 短号由 [EquipmentShortIdCache] 在 `/装备 列表` 展示时分配,
 * 5 分钟 TTL 覆盖典型查看 → 穿戴的操作窗口.
 */
@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class EquipmentCommand : AbstractCommand(
  Kivotos,
  "装备",
  description = "装备相关指令",
  help = tencentCustomMarkdown {
    list {
      +"/${KivotosCommand.primaryName} 装备 列表, 查看装备"
      +"/${KivotosCommand.primaryName} 装备 穿戴 <学生名> <短号>, 为学生穿上装备"
      +"/${KivotosCommand.primaryName} 装备 卸下 <短号>, 卸下装备"
      +"/${KivotosCommand.primaryName} 装备 强化 <短号>, 消耗材料强化"
    }
  }.content,
) {
  suspend fun UserCommandSender.equipment0() {
    currentContext.setObject("md", tencentCustomMarkdown { })
    currentContext.setObject("kb", tencentCustomKeyboard { })
  }
}

@SubCommand(forClass = EquipmentCommand::class)
@Suppress("unused")
class EquipmentListCommand : AbstractCommand(
  Kivotos,
  "列表",
  description = "查看装备",
  help = "/${KivotosCommand.primaryName} 装备 列表",
) {
  suspend fun UserCommandSender.list() {
    val uid = userDocument().id
    val all = EquipmentService.listByUid(uid)
    if (all.isEmpty()) {
      sendMessage("背包里没有装备")
      return
    }
    val sorted = all.sortedWith(compareBy({ it.equippedBy == null }, { it.tplId.toInt() }, { it.id }))
    EquipmentShortIdCache.store(uid, sorted.map { it.id })

    // ItemTemplateCache.get 是 suspend, 不能在非 suspend 的 DSL lambda 里调用; 先 suspend 组装行再进入 DSL
    val lines = sorted.mapIndexed { index, inst ->
      val tplName = ItemTemplateCache.get(inst.tplId)?.name ?: "tpl#${inst.tplId}"
      val worn = if (inst.equippedBy != null && inst.slot != null) {
        val studentName = StudentSchema.StudentCache[inst.equippedBy]?.name ?: "#${inst.equippedBy}"
        "[$studentName/${inst.slot}]"
      } else {
        "[背包]"
      }
      val enhance = if (inst.enhance > 0) "+${inst.enhance}" else ""
      "#${index + 1} $tplName$enhance $worn"
    }

    val md = tencentCustomMarkdown {
      h1("装备")
      list { lines.forEach { +it } }
      at()
    }
    sendMessage(md)
  }
}

@SubCommand(forClass = EquipmentCommand::class)
@Suppress("unused")
class EquipmentEquipCommand : AbstractCommand(
  Kivotos,
  "穿戴",
  description = "为学生穿上装备",
  help = "/${KivotosCommand.primaryName} 装备 穿戴 <学生名> <短号>",
) {
  private val studentName by argument("学生名").optional()
  private val shortId by argument("装备短号").optional()

  suspend fun UserCommandSender.equip() {
    if (studentName == null || shortId == null) {
      sendMessage("用法: /${KivotosCommand.primaryName} 装备 穿戴 <学生名> <短号>")
      return
    }
    val uid = userDocument().id
    val student = resolveStudentId(studentName!!) ?: run {
      sendMessage("找不到学生: $studentName"); return
    }
    val instanceId = EquipmentShortIdCache.resolve(uid, shortId!!) ?: run {
      sendMessage("短号 $shortId 无效, 请先 /${KivotosCommand.primaryName} 装备 列表 刷新"); return
    }

    val ctx = GrantContext(
      reason = "equip",
      sourceType = "command",
      sourceId = instanceId,
      traceId = uuid("equip"),
    )
    when (val outcome = EquipmentService.equip(uid, instanceId, student, ctx)) {
      is EquipResult.Ok -> sendMessage(
        if (outcome.replacedInstanceId != null) "穿戴成功 (替换了旧装备)" else "穿戴成功"
      )
      is EquipResult.AlreadyEquipped -> sendMessage("该装备已经穿在目标学生该槽位")
      is EquipResult.InstanceMissing -> sendMessage("装备不存在, 可能已被删除")
      is EquipResult.NotOwnedByUid -> sendMessage("该装备不属于你")
      is EquipResult.NotEquipmentCategory -> sendMessage("模板配置错误: 该物品不是装备")
      is EquipResult.TemplateMissing -> sendMessage("装备模板缺失或未配置 EquipmentPayload")
      is EquipResult.WriteFailed -> sendMessage("穿戴失败 (并发冲突), 请重试")
    }
  }
}

@SubCommand(forClass = EquipmentCommand::class)
@Suppress("unused")
class EquipmentUnequipCommand : AbstractCommand(
  Kivotos,
  "卸下",
  description = "卸下装备",
  help = "/${KivotosCommand.primaryName} 装备 卸下 <短号>",
) {
  private val shortId by argument("装备短号").optional()

  suspend fun UserCommandSender.unequip() {
    val sid = shortId
    if (sid == null) {
      sendMessage("用法: /${KivotosCommand.primaryName} 装备 卸下 <短号>")
      return
    }
    val uid = userDocument().id
    val instanceId = EquipmentShortIdCache.resolve(uid, sid) ?: run {
      sendMessage("短号 $sid 无效, 请先 /${KivotosCommand.primaryName} 装备 列表 刷新"); return
    }
    val ctx = GrantContext(
      reason = "unequip",
      sourceType = "command",
      sourceId = instanceId,
      traceId = uuid("unequip"),
    )
    when (EquipmentService.unequip(uid, instanceId, ctx)) {
      is UnequipResult.Ok -> sendMessage("卸下成功")
      is UnequipResult.InstanceMissing -> sendMessage("装备不存在")
      is UnequipResult.NotOwnedByUid -> sendMessage("该装备不属于你")
      is UnequipResult.NotEquipped -> sendMessage("该装备未穿戴")
    }
  }
}

@SubCommand(forClass = EquipmentCommand::class)
@Suppress("unused")
class EquipmentEnhanceCommand : AbstractCommand(
  Kivotos,
  "强化",
  description = "消耗材料强化装备",
  help = "/${KivotosCommand.primaryName} 装备 强化 <短号>",
) {
  private val shortId by argument("装备短号").optional()

  suspend fun UserCommandSender.enhance() {
    val sid = shortId
    if (sid == null) {
      sendMessage("用法: /${KivotosCommand.primaryName} 装备 强化 <短号>")
      return
    }
    val uid = userDocument().id
    val instanceId = EquipmentShortIdCache.resolve(uid, sid) ?: run {
      sendMessage("短号 $sid 无效, 请先 /${KivotosCommand.primaryName} 装备 列表 刷新"); return
    }
    val instance = EquipmentService.findById(instanceId) ?: run {
      sendMessage("装备不存在"); return
    }
    if (instance.uid != uid) {
      sendMessage("该装备不属于你"); return
    }
    val payload = ItemTemplateCache.getEquipmentPayload(instance.tplId) ?: run {
      sendMessage("模板缺失或未配置"); return
    }
    if (instance.enhance >= payload.maxEnhance) {
      sendMessage("已经满级 (+${instance.enhance})"); return
    }
    val cost = payload.enhanceCost.getOrNull(instance.enhance)
    if (cost.isNullOrEmpty()) {
      sendMessage("该等级未配置材料, 无法强化"); return
    }

    val traceId = uuid("enhance")
    val confirmData = "confirm:$traceId"
    val cancelData = "cancel:$traceId"
    val tplName = ItemTemplateCache.get(instance.tplId)?.name ?: "tpl#${instance.tplId}"

    val costLine = "消耗: " + cost.joinToString(", ") { "itemId=${it.itemId} x${it.amount}" }
    val msg = tencentCustomMarkdown {
      h2("确认强化")
      +"$tplName +${instance.enhance} → +${instance.enhance + 1}"
      +costLine
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
        // 幂等键绑定 (实例, 当前等级). 连发两次命令会落到同一 key, InventoryService 的 Redis 占位拦住重复扣材料.
        // 强化成功后等级变化, 下次命令键不同, 用户可继续强化.
        val ctx = GrantContext(
          reason = "enhance",
          sourceType = "command",
          sourceId = instanceId,
          idempotencyKey = "enhance.$instanceId.${instance.enhance}",
          traceId = traceId,
        )
        when (val r = EquipmentService.enhance(uid, instanceId, ctx)) {
          is EnhanceResult.Ok -> sendMessage("强化成功: +${r.fromLevel} → +${r.toLevel}")
          is EnhanceResult.AtMaxLevel -> sendMessage("已是满级")
          is EnhanceResult.Insufficient -> sendMessage(
            "材料不足: " + r.shortages.joinToString(", ") { "itemId=${it.itemId} 缺 ${it.amount}" }
          )
          is EnhanceResult.InstanceMissing -> sendMessage("装备不存在")
          is EnhanceResult.NotOwnedByUid -> sendMessage("该装备不属于你")
          is EnhanceResult.NotEquipmentCategory -> sendMessage("模板配置错误")
          is EnhanceResult.TemplateMissing -> sendMessage("模板缺失")
          is EnhanceResult.DuplicateRequest -> sendMessage("重复请求, 前次 traceId=${r.previousTraceId}")
          is EnhanceResult.InventoryFailed -> sendMessage("库存操作失败: ${r.reason}")
          is EnhanceResult.WriteFailed -> sendMessage("强化写库失败, 材料已扣, 请联系管理员补偿 (traceId=$traceId)")
        }
      }
    }
  }
}

/**
 * uid 维度的装备短号缓存. 列出装备时写入顺序列表到 Redis, TTL 5 分钟.
 * 穿戴/卸下/强化时按短号查 uuid.
 *
 * 用 Redis 而非内存 Map 的原因:
 *  - 命令进程可能多开, Redis 是跨实例共享的事实来源
 *  - TTL 天然地把"过期列表"清掉, 避免长时间后短号指向陈旧 uuid
 */
/** 从命令输入中解析学生, 先走项目的名字归一化, 再在内存缓存里精确匹配. */
private suspend fun resolveStudentId(rawName: String): Int? {
  val canonical = normalizeStudentName(rawName) ?: return null
  return StudentSchema.StudentCache.values.firstOrNull { it.name == canonical }?.id?.value
}

private object EquipmentShortIdCache {
  private const val PREFIX = "kivotos.equip.shortid"
  // uuid 不含 "|", 直接拼字符串比引入 Redis list 更简单, 整条键带 TTL 自动清理
  private const val SEPARATOR = "|"
  private const val TTL_SECONDS: ULong = 300u

  suspend fun store(uid: String, orderedIds: List<String>) {
    val value = orderedIds.joinToString(SEPARATOR)
    redisDbQuery {
      set("$PREFIX.$uid", value, SetOption.Builder(exSeconds = TTL_SECONDS).build())
    }
  }

  /** 接受 "#3" 或 "3" 两种输入. */
  suspend fun resolve(uid: String, input: String): String? {
    val index = input.removePrefix("#").toIntOrNull() ?: return null
    if (index <= 0) return null
    val raw = redisDbQuery { get("$PREFIX.$uid") } ?: return null
    return raw.split(SEPARATOR).getOrNull(index - 1)?.takeIf { it.isNotBlank() }
  }
}
