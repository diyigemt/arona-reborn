package com.diyigemt.arona.plana

import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value

/**
 * 插件配置, 落在 `config/com.diyigemt.arona.plana/config.yml`.
 *
 * 腾讯云相关项默认为空, 需要部署方在控制台填写后审查链路才会真正生效;
 * 缺失凭据时 [com.diyigemt.arona.plana.service.CosAuditService] 走 fail-open, 不会拦截消息.
 */
object Config : AutoSavePluginData("config") {
  /** 腾讯云 COS / 数据万象 SecretId. */
  val cosSecretId by value("")

  /** 腾讯云 COS / 数据万象 SecretKey. */
  val cosSecretKey by value("")

  /** COS 存储桶所在地域, 形如 `ap-shanghai`. */
  val region by value("ap-shanghai")

  /** 用于暂存待审图片的存储桶名, 形如 `example-1250000000`. */
  val bucket by value("")

  /** 暂存对象的 key 前缀, 实际 key 形如 `<prefix>/<yyyy-MM-dd>/<hash>.img`. */
  val cosPathPrefix by value("plana-audit")

  /** 涉黄分数严格大于该值视为命中(0..100). */
  var pornThreshold by value(93)

  /** 仅审查不小于该字节数的图片, 默认 80KB. */
  val minImageSizeBytes by value(80 * 1024)

  /** 单张图片审查(下载+上传+识别)的整体超时, 超时按 fail-open 放行. */
  val auditTimeoutMillis by value(8000L)
}
