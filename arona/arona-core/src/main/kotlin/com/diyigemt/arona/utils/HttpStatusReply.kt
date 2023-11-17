package com.diyigemt.arona.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

suspend fun PipelineContext<Unit, ApplicationCall>.badRequest() = context.respond(HttpStatusCode.BadRequest)
suspend fun PipelineContext<Unit, ApplicationCall>.unauthorized() = context.respond(HttpStatusCode.Unauthorized)
suspend fun PipelineContext<Unit, ApplicationCall>.forbidden() = context.respond(HttpStatusCode.Forbidden)
suspend fun PipelineContext<Unit, ApplicationCall>.internalServerError() =
  context.respond(HttpStatusCode.InternalServerError)

suspend inline fun <reified T : Any> PipelineContext<Unit, ApplicationCall>.success(data: T) =
  context.respond(ServerResponse(data))

suspend fun PipelineContext<Unit, ApplicationCall>.success() = context.respond(ServerResponse(null))

object HttpStatusCodeAsStringSerializer : KSerializer<HttpStatusCode> {
  override val descriptor = PrimitiveSerialDescriptor("HttpStatusCode", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: HttpStatusCode) = encoder.encodeInt(value.value)
  override fun deserialize(decoder: Decoder) = HttpStatusCode.fromValue(decoder.decodeInt())
}

@Serializable
data class ServerResponse<T>(
  @Serializable(with = HttpStatusCodeAsStringSerializer::class)
  val code: HttpStatusCode,
  val message: String,
  val data: T?,
) {
  constructor(data: T) : this(HttpStatusCode.OK, data)
  constructor(code: HttpStatusCode) : this(code, code.description, null)
  constructor(code: HttpStatusCode, data: T) : this(code, code.description, data)
}