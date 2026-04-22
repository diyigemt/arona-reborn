package com.diyigemt.arona.database.permission

import codes.laurence.warden.atts.HasAtts
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.dot
import com.diyigemt.arona.database.matchedOne
import com.diyigemt.arona.database.memberPositional
import com.diyigemt.arona.database.membersIdPath
import com.diyigemt.arona.database.pluginConfigPath
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.createBaseAdminRole
import com.diyigemt.arona.database.permission.ContactRole.Companion.createBaseMemberRole
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.arona.webui.endpoints.aronaUser
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import com.mongodb.kotlin.client.coroutine.AggregateFlow
import com.mongodb.kotlin.client.coroutine.FindFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.bson.Document
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.codecs.pojo.annotations.BsonProperty
import org.bson.types.ObjectId

@Serializable
enum class ContactType {
  Private,
  PrivateGuild,
  Group,
  Guild,
}

abstract class PluginContactDocument : PluginVisibleData() {
  abstract val id: String
  abstract val contactName: String
  abstract val contactType: ContactType
  abstract var roles: List<ContactRole>
  abstract var members: List<ContactMember>
  fun findContactMemberOrNull(memberId: String) = members.firstOrNull { it.id == memberId }
  fun findContactMember(memberId: String) = members.first { it.id == memberId }

}

abstract class PluginContactMember : PluginVisibleData() {
  abstract val id: String // 指向UserDocument.id
  abstract val name: String
  abstract val roles: List<String>
}


@Serializable
data class ContactRole(
  @BsonId
  val id: String,
  val name: String,
) {
  companion object {
    internal const val DEFAULT_MEMBER_CONTACT_ROLE_ID = "role.default"
    internal const val DEFAULT_ADMIN_CONTACT_ROLE_ID = "role.admin"
    internal const val DEFAULT_SUPER_ROLE_ID = "role.super" // 只有机器人部署者才有的权限
    internal val PROTECTED_ROLE_ID = listOf(DEFAULT_SUPER_ROLE_ID)
    internal fun checkHasProtectedRoleId(p: Policy): Boolean {
      return p.rules.map { checkHasProtectedRoleIdNode(it) }.any { it }
    }

    private fun checkHasProtectedRoleIdNode(node: PolicyNode): Boolean {
      return (node.rule?.map { checkHasProtectedRoleIdRule(it) }?.any { it } ?: false) ||
          (node.children?.map { checkHasProtectedRoleIdNode(it) }?.any { it } ?: false)
    }

    private fun checkHasProtectedRoleIdRule(rule: PolicyRule): Boolean {
      val a = rule.type == PolicyRuleType.Subject && rule.key == "roles"
      val b = rule.value.split(",").any { it in PROTECTED_ROLE_ID }
      return a && b
    }

    fun createBaseAdminRole() = ContactRole(DEFAULT_ADMIN_CONTACT_ROLE_ID, "管理员")
    fun createBaseMemberRole() = ContactRole(DEFAULT_MEMBER_CONTACT_ROLE_ID, "普通成员")
    fun createRole(name: String) = ContactRole(
      "role.${uuid()}",
      name
    )
  }
}

