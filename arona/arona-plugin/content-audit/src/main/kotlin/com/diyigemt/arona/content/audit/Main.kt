package com.diyigemt.arona.content.audit

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.qcloud.cos.auth.BasicCOSCredentials

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.content.audit",
    name = "custom-menu",
    author = "diyigemt",
    version = "0.1.0",
    description = "内容审核"
  )
) {
  private val tencentClient by lazy {
    BasicCOSCredentials("", "")
  }
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<ContentAuditEvent> {

    }
  }
}
