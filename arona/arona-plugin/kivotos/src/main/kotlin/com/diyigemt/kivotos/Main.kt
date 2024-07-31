package com.diyigemt.kivotos

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription

const val KivotosRedisKey = "kivotos"

@Suppress("unused")
object Kivotos : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.kivotos",
    name = "kivotos",
    author = "diyigemt",
    version = "0.1.16",
    description = "hello world"
  )
) {
  override fun onLoad() {}
}
