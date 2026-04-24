package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.inventory.use.ApplyOutcome
import com.diyigemt.kivotos.inventory.use.PreviewOutcome
import com.diyigemt.kivotos.inventory.use.UsePreview
import com.diyigemt.kivotos.inventory.use.UseService
import com.diyigemt.kivotos.inventory.use.UseTarget
import com.diyigemt.kivotos.schema.UserDocument
import com.diyigemt.kivotos.tools.normalizeStudentName
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

/**
 * 背包子命令: 展示货币 / 资源 / 可堆叠道具三段.
 *
 * 目前不对条数限流 — 单用户初期持有量可控, 真正的截断逻辑留到 UI 接入分页时一并处理.
 * 后续 P2 会追加装备段.
 */
@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class InventoryShowCommand : AbstractCommand(
  Kivotos,
  "背包",
  description = "查看当前仓库",
  help = "/${KivotosCommand.primaryName} 背包",
) {
  suspend fun UserCommandSender.show() {
    val uid = userDocument().id
    val inv = InventoryService.loadInventory(uid)
    val currencyLines = lookupLines(inv.currencies) { tpl, amount -> "${tpl.name}: $amount" }
    val resourceLines = lookupLines(inv.resources) { tpl, state -> "${tpl.name}: ${state.amount}/${state.cap}" }
    val stackableLines = lookupLines(inv.stackables.filterValues { it > 0 }) { tpl, count -> "${tpl.name} x$count" }
    val equipmentCount = com.diyigemt.kivotos.inventory.equipment.EquipmentService.listByUid(uid).size

    val md = tencentCustomMarkdown {
      h1("背包")
      +"【货币】"
      if (currencyLines.isEmpty()) +"(空)" else list { currencyLines.forEach { +it } }
      +"【资源】"
      if (resourceLines.isEmpty()) +"(空)" else list { resourceLines.forEach { +it } }
      +"【道具】"
      if (stackableLines.isEmpty()) +"(空)" else list { stackableLines.forEach { +it } }
      +"【装备】"
      +"共 $equipmentCount 件 (详见 /${KivotosCommand.primaryName} 装备 列表)"
      at()
    }
    sendMessage(md)
  }

  private suspend fun <V> lookupLines(source: Map<String, V>, render: (ItemTemplateDocument, V) -> String): List<String> =
    source.mapNotNull { (key, value) ->
      val tpl = ItemTemplateCache.get(key.asItemId()) ?: return@mapNotNull null
      render(tpl, value)
    }
}

/**
 * 使用道具子命令: `/赛博基沃托斯 使用 <名称> [数量] [目标]`.
 *
 * 关键点:
 *  - 按钮 payload 带 traceId, 避免现有 `nextButtonInteraction` 在同一用户同一会话多个确认框串单
 *  - 超时 25 秒 (10 秒对移动端切后台过短)
 *  - apply 会重做 validate, 不信任 preview 阶段的结果
 */
