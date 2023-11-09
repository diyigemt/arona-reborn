package com.diyigemt.arona.config

import kotlinx.coroutines.CoroutineScope

interface AutoSavePluginDataHolder : PluginDataHolder, CoroutineScope {
  val autoSaveIntervalMillis: LongRange
}
