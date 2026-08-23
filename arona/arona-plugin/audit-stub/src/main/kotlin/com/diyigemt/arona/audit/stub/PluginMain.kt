package com.diyigemt.arona.audit.stub

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.webui.event.ContentAuditEvent
import kotlinx.coroutines.delay

/**
 * sandbox 专用审核桩 (不要和 content-audit 同时装, 也不要进生产):
 *  - 文本含 `BLOCKME`  -> pass=false, audited=true   (AUDIT_BLOCK)
 *  - 文本含 `NOAUDIT`  -> 什么都不做, audited 保持 false (AUDIT_UNAVAILABLE: "没人审")
 *  - 文本含 `SLOWME`   -> 先睡 5s 再置位 (超过 chatbot 3s 审核超时 -> AUDIT_UNAVAILABLE)
 *  - 其它              -> audited=true, 通过
 */
@Suppress("unused")
object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION,
  ),
) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<ContentAuditEvent> {
      logger.info("[audit-stub] value={}", it.value.take(60))
      when {
        "NOAUDIT" in it.value -> return@subscribeAlways
        "SLOWME" in it.value -> delay(5_000)
      }
      if ("BLOCKME" in it.value) {
        it.pass = false
        it.message = "stub-block"
      }
      it.audited = true
    }
  }
}
