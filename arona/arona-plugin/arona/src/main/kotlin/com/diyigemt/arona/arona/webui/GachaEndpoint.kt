package com.diyigemt.arona.arona.webui

import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import io.ktor.server.application.*
import io.ktor.util.pipeline.*

@Suppress("unused")
@AronaBackendEndpoint("/gacha")
object GachaEndpoint {
  @AronaBackendEndpointGet("/students")
  suspend fun PipelineContext<Unit, ApplicationCall>.getStudentList() {
    success(StudentSchema.all().toList())
  }
}