package com.diyigemt.arona.arona.database.gacha

import com.diyigemt.arona.arona.database.Database
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.arona.tools.GachaTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.json

data class GachaPool(
  val name: String,
  val students: List<StudentSchema>,
  val isFes: Boolean = false,
)

@Database
object GachaPoolTable : IntIdTable(name = "GachaPool") {
  private val format = Json { ignoreUnknownKeys = true }
  val name = varchar("name", length = 50)
  val students = json("students", format, ListSerializer(Int.serializer()))
  val active = bool("active").clientDefault { false }
  val fes = bool("fes").clientDefault { false }
}

class GachaPoolSchema(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<GachaPoolSchema>(GachaPoolTable) {
    fun activePool(id: Int): Boolean {
      return dbQuery {
        val p = findById(id) ?: return@dbQuery false
        GachaPoolTable.update {
          it[active] = false
        }
        p.active = true
        GachaTool.CurrentPickupPool = p
        true
      }
    }

    fun currentActivePool(): GachaPoolSchema? {
      return dbQuery {
        GachaPoolSchema.find { GachaPoolTable.active eq true }.toList().firstOrNull()
      }
    }
  }

  var name by GachaPoolTable.name
  var students by GachaPoolTable.students
  var active by GachaPoolTable.active
  var fes by GachaPoolTable.fes
  fun toGachaPool() =
    GachaPool(
      name,
      dbQuery { StudentSchema.find { StudentTable.id inList students }.toList() },
      isFes = fes
    )

  override fun toString(): String {
    val students = toGachaPool().students.joinToString("\n") { it.toString() }
    return "GachaPool(name=$name, active=$active students=\n$students\n)"
  }
}
