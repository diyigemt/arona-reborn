package com.diyigemt.arona.config

import kotlinx.serialization.KSerializer

abstract class PluginData {
  private val serializer: MutableList<KSerializer<*>> = mutableListOf()
}
