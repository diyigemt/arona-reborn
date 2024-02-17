package com.diyigemt.arona.content.audit

import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.model.ciModel.auditing.TextAuditingRequest
import com.qcloud.cos.region.Region
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.content.audit",
    name = "custom-menu",
    author = "diyigemt",
    version = "0.1.1",
    description = "内容审核"
  )
) {
  private val tencentClient by lazy {
    COSClient(
      BasicCOSCredentials(AuditConfig.secretId, AuditConfig.secretKey),
      ClientConfig(Region("ap-shanghai"))
    )
  }

  @OptIn(ExperimentalEncodingApi::class)
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<ContentAuditEvent> {
      withTimeout(AuditConfig.auditTimeout) {
        val req = TextAuditingRequest().apply {
          bucketName = "drift-text-analysis-1309038720"
          input.content = Base64.encode(it.value.encodeToByteArray())
        }
        val resp = tencentClient.createAuditingTextJobs(req).jobsDetail
        if (resp.label != "Normal") {
          listOfNotNull(
            resp.pornInfo,
            resp.terroristInfo,
            resp.politicsInfo,
            resp.adsInfo,
            resp.abuseInfo,
            resp.illegalInfo,
            resp.teenagerInfo,
            resp.meaninglessInfo
          ).firstOrNull { info ->
            (info.score.toIntOrNull() ?: 0) >= AuditConfig.threshold
          }?.also { _ ->
            it.pass = false
            it.message = resp.label
          }
        }
      }
    }
  }
}

@Suppress("unused")
class AuditThresholdChangeCommand : CommandLineSubCommand, CliktCommand(
  name = "auditThreshold", help = "改变内容审查阈值",
  invokeWithoutSubcommand = true
) {
  private val threshold by argument().int()
  override fun run() {
    if (threshold !in (0 .. 100)) {
      error("必须在0至100之间")
    }
    AuditConfig.threshold = threshold
  }

}

object AuditConfig : AutoSavePluginData("config") {
  val secretId by value("")
  val secretKey by value("")
  val auditTimeout by value(5000L)
  var threshold by value(93)
}
