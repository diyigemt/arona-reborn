package com.diyigemt.arona.webui.endpoints.admin

import com.diyigemt.arona.webui.endpoints.AronaBackendAdminEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.admin.AdminServiceProvider.service
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

object AdminServiceProvider {
  private val json = Json {
    ignoreUnknownKeys = true
  }
  private val map: Map<AdminActionType, AdminService<*>> by lazy {
    ReflectionUtil.scanInterfacePetObjectInstance(AdminService::class).associateBy { it.type }
  }
  suspend fun PipelineContext<Unit, ApplicationCall>.service(action: AdminAction0, body: String) {
    val obj = map[action.action] ?: return
    val data = json.decodeFromString(AdminAction.serializer((obj.deserializer)), body)
    obj::class.declaredFunctions.firstOrNull()?.callSuspend(obj, this, data.data)
  }
}

@Serializable
data class AdminAction0(
  val action: AdminActionType
)
@Serializable
data class AdminAction<T>(
  val action: AdminActionType,
  val data: T
)

abstract class AdminService<T> {
  abstract val type: AdminActionType
  abstract val deserializer: KSerializer<T>
}

@Serializable(with = AdminActionTypeAsStringSerializer::class)
enum class AdminActionType(val action: String) {
  NONE("none"),
  // 图片管理类
  UPDATE_IMAGE("imageUpdate"),
  REMOVE_IMAGE("imageRemove"),
  APPEND_ALIAS("imageAppendAlias"),
  REPLACE_ALIAS("imageReplaceAlias"),
  REMOVE_ALIAS("imageRemoveAlias"),
  QUERY_IMAGE_TABLE("imageQueryTable"),
  // action管理
  // action 卡池更新
  ACTION_POOL_UPDATE("poolUpdate"),
  // action 公告
  ACTION_ANNOUNCEMENT("announcement"),
  // 文件管理类
  QUERY_FILE_TABLE("fileQueryTable");

  companion object {
    private val ActionMap: Map<String, AdminActionType> = entries.associateBy { it.action }
    fun fromValue(value: String) = ActionMap[value] ?: NONE
  }
}

object AdminActionTypeAsStringSerializer : KSerializer<AdminActionType> {
  override val descriptor = PrimitiveSerialDescriptor("AdminActionType", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: AdminActionType) = encoder.encodeString(value.action)
  override fun deserialize(decoder: Decoder) = AdminActionType.fromValue(decoder.decodeString())
}

@AronaBackendAdminEndpoint
@AronaBackendEndpoint("/admin")
object AdminActionEndpoint {
  private val json = Json {
    ignoreUnknownKeys = true
  }

  @AronaBackendEndpointPost("/action")
  suspend fun PipelineContext<Unit, ApplicationCall>.adminAction() {
    val body = context.receiveText()
    val action = json.decodeFromString<AdminAction0>(body)
    service(action, body)
  }
}
