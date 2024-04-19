package com.diyigemt.arona.webui.event

import com.diyigemt.arona.communication.event.Event

data class ContentAuditEvent(
  var value: String,
  var pass: Boolean = true,
  var message: String = "Normal",
  val level: Int? = null // 屏蔽等级 越低屏蔽越严格
) : Event

inline val ContentAuditEvent.isBlock
  get() = !pass

inline val ContentAuditEvent.isPass
  get() = pass
