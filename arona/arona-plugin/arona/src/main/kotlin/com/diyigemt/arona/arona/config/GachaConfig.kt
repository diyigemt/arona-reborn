package com.diyigemt.arona.arona.config

import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.gacha.GachaPool
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

@Serializable
data class GachaRate(
  val r: Float = 78.5f,
  val sr: Float = 18.5f,
  val ssr: Float = 3.0f
)
@Serializable
data class GachaPickupRate(
  val sr: Float = 3.0f,
  val ssr: Float = 0.7f
)
@Serializable
data class CustomPool(
  val name: String,
  val pickup: List<Int>,
  val rate: GachaRate,
  val pickupRate: GachaPickupRate
) {
  fun toGachaPool() = dbQuery {
    GachaPool(
      name,
      StudentSchema.find { StudentTable.id inList pickup }.toList(),
      isFes = false
    )
  }
}
@Serializable
data class GachaConfig(
  val pools: MutableList<CustomPool> = mutableListOf()
) : PluginWebuiConfig() {
  override fun check() {
    while (pools.size > 5) {
      pools.removeLast()
    }
  }
}