@file:Suppress("unused", "PropertyName", "PrivatePropertyName")

package com.diyigemt.arona.config

import com.diyigemt.arona.config.internal.AbstractPluginData
import com.diyigemt.arona.config.internal.Value
import com.diyigemt.arona.utils.*
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException

open class AutoSavePluginData private constructor(
  // KEEP THIS PRIMARY CONSTRUCTOR FOR FUTURE USE: WE'LL SUPPORT SERIALIZERS_MODULE FOR POLYMORPHISM
  @Suppress("UNUSED_PARAMETER") primaryConstructorMark: Any?,
) : AbstractPluginData() {
  private lateinit var owner_: AutoSavePluginDataHolder
  private val autoSaveIntervalMillis_: LongRange get() = owner_.autoSaveIntervalMillis
  private lateinit var storage_: PluginDataStorage

  final override val saveName: String
    get() = _saveName

  @Suppress("JoinDeclarationAndAssignment") // bug
  private lateinit var _saveName: String

  constructor(saveName: String) : this(null) {
    _saveName = saveName
  }

  private fun logException(e: Throwable) {
    owner_.coroutineContext[CoroutineExceptionHandler]?.handleException(owner_.coroutineContext, e)
      ?.let { return }
    commandLineLogger.error(
      "An exception occurred when saving config ${this@AutoSavePluginData::class.qualifiedNameOrTip} " +
          "but CoroutineExceptionHandler not found in PluginDataHolder.coroutineContext for ${owner_::class.qualifiedNameOrTip}",
      e
    )
  }

  override fun onInit(owner: PluginDataHolder, storage: PluginDataStorage) {
    check(owner is AutoSavePluginDataHolder) { "owner must be AutoSavePluginDataHolder for AutoSavePluginData" }

    if (this::storage_.isInitialized) {
      check(storage == this.storage_) { "AutoSavePluginData is already initialized with one storage and cannot be reinitialized with another." }
    }

    this.storage_ = storage
    this.owner_ = owner

    owner_.coroutineContext[Job]?.invokeOnCompletion { save() }

    saverTask = owner_.launchTimedTask(
      intervalMillis = autoSaveIntervalMillis_.first,
      coroutineContext = CoroutineName("AutoSavePluginData.saver: ${this::class.qualifiedNameOrTip}")
    ) { save() }

    if (shouldPerformAutoSaveWheneverChanged()) {
      // 定时自动保存, 用于 kts 序列化的对象
      owner_.launch(CoroutineName("AutoSavePluginData.timedAutoSave: ${this::class.qualifiedNameOrTip}")) {
        while (isActive) {
          runIgnoreException<CancellationException> { delay(autoSaveIntervalMillis_.last) } ?: return@launch
          doSave()
        }
      }
    }
  }

  private var saverTask: TimedTask? = null

  protected open fun shouldPerformAutoSaveWheneverChanged(): Boolean {
    return true
  }

  final override fun onValueChanged(value: Value<*>) {
    debugLogger.debug { "onValueChanged: $value" }
    saverTask?.setChanged()
  }

  private fun save() {
    kotlin.runCatching {
      doSave()
    }.onFailure { e ->
      logException(e)
    }
  }

  private fun doSave() {
    debugLogger.debug { "doSave: ${this::class.qualifiedName}" }
    storage_.store(owner_, this)
  }
}
