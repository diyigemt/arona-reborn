package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.permission.ContactType
import com.diyigemt.arona.database.permission.findContactPluginDocumentOrNull
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.errorPermissionDeniedMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointDelete
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.pluginUser
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

/** 运营页写请求: status / tags / summary 都可选, 只改给了的字段. */
@Serializable
internal data class StickerEditRequest(
  val gid: String = "",
  val id: String = "",
  val status: String? = null,
  val tags: List<String>? = null,
  val summary: String? = null,
)

@Serializable
internal data class StickerRef(val gid: String = "", val id: String = "")

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
 * 图库运营页 (webui) 的后端. 登录态由 core 根拦截器注入 ([pluginUser]); 群管理员资格每个 handler 自己查 —— 插件拿不到 core 的 HaltPipeline,
 * 做不了拦截器, 所以 [requireGroupAdmin] 不通过时自己写响应, 调用方直接 return.
 *
 * 一张图的 status/tags/summary 是全局的 (`_id` = 内容哈希, 多群共见只累加 groupIds): 任一来源群的管理员都能改.
 * 通过 = 其它见过它的群也能用 (与 stickerAutoApprove 同义, 不跨群泄露, 因为对方群本来就见过); 拒绝/隐藏只是让图不再被选, 错了可改回.
 * 不要求"同时管理全部来源群": 那会让多群共见的图永远没人能审. 删除是唯一不可逆的操作, 所以按群解绑, 最后一个群才真删 (见 [StickerStore.unlink]).
 * tags/summary 只进选图打分, 不出站给用户, 不过内容审核.
 */
@Suppress("unused")
@AronaBackendEndpoint("/chatbot", withoutTransaction = true)
object ChatbotEndpoint {
  @AronaBackendEndpointGet("/sticker/list")
  suspend fun ApplicationCall.listStickers() {
    val gid = request.queryParameters["gid"].orEmpty()
    if (!requireGroupAdmin(gid)) return
    success(StickerStore.list(gid) { StickerFiles.publicUrl(it) })
  }

  @AronaBackendEndpointPost("/sticker/update")
  suspend fun ApplicationCall.updateSticker() {
    val req = receiveJsonOrNull<StickerEditRequest>() ?: return badRequest()
    if (!requireGroupAdmin(req.gid)) return
    val edit = normalizeStickerEdit(req) ?: return badRequest()
    if (!StickerStore.update(req.id, req.gid, edit)) return errorMessage("表情不存在或仍在分析")
    success()
  }

  /** 从本群移除; 只有没别的群见过它时才连文件一起删, 见 [StickerStore.unlink]. */
  @AronaBackendEndpointDelete("/sticker")
  suspend fun ApplicationCall.deleteSticker() {
    val req = receiveJsonOrNull<StickerRef>() ?: return badRequest()
    if (!requireGroupAdmin(req.gid)) return
    val removed = StickerStore.unlink(req.id, req.gid) ?: return errorMessage("表情不存在或仍在分析")
    // Mongo 行已删再删文件: 删失败只留一个孤儿文件, 同图再入库会覆盖同名, 不影响功能.
    removed.fileToDelete?.let { name ->
      runCatchingCancellable { StickerFiles.delete(name) }.onFailure { PluginMain.logger.warn("chatbot 删表情文件失败: $name", it) }
    }
    success()
  }

  /** gid 是请求给的, 必须验证确有此群且当前用户是其管理员. 不通过时已写出响应. */
  private suspend fun ApplicationCall.requireGroupAdmin(gid: String): Boolean {
    if (gid.isBlank()) {
      badRequest()
      return false
    }
    val group = findContactPluginDocumentOrNull(gid)?.takeIf { it.contactType == ContactType.Group }
    if (group == null) {
      errorMessage("群不存在")
      return false
    }
    if (!group.checkAdminPermission(pluginUser.id)) {
      errorPermissionDeniedMessage()
      return false
    }
    return true
  }
}
