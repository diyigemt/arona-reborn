package com.diyigemt.kivotos.inventory.use

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

/**
 * "使用道具" 的独立审计流水.
 *
 * 与 [com.diyigemt.kivotos.inventory.InventoryAuditLog] 拆分的理由:
 *  - 库存审计只关心"数值发生什么变化", 已被 InventoryService 在 execute 成功时原子写入
 *  - UseLog 关心"使用行为是否成功", 尤其要把 side-effect 失败、幂等重入等非库存状态沉淀下来
 *  - 两张表可以用同一 traceId 串起来, 运维排障时各自查各自的维度
 *
 * [targetDescriptor] 是面向审计的弱类型描述 (e.g. "student:10000" / "none"), 避免多态序列化成本.
 */
/**
 * [details] 承载结构化现场. 相对早期只有自由文本 [error], 这里给出明确可机读字段, 典型用例:
 *  - 礼包掷骰结果: `"rolled"` → `"1000000:100,2000001:1"` 便于脚本补偿
 *  - 混合发奖分项: `"granted_stackable"` / `"granted_equipment"` / `"failed_equipment"`
 *  - 其他业务自定义键
 * 弱类型 Map 比每种 effect 扩 schema 容易, 同时比塞进 [error] 字符串可检索.
 */
@Serializable
data class UseLog(
  @BsonId
  val id: String = uuid("inv.use"),
  val uid: String,
  val itemId: UInt,
  val count: Int,
  val targetDescriptor: String,
  val effectKey: String,
  val traceId: String,
  val status: UseLogStatus,
  val phase: String,
  val error: String? = null,
  val details: Map<String, String> = emptyMap(),
  val ts: String = currentDateTime(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "UseLog"
    override val database get() = KivotosMongoDatabase.instance

    suspend fun record(
      uid: String,
      itemId: UInt,
      count: Int,
      target: UseTarget,
      effectKey: String,
      traceId: String,
      status: UseLogStatus,
      phase: String,
      error: String? = null,
      details: Map<String, String> = emptyMap(),
    ): String {
      val doc = UseLog(
        uid = uid,
        itemId = itemId,
        count = count,
        targetDescriptor = describeTarget(target),
        effectKey = effectKey,
        traceId = traceId,
        status = status,
        phase = phase,
        error = error,
        details = details,
      )
      withCollection<UseLog, Unit> { insertOne(doc) }
      return doc.id
    }

    private fun describeTarget(target: UseTarget): String = when (target) {
      is UseTarget.None -> "none"
      is UseTarget.Student -> "student:${target.id}"
      is UseTarget.EquipmentInstance -> "equipment:${target.id}"
    }
  }
}
