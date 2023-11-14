package com.diyigemt.arona.example

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription

object PluginMain : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.example",
  name = "example",
  author = "diyigemt",
  version = "2.3.3",
  description = "hello world"
)) {
  override fun onLoad() {

  }
}