@Serializable
data class ContactMember(
  @BsonId
  override val id: String, // 指向UserDocument.id
  override val name: String,
  override val roles: List<String>, // 指向ContactDocument.roles.id
  override val config: Map<String, Map<String, String>> = mapOf(),
) : PluginContactMember() {
  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: String,
  ) {
    commandLineLogger.warn("trigger contact member update config without cid, ignoring.")
  }
  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: String,
    cid: String,
  ) {
    ContactDocument.withCollection<ContactDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(
          idFilter(cid),
          Filters.eq(membersIdPath(), id)
        ),
        update = Updates.set(memberPositional(ContactMember::config, pluginId.toMongodbKey(), key), value)
      )
    }
  }
  companion object {
    data class ContactMemberPermissionSubject(
      val id: String,
      val roles: List<String>,
    ) : HasAtts()

    internal fun ContactMember.toPermissionSubject() = ContactMemberPermissionSubject(
      id,
      roles
    )
  }
}
@Serializable
internal data class SimplifiedContactDocument(
  @BsonProperty("_id")
  @BsonId
  val id: String,
  val contactName: String,
  val contactType: ContactType = ContactType.Group,
)
@Serializable
internal data class ContactDocument(
  @BsonId
  override val id: String,
  override val contactName: String = "",
  override val contactType: ContactType = ContactType.Group,
  var policies: List<Policy> = listOf(),
  override var roles: List<ContactRole> = listOf(),
  override var members: List<ContactMember> = listOf(),
  val registerTime: String = currentDateTime(),
  override val config: Map<String, Map<String, String>> = mapOf(), // 环境自定义的,插件专有的配置项
): PluginContactDocument() {

  /**
   * 检查member是否拥有这个群的role.admin权限
   */
  fun checkAdminPermission(userId: String) =
    members.any { it.roles.contains(DEFAULT_ADMIN_CONTACT_ROLE_ID) && it.id == userId }

  suspend fun updateContactDocumentName(name: String) {
    withCollection<ContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(ContactDocument::contactName.name, name)
      )
    }
  }

  /**
   * 不触 Mongo 的纯函数校验, 便于单元测试. 返回 [ContactDocumentUpdateException.Success] 表示输入合法.
   */
  internal fun validateMemberRoleUpdate(memberId: String, roleId: String): ContactDocumentUpdateException {
    if (findContactMemberOrNull(memberId) == null) {
      return ContactDocumentUpdateException.MemberNotFoundException(memberId)
    }
    if (roles.none { it.id == roleId }) {
      return ContactDocumentUpdateException.RoleNotFoundException(roleId)
    }
    return ContactDocumentUpdateException.Success()
  }

  suspend fun updateMemberRole(memberId: String, roleId: String): ContactDocumentUpdateException {
    when (val v = validateMemberRoleUpdate(memberId, roleId)) {
      is ContactDocumentUpdateException.Success -> Unit
      else -> return v
    }
    val res = withCollection<ContactDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(idFilter(id), Filters.eq(membersIdPath(), memberId)),
        update = Updates.addToSet(memberPositional(ContactMember::roles), roleId),
      )
    }
    return when {
      !res.matchedOne() -> ContactDocumentUpdateException.MemberNotFoundException(memberId)
      // matched 但 modified=0 表示 role 已存在; 调用方语义上视为成功.
      else -> ContactDocumentUpdateException.Success()
    }
  }

  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: String,
  ) {
    withCollection<ContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(pluginConfigPath(ContactDocument::config, pluginId, key), value)
      )
    }
  }

  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: String,
    cid: String
  ) {
    updatePluginConfig(pluginId, key, value)
  }

  companion object : DocumentCompanionObject {
    override val documentName = "Contact"

    suspend fun findContactDocumentByIdOrNull(id: String): ContactDocument? = withCollection {
      find(idFilter(id)).limit(1).firstOrNull()
    }

    suspend fun createContactDocument(id: String, type: ContactType = ContactType.Group): ContactDocument {
      val cd = ContactDocument(
        id,
        roles = listOf(createBaseAdminRole(), createBaseMemberRole()),
        policies = mutableListOf(createBaseContactAdminPolicy()).apply { addAll(createBaseMemberPolicy()) },
        contactType = type,
      )
      withCollection { insertOne(cd) }
      return cd
    }

    /**
     * 查询用户可见的群/频道; 先用 match 筛出当前用户所在集合, 再把 members 数组只保留当前用户自身,
     * 并剔除 config 等敏感字段. 调用方通过 reified [R] 指定结果 DTO, 保持 DTO 关注点留在 endpoint.
     */
    internal suspend inline fun <reified R : Any> findVisibleToUser(userId: String): List<R> =
      withCollection<ContactDocument, List<R>> {
        aggregate<R>(visibleToUserPipeline(userId)).toList()
      }

    internal suspend fun contacts(): List<SimplifiedContactDocument> {
      val filter = Aggregates.match(Filters.eq(ContactDocument::contactType.name, ContactType.Group.name))
      return withCollection<ContactDocument, List<SimplifiedContactDocument>> {
        aggregate<SimplifiedContactDocument>(
          listOf(
            filter,
            Aggregates.project(
              Projections.fields(
                Document("_id", 1),
                Document(ContactDocument::contactName.name, 1),
                Document(ContactDocument::contactType.name, 1)
              )
            )
          )
        ).toList()
      }
    }

    internal suspend fun guilds(): List<SimplifiedContactDocument> {
      val filter = Aggregates.match(Filters.eq(ContactDocument::contactType.name, ContactType.Guild.name))
      return withCollection<ContactDocument, List<SimplifiedContactDocument>> {
        aggregate<SimplifiedContactDocument>(
          listOf(
            filter,
            Aggregates.project(
              Projections.fields(
                Document("_id", 1),
                Document(ContactDocument::contactName.name, 1),
                Document(ContactDocument::contactType.name, 1)
              )
            )
          )
        ).toList()
      }
    }
  }
}

/**
 * 聚合管道: 匹配当前用户所在的群/频道, 并用 $filter 把 members 数组裁剪为当前用户自身,
 * 最后只投影列表页所需字段 (剔除 config, 防止群级敏感插件配置外泄).
 */
internal fun visibleToUserPipeline(userId: String): List<org.bson.conversions.Bson> = listOf(
  Aggregates.match(Filters.eq(membersIdPath(), userId)),
  Aggregates.project(
    Projections.fields(
      Document("_id", 1),
      Document(ContactDocument::contactName.name, 1),
      Document(ContactDocument::contactType.name, 1),
      Document(ContactDocument::roles.name, 1),
      Document(
        ContactDocument::members.name,
        Document(
          "\$filter",
          Document(
            mapOf(
              "input" to "\$${ContactDocument::members.name}",
              "as" to "mem",
              "cond" to Document("\$eq", listOf("\$\$mem._id", userId)),
            )
          )
        )
      ),
    )
  ),
  Aggregates.project(
    Projections.fields(
      Document("_id", 1),
      Document(ContactDocument::contactName.name, 1),
      Document(ContactDocument::contactType.name, 1),
      Document(ContactDocument::roles.name, 1),
      Document(membersIdPath(), 1),
      Document(ContactDocument::members dot ContactMember::name, 1),
      Document(ContactDocument::members dot ContactMember::roles, 1),
    )
  ),
)

internal sealed class ContactDocumentUpdateException {
  abstract val cause: String

  class Success : ContactDocumentUpdateException() {
    override val cause: String = ""
  }

  class MemberNotFoundException(memberId: String) : ContactDocumentUpdateException() {
    override val cause: String = "member: $memberId not found"
  }

  class RoleNotFoundException(roleId: String) : ContactDocumentUpdateException() {
    override val cause: String = "role: $roleId not found"
  }

  class PolicyNotFoundException(policyId: String) : ContactDocumentUpdateException() {
    override val cause: String = "policy: $policyId not found"
  }

  class InternalFailureException(override val cause: String) : ContactDocumentUpdateException()
}
