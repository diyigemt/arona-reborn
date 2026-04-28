package com.diyigemt.arona

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription

object PluginMain : AronaPlugin(AronaPluginDescription(
  id = BuildConfig.ID,
  name = BuildConfig.NAME,
  author = BuildConfig.AUTHOR,
  version = BuildConfig.VERSION,
  description = BuildConfig.DESCRIPTION
)) {
  override fun onLoad() {

  }
}
