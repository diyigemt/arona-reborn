package com.diyigemt.arona.webui.event

import com.diyigemt.arona.communication.event.Event

data class ContentAuditEvent(
  var value: String,
  var pass: Boolean = true,
  var message: String = ""
) : Event

inline val ContentAuditEvent.isNotPass
  get() = !pass

inline val ContentAuditEvent.isPass
  get() = pass