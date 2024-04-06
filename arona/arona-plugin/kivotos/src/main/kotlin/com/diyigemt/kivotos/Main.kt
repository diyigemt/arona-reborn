package com.diyigemt.kivotos

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription

@Suppress("unused")
object Kivotos : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.kivotos",
    name = "kivotos",
    author = "diyigemt",
    version = "0.0.34",
    description = "hello world"
  )
) {
  override fun onLoad() {}
}
