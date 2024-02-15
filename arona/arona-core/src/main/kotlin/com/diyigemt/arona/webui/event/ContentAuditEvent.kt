package com.diyigemt.arona.webui.event

import com.diyigemt.arona.communication.event.Event

data class ContentAuditEvent(
  var value: String,
  var pass: Boolean = true,
  var message: String = "Normal"
) : Event

inline val ContentAuditEvent.isBlock
  get() = !pass

inline val ContentAuditEvent.isPass
  get() = pass