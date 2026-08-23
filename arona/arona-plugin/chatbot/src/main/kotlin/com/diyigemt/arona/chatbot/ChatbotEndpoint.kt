package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.permission.findContactPluginDocumentOrNull
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.errorPermissionDeniedMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointDelete
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.isSuperAdmin
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

/** 运营页写请求: status / tags / summary 都可选, 只改给了的字段. */
@Serializable
internal data class StickerEditRequest(
  val id: String = "",
  val status: String? = null,
  val tags: List<String>? = null,
  val summary: String? = null,
)

@Serializable
internal data class StickerIdRequest(val id: String = "")

@Serializable
internal data class GroupRef(val id: String, val name: String)

/** 列表 + 全部来源群 (做过滤下拉; 群名查不到时用 id). */
@Serializable
internal data class StickerListResp(val stickers: List<StickerView>, val groups: List<GroupRef>)

/**
 * 归一化运营编辑: status 必须是四个终态之一 (非法整体拒绝, 不静默丢字段); tags 去空白/去重/每个最多 [MAX_TAG_CHARS] 字/最多 [MAX_TAGS] 个,
 * 给了空列表就是清空; summary 最多 [MAX_SUMMARY_CHARS] 字. 三个字段都没给返回 null.
 */
internal fun normalizeStickerEdit(req: StickerEditRequest): StickerEdit? {
  val status = req.status?.trim()
  if (status != null && status !in StickerStatus.TERMINAL) return null
  val tags = req.tags?.map { it.trim().take(MAX_TAG_CHARS) }?.filter { it.isNotEmpty() }?.distinct()?.take(MAX_TAGS)
  val summary = req.summary?.trim()?.take(MAX_SUMMARY_CHARS)
  if (status == null && tags == null && summary == null) return null
  return StickerEdit(status, tags, summary)
}

private const val MAX_TAGS = 8
private const val MAX_TAG_CHARS = 20
private const val MAX_SUMMARY_CHARS = 200

/**
 * 图库运营页 (webui) 的后端, **只有主配置 `superAdminUid` 里的用户能用** (登录态由 core 根拦截器注入, [isSuperAdmin] 是 core 公开的判断).
 * 插件拿不到 core 的 HaltPipeline 做不了拦截器, 所以每个 handler 自己 [requireSuperAdmin], 不通过时已写响应, 直接 return.
 *
 * 表情是全局的 (`_id` = 内容哈希, 多群共见只累加 groupIds), 运营也是全局的: 列表默认全库, `gid` 只是过滤; 删除是物理删行 + 删文件.
 * tags/summary 只进选图打分, 不出站给用户, 不过内容审核.
 */
@Suppress("unused")
@AronaBackendEndpoint("/chatbot", withoutTransaction = true)
object ChatbotEndpoint {
  @AronaBackendEndpointGet("/sticker/list")
  suspend fun ApplicationCall.listStickers() {
    if (!requireSuperAdmin()) return
    val gid = request.queryParameters["gid"]?.takeIf { it.isNotBlank() }
    val groups = StickerStore.sourceGroupIds().map { id ->
      GroupRef(id, findContactPluginDocumentOrNull(id)?.contactName?.takeIf { it.isNotBlank() } ?: id)
    }
    success(StickerListResp(StickerStore.list(gid) { StickerFiles.publicUrl(it) }, groups))
  }

  @AronaBackendEndpointPost("/sticker/update")
  suspend fun ApplicationCall.updateSticker() {
    if (!requireSuperAdmin()) return
    val req = receiveJsonOrNull<StickerEditRequest>() ?: return badRequest()
    if (req.id.isBlank()) return badRequest()
    val edit = normalizeStickerEdit(req) ?: return badRequest()
    if (!StickerStore.update(req.id, edit)) return errorMessage("表情不存在、仍在分析, 或没有图片文件不能设为可用")
    success()
  }

  @AronaBackendEndpointDelete("/sticker")
  suspend fun ApplicationCall.deleteSticker() {
    if (!requireSuperAdmin()) return
    val req = receiveJsonOrNull<StickerIdRequest>() ?: return badRequest()
    if (req.id.isBlank()) return badRequest()
    val doc = StickerStore.delete(req.id) ?: return errorMessage("表情不存在或仍在分析")
    // Mongo 行已删再删文件: 删失败只留一个孤儿文件, 同图再入库会覆盖同名, 不影响功能.
    doc.getString("fileName")?.let { name ->
      runCatchingCancellable { StickerFiles.delete(name) }.onFailure { PluginMain.logger.warn("chatbot 删表情文件失败: $name", it) }
    }
    success()
  }

  private suspend fun ApplicationCall.requireSuperAdmin(): Boolean {
    if (isSuperAdmin) return true
    errorPermissionDeniedMessage()
    return false
  }
}
