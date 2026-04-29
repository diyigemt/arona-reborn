package com.diyigemt.arona.database.permission

import kotlinx.serialization.Serializable

/**
 * HTTP 暴露给前端的 Contact 视图 DTO. 字段名保持 `id` (与前端 TS interface 一致).
 *
 * 不直接走 Mongo BSON 编解码: aggregate 投影目标用 [MongoUserContactDocument] /
 * [MongoUserContactMemberDocument] (定义在 MongoSchemas.kt), 边界 `.toDomain()` 转换到本类型
 * 再交给 ktor ContentNegotiation 序列化为 JSON 输出.
 */
@Serializable
internal data class UserContactMemberDocument(
  val id: String, // 指向UserDocument.id
  val name: String,
  val roles: List<String>, // 指向ContactDocument.roles.id
)

@Serializable
internal data class UserContactDocument(
  val id: String,
  val contactName: String,
  val contactType: ContactType = ContactType.Group,
  val members: List<UserContactMemberDocument> = listOf(),
  val roles: List<ContactRole> = listOf(),
  // 仅 /contact?id= 完整接口才填充; 列表接口 /contacts 与 /manage-contacts 不下发, 防止群级敏感插件配置外泄.
  val config: Map<String, Map<String, String>>? = null,
)
