package com.diyigemt.kivotos

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.kivotos.inventory.ItemTemplateCache
import com.diyigemt.kivotos.inventory.KivotosInventoryIndexes
import com.diyigemt.kivotos.inventory.loot.LootTableCache
import com.diyigemt.kivotos.inventory.use.ItemEffectRegistry
import com.diyigemt.kivotos.inventory.use.effect.CurrencyGrantEffect
import com.diyigemt.kivotos.inventory.use.effect.FavorGiftEffect
import com.diyigemt.kivotos.inventory.use.effect.LootBoxEffect

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
  override fun onLoad() {
    // effect 注册是纯内存操作, 与 Mongo/Redis 无关, 放同步路径, 保证指令加载前注册完成
    ItemEffectRegistry.register(CurrencyGrantEffect)
    ItemEffectRegistry.register(FavorGiftEffect)
    ItemEffectRegistry.register(LootBoxEffect)
    runSuspend {
      KivotosInventoryIndexes.ensure()
      // 预热模板: 资源 cap 依赖模板, 先载入避免首个用户仓库被种子成 DEFAULT_RESOURCE_CAP
      ItemTemplateCache.refresh()
      // LootTable 与模板同级配置, 装载期校验完整性, 坏表被跳过而非拖垮启动
      LootTableCache.refresh()
    }
  }
}
