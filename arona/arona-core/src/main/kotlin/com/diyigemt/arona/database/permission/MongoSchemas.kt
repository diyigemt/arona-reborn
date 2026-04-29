package com.diyigemt.arona.database.permission

import com.diyigemt.arona.utils.currentDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

/**
 * Mongo 持久化 wrapper 类型与 mapper.
 *
 * 拓扑: domain/HTTP/SDK 层 (Policy / ContactRole / ContactMember / ContactDocument /
 * UserDocument / SimplifiedUserDocument) 保持字段名 `id`, 用于前端 JSON / golden test /
 * plugin SDK; Mongo 持久化层用本文件的 wrapper, 通过 `@SerialName("_id")` 把字段名映射到
 * BSON `_id`. 边界 mapper (`Domain.toMongo()` / `Mongo.toDomain()`) 集中在
 * Companion / Service / Endpoint 的 Mongo 入口处转换.
 *
 * `@BsonId` 是 transitional 注解, 与 `@SerialName("_id")` 双标. 反射 codec (C2-C4 仍接管)
 * 不认 `@SerialName` 只认 `@BsonId`; KotlinSerializerCodec (C5 切链后接管) 反过来认
 * `@SerialName` 且 4.11.1 拒绝 `@BsonId`. C5 切链同 commit 删除全部 `@BsonId`,
 * 保证任意单 commit revert 都 runtime 安全.
 */

@Serializable
internal data class MongoPolicy(
  @BsonId
  @SerialName("_id")
  val id: String,
  val name: String,
  val effect: PolicyNodeEffect,
  val rules: List<PolicyRoot>,
)

@Serializable
internal data class MongoContactRole(
  @BsonId
  @SerialName("_id")
  val id: String,
  val name: String,
)

@Serializable
internal data class MongoContactMember(
  @BsonId
  @SerialName("_id")
  val id: String,
  val name: String,
  val roles: List<String>,
  val config: Map<String, Map<String, String>> = mapOf(),
)

@Serializable
internal data class MongoContactDocument(
  @BsonId
  @SerialName("_id")
  val id: String,
  val contactName: String = "",
  val contactType: ContactType = ContactType.Group,
  val policies: List<MongoPolicy> = listOf(),
  val roles: List<MongoContactRole> = listOf(),
  val members: List<MongoContactMember> = listOf(),
  val registerTime: String = currentDateTime(),
  val config: Map<String, Map<String, String>> = mapOf(),
)

@Serializable
internal data class MongoUserDocument(
  @BsonId
  @SerialName("_id")
  val id: String,
  val username: String = "Arona用户$id",
  val unionOpenId: String = "",
  val qq: Long = 0L,
  val uid: List<String> = listOf(),
  val contacts: List<String> = listOf(),
  val policies: List<MongoPolicy> = listOf(),
  val config: Map<String, Map<String, String>> = mapOf(),
)

@Serializable
internal data class MongoSimplifiedUserDocument(
  @BsonId
  @SerialName("_id")
  val id: String,
  val username: String,
)

internal fun Policy.toMongo(): MongoPolicy = MongoPolicy(
  id = id,
  name = name,
  effect = effect,
  rules = rules,
)

internal fun MongoPolicy.toDomain(): Policy = Policy(
  id = id,
  name = name,
  effect = effect,
  rules = rules,
)

internal fun ContactRole.toMongo(): MongoContactRole = MongoContactRole(
  id = id,
  name = name,
)

internal fun MongoContactRole.toDomain(): ContactRole = ContactRole(
  id = id,
  name = name,
)

internal fun ContactMember.toMongo(): MongoContactMember = MongoContactMember(
  id = id,
  name = name,
  roles = roles,
  config = config,
)

internal fun MongoContactMember.toDomain(): ContactMember = ContactMember(
  id = id,
  name = name,
  roles = roles,
  config = config,
)

internal fun ContactDocument.toMongo(): MongoContactDocument = MongoContactDocument(
  id = id,
  contactName = contactName,
  contactType = contactType,
  policies = policies.map { it.toMongo() },
  roles = roles.map { it.toMongo() },
  members = members.map { it.toMongo() },
  registerTime = registerTime,
  config = config,
)

internal fun MongoContactDocument.toDomain(): ContactDocument = ContactDocument(
  id = id,
  contactName = contactName,
  contactType = contactType,
  policies = policies.map { it.toDomain() },
  roles = roles.map { it.toDomain() },
  members = members.map { it.toDomain() },
  registerTime = registerTime,
  config = config,
)

internal fun UserDocument.toMongo(): MongoUserDocument = MongoUserDocument(
  id = id,
  username = username,
  unionOpenId = unionOpenId,
  qq = qq,
  uid = uid,
  contacts = contacts,
  policies = policies.map { it.toMongo() },
  config = config,
)

internal fun MongoUserDocument.toDomain(): UserDocument = UserDocument(
  id = id,
  username = username,
  unionOpenId = unionOpenId,
  qq = qq,
  uid = uid,
  contacts = contacts,
  policies = policies.map { it.toDomain() },
  config = config,
)

internal fun MongoSimplifiedUserDocument.toDomain(): SimplifiedUserDocument = SimplifiedUserDocument(
  id = id,
  username = username,
)