@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class InventoryUseCommand : AbstractCommand(
  Kivotos,
  "使用",
  description = "使用道具",
  help = "/${KivotosCommand.primaryName} 使用 <道具名> [数量] [目标]",
) {
  private val itemName by argument("道具名").optional()
  private val countArg by argument("数量").optional()
  private val targetArg by argument("目标").optional()

  suspend fun UserCommandSender.use() {
    if (itemName == null) {
      sendMessage("请输入道具名, 如: /${KivotosCommand.primaryName} 使用 爱丽丝的礼物 1 爱丽丝")
      return
    }
    val template = ItemTemplateCache.getByName(itemName!!)
    if (template == null) {
      sendMessage("没有找到道具: $itemName")
      return
    }
    val count = countArg?.toIntOrNull() ?: 1
    if (count <= 0) {
      sendMessage("数量必须大于 0")
      return
    }
    val target = resolveTarget(targetArg)
    if (target == null && targetArg != null) {
      sendMessage("无法识别目标: $targetArg")
      return
    }

    val uid = userDocument().id
    when (val preview = UseService.preview(uid, template.id, count, target ?: UseTarget.None)) {
      is PreviewOutcome.TemplateMissing -> sendMessage("道具模板不存在")
      is PreviewOutcome.NoEffect -> sendMessage("${template.name} 没有配置使用行为")
      is PreviewOutcome.BadCount -> sendMessage(preview.reason)
      is PreviewOutcome.BadTarget -> sendMessage(preview.reason)
      is PreviewOutcome.Insufficient -> sendMessage("持有量不足: ${describeShortages(preview.shortages)}")
      is PreviewOutcome.Ok -> runConfirmAndApply(uid, template, count, target ?: UseTarget.None, preview.preview)
    }
  }

  private suspend fun UserCommandSender.runConfirmAndApply(
    uid: String,
    template: ItemTemplateDocument,
    count: Int,
    target: UseTarget,
    preview: UsePreview,
  ) {
    val traceId = uuid("use")
    val confirmData = "confirm:$traceId"
    val cancelData = "cancel:$traceId"

    val msg = tencentCustomMarkdown {
      h2("确认使用")
      +preview.summary
      if (preview.extraNotes.isNotEmpty()) {
        list { preview.extraNotes.forEach { +it } }
      }
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
    val sentMessage = sendMessage(msg)

    // 通过 filter 精确匹配本次 traceId 的两个按钮; 其他 (包括咖啡厅 Y/N) 不进入本 waiter,
    // 自然留给其他 nextButtonInteraction. 这样既不吞他人事件, 也不会被干扰.
    val next = runCatching {
      nextButtonInteraction(timeoutMillis = 25_000L) { filter ->
        filter.buttonData == confirmData || filter.buttonData == cancelData
      }
    }.getOrNull()

    val decision = when (next?.buttonData) {
      confirmData -> {
        next.accept()
        true
      }
      cancelData -> {
        next.accept()
        false
      }
      else -> null
    }

    sentMessage?.recall()

    when (decision) {
      null -> sendMessage("操作超时, 已取消")
      false -> sendMessage("已取消")
      true -> doApply(uid, template, count, target, traceId)
    }
  }

  private suspend fun UserCommandSender.doApply(
    uid: String,
    template: ItemTemplateDocument,
    count: Int,
    target: UseTarget,
    traceId: String,
  ) {
    val ctx = GrantContext(
      reason = "use.${template.id}",
      sourceType = "use",
      sourceId = template.id.toString(),
      idempotencyKey = traceId,
      traceId = traceId,
    )
    when (val outcome = UseService.apply(uid, template.id, count, target, ctx)) {
      is ApplyOutcome.Ok -> sendMessage(buildSuccessMessage(outcome.preview))
      is ApplyOutcome.TemplateMissing -> sendMessage("道具模板不存在")
      is ApplyOutcome.NoEffect -> sendMessage("${template.name} 没有配置使用行为")
      is ApplyOutcome.BadCount -> sendMessage(outcome.reason)
      is ApplyOutcome.BadTarget -> sendMessage(outcome.reason)
      is ApplyOutcome.Insufficient -> sendMessage("持有量不足: ${describeShortages(outcome.shortages)}")
      is ApplyOutcome.InventoryFailed -> sendMessage("库存校验失败: ${outcome.reason}")
      is ApplyOutcome.DuplicateRequest -> sendMessage("重复请求, 前次 traceId=${outcome.previousTraceId}")
      is ApplyOutcome.SideEffectPartialFailed -> sendMessage(
        buildPartialFailedMessage(outcome.preview, outcome.useLogId, traceId)
      )
    }
  }

  private fun UserCommandSender.buildSuccessMessage(preview: UsePreview) = tencentCustomMarkdown {
    +"使用成功"
    +preview.summary
    if (preview.extraNotes.isNotEmpty()) {
      list { preview.extraNotes.forEach { +it } }
    }
    at()
  }

  private fun UserCommandSender.buildPartialFailedMessage(
    preview: UsePreview,
    useLogId: String,
    traceId: String,
  ) = tencentCustomMarkdown {
    +"道具已扣减, 附加效果部分生效或未生效, 请联系管理员处理"
    // useLogId == "unknown" 表示 UseLog 未写入, 不应误导用户去查一个不存在的单号; 回退到 traceId
    val reference = if (useLogId == "unknown") "追踪号: $traceId (日志暂未落盘)" else "单号: $useLogId"
    +reference
    +preview.summary
    // extraNotes 此时带着 sideEffect 部分成功的产出描述, 让用户明确"哪些已到账"
    if (preview.extraNotes.isNotEmpty()) {
      +"已生效部分:"
      list { preview.extraNotes.forEach { +it } }
    }
    at()
  }

  private suspend fun resolveTarget(raw: String?): UseTarget? {
    if (raw.isNullOrBlank()) return null
    val studentName = normalizeStudentName(raw) ?: return null
    val student = StudentSchema.StudentCache.values.firstOrNull { it.name == studentName } ?: return null
    return UseTarget.Student(student.id.value)
  }

  private fun describeShortages(shortages: List<ItemDelta>): String =
    shortages.joinToString(", ") { "itemId=${it.itemId} 缺 ${it.amount}" }
}
