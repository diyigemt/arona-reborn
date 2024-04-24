package com.diyigemt.arona.arona.webui

import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuerySuspended
import com.diyigemt.arona.arona.database.student.StudentLimitType
import com.diyigemt.arona.arona.database.student.StudentRarity
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable

@Serializable
data class Student(
  val id: Int,
  val name: String,
  val limit: StudentLimitType,
  val rarity: StudentRarity,
)
fun StudentSchema.toStudent() = Student(
  id.value,
  name,
  limit,
  rarity
)
@Suppress("unused")
@AronaBackendEndpoint("/gacha", withoutTransaction = true)
object GachaEndpoint {
  @AronaBackendEndpointGet("/students")
  suspend fun PipelineContext<Unit, ApplicationCall>.getStudentList() {
    success(dbQuerySuspended {
      StudentSchema.all().map { it.toStudent() }.toList()
    })
  }
}
