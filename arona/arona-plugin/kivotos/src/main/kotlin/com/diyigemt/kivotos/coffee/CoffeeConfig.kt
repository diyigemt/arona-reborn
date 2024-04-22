package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@Serializable
data class CoffeeConfig(
  @EncodeDefault
  var inviteDoubleCheck: Boolean = true,
  @EncodeDefault
  var touchAfterInvite: Boolean = true,
) : PluginWebuiConfig() {
  override fun check() {
    TODO("Not yet implemented")
  }
}
