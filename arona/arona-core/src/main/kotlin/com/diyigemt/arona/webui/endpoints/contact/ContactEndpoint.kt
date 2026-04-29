@file:Suppress("unused")

package com.diyigemt.arona.webui.endpoints.contact

import com.diyigemt.arona.database.MongoWriteOutcome
import com.diyigemt.arona.database.classify
import com.diyigemt.arona.database.dot
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.matchedOne
import com.diyigemt.arona.database.memberPositional
import com.diyigemt.arona.database.membersIdPath
import com.diyigemt.arona.database.modifiedOne
import com.diyigemt.arona.database.permission.*
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.PROTECTED_ROLE_ID
import com.diyigemt.arona.database.permission.Policy.Companion.PROTECTED_POLICY_ID
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.permission.abac.cache.PolicyCompileCache
import com.diyigemt.arona.utils.*
import com.diyigemt.arona.webui.endpoints.*
import com.diyigemt.arona.webui.endpoints.plugin.PluginPreferenceResp
import com.diyigemt.arona.webui.event.auditOrAllow
import com.diyigemt.arona.webui.event.isBlock
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import com.diyigemt.arona.webui.plugins.HaltPipeline
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import org.bson.Document

@Serializable
internal data class IdBody(
  val id: String,
)

@Serializable
internal data class ContactUpdateReq(
  val id: String,
  val contactName: String,
)

@Serializable
internal data class ContactMemberUpdateReq(
  val id: String,
  val name: String,
  val roles: List<String>,
)

@Serializable
internal data class ContactRoleCreateReq(
  val id: String,
  val name: String,
)

@AronaBackendEndpoint("/contact")
internal object ContactEndpoint {
  private val NoRequestContactIdPath = setOf("/contacts", "/manage-contacts")
  private val ApplicationCall.contactId
    get() = request.queryParameters["id"] ?: parameters["id"]!!

  private val ContextContactAttrKey = AttributeKey<ContactDocument>("contact")

  private var ApplicationCall._contact: ContactDocument?
    get() = attributes.getOrNull(ContextContactAttrKey)
    set(value) = attributes.put(ContextContactAttrKey, value as ContactDocument)

  private val ApplicationCall.contact: ContactDocument
    get() = attributes[ContextContactAttrKey]

  @AronaBackendRouteInterceptor(priority = RouteInterceptorPriority.LOW)
  suspend fun ApplicationCall.contactIdInterceptor() {
    val method = request.httpMethod
    val path = request.path()
    if (method == HttpMethod.Get && NoRequestContactIdPath.none { path.endsWith(it) }) {
      if ((parameters["id"] ?: request.queryParameters["id"]) == null) {
        errorMessage("缺少请求参数")
        throw HaltPipeline()
      }
    }
    // 检查权限?
    if (method == HttpMethod.Get) {
      parameters["id"] ?: request.queryParameters["id"]
    } else {
      parameters["id"] ?: receiveJsonOrNull<IdBody>()?.id
    }?.let {
      findContactDocumentByIdOrNull(it)
    }?.run {
      _contact = this
    }
    if (requiresContactAdmin(path)) {
      if (_contact != null) {
        if (!contact.checkAdminPermission(aronaUser.id)) {
          errorPermissionDeniedMessage()
          throw HaltPipeline()
        }
      } else {
        errorMessage("群/频道信息查询失败")
        throw HaltPipeline()
      }
    }
  }

  @AronaBackendEndpointGet("/manage-contacts")
  suspend fun ApplicationCall.manageContacts() {
    return success(
      getContacts().filter { c ->
        c.members.first { m -> m.id == aronaUser.id }.roles.any { it == DEFAULT_ADMIN_CONTACT_ROLE_ID }
      }
    )
  }

  /**
   * 获取用户所有群/频道列表
   */
  @AronaBackendEndpointGet("/contacts")
  suspend fun ApplicationCall.contacts() {
    return success(getContacts())
  }

  suspend fun ApplicationCall.getContacts(): List<UserContactDocument> =
    ContactDocument.findVisibleToUser(aronaUser.id)

  /**
   * 根据id获取一个contact的所有信息(仅管理员
   */
  @AronaBackendEndpointGet("/contact")
  suspend fun ApplicationCall.contact() {
    success(contact)
  }

  private fun ContactMember.toSimply() = UserContactMemberDocument(id, name, roles)

  /**
   * 根据id获取一个contact的基本信息 用户自定义名称、群名、成员列表
   */
  @AronaBackendEndpointGet("/contact-base")
  suspend fun ApplicationCall.contactBase() {
    success(
      UserContactDocument(
        contactId,
        contact.contactName,
        contact.contactType,
        contact.members.map { it.toSimply() },
        contact.roles
      )
    )
  }

  /**
   * 根据id更新contact的基本信息
   */
  @AronaBackendEndpointPost("/contact-basic")
  suspend fun ApplicationCall.updateContactBasic() {
    val data = receive<ContactUpdateReq>()
    ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(contact.id),
        update = Updates.set(ContactDocument::contactName.name, data.contactName)
      )
    }
    success()
  }

  /**
   * 获取某个群/频道自定义的角色列表
   */
  @AronaBackendEndpointGet("/roles")
  suspend fun ApplicationCall.contactRoles() {
    return success(contact.roles)
  }

  /**
   * 获取某个群/频道成员列表
   */
  @AronaBackendEndpointGet("/members")
  suspend fun ApplicationCall.contactMembers() {
    return success(contact.members.map { it.toSimply() })
  }

  /**
   * 获取某个群/频道策略列表
   */
  @AronaBackendEndpointGet("/{id}/policies")
  suspend fun ApplicationCall.contactPolicies() {
    return success(contact.policies)
  }

  /**
   * 更新成员信息.
   * 普通成员只能改自己的 name; 仅管理员可改 roles. 任何分支都不允许写入 super 角色.
   * 见 [resolveMemberUpdate] 的决策表.
   */
  @AronaBackendEndpointPut("/{id}/member")
  suspend fun ApplicationCall.updateMember() {
    val data = receiveJsonOrNull<ContactMemberUpdateReq>() ?: return badRequest()
    val target = contact.findContactMemberOrNull(data.id)
    val decision = resolveMemberUpdate(contact, aronaUser.id, target, data)
    val updateRoles = when (decision) {
      MemberUpdateDecision.Deny -> return errorPermissionDeniedMessage()
      is MemberUpdateDecision.Allow -> decision.updateRoles
    }
    val update = if (updateRoles) {
      Updates.combine(
        Updates.set(memberPositional(ContactMember::name), data.name),
        Updates.set(memberPositional(ContactMember::roles), data.roles),
      )
    } else {
      Updates.set(memberPositional(ContactMember::name), data.name)
    }
    val matched = ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(
          idFilter(contact.id),
          Filters.eq(membersIdPath(), data.id),
        ),
        update = update,
      )
    }.matchedOne()
    return if (matched) success() else internalServerError()
  }

  /**
   * 创建角色
   */
  @AronaBackendEndpointPost("/{id}/role")
  suspend fun ApplicationCall.createRole() {
    val data = receiveJsonOrNull<ContactRoleCreateReq>() ?: return badRequest()
    return if (
      ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
        updateOne(
          filter = idFilter(contact.id),
          update = Updates.addToSet(
            ContactDocument::roles.name,
            ContactRole.createRole(data.name).toMongo()
          )
        )
      }.modifiedOne()
    ) success()
    else internalServerError()
  }

  /**
   * 删除角色
   */
  @AronaBackendEndpointDelete("/{id}/role")
  suspend fun ApplicationCall.deleteRole() {
    val data = receiveJsonOrNull<IdBody>() ?: return badRequest()
    if (data.id in PROTECTED_ROLE_ID) {
      return badRequest()
    }
    // TODO
    // 删除 contact document 的 roles
    // 删除所有 member 的 role
    // 删除 policy 与 role 有关的
    internalServerError()
  }

  /**
   * 更新角色
   */
  @AronaBackendEndpointPut("/{id}/role")
  suspend fun ApplicationCall.updateRole() {
    val data = receiveJsonOrNull<ContactRoleCreateReq>() ?: return badRequest()
    if (data.id in PROTECTED_ROLE_ID) {
      return badRequest()
    }
    return if (
      ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
        updateOne(
          filter = Filters.and(
            idFilter(contact.id),
            Filters.eq(ContactDocument::roles.dot("_id"), data.id)
          ),
          update = Updates.set(ContactDocument::roles.dot("\$", ContactRole::name.name), data.name),
        )
      }.classify() != MongoWriteOutcome.NotMatched
    ) success()
    else internalServerError()
  }

  /**
   * 获取策略
   */
  @AronaBackendEndpointGet("/{id}/policy")
  suspend fun ApplicationCall.policy() {
    val id = request.queryParameters["pid"] ?: return badRequest()
    return contact.policies.firstOrNull { it.id == id }?.let {
      success(it)
    } ?: success()
  }

  private fun checkPolicy(policy: Policy): Boolean {
    if (policy.id in PROTECTED_POLICY_ID || ContactRole.checkHasProtectedRoleId(policy)) {
      return true
    }
    return policy.rules.isEmpty() || policy.rules.any { it.rule.isNullOrEmpty() && it.children.isNullOrEmpty() }
  }

  /**
   * 更新策略
   */
  @AronaBackendEndpointPut("/{id}/policy")
  suspend fun ApplicationCall.updatePolicy() {
    val policy = receiveJsonOrNull<Policy>() ?: return badRequest()
    if (checkPolicy(policy)) return badRequest()
    return if (
      ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
        updateOne(
          filter = Filters.and(
            idFilter(contact.id),
            Filters.eq(ContactDocument::policies.dot("_id"), policy.id)
          ),
          update = Updates.set(ContactDocument::policies.dot("\$"), policy.toMongo())
        )
      }.classify() != MongoWriteOutcome.NotMatched
    ) {
      PolicyCompileCache.invalidateById(policy.id)
      success(policy.id)
    }
    else internalServerError()
  }

  /**
   * 创建策略
   */
  @AronaBackendEndpointPost("/{id}/policy")
  suspend fun ApplicationCall.createPolicy() {
    val data = receiveJsonOrNull<Policy>() ?: return badRequest()
    if (contact.policies.any { it.id == data.id }) {
      return badRequest()
    }
    if (checkPolicy(data)) return badRequest()
    val policy = Policy(
      Policy.randomPolicyId(),
      data.name,
      data.effect,
      data.rules
    )
    return if (
      ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
        updateOne(
          filter = idFilter(contact.id),
          update = Updates.push(ContactDocument::policies.name, policy.toMongo())
        )
      }.modifiedOne()
    ) success(policy.id)
    else internalServerError()
  }

  /**
   * 删除策略
   */
  @AronaBackendEndpointDelete("/{id}/policy")
  suspend fun ApplicationCall.deletePolicy() {
    val id = receiveJsonOrNull<IdBody>()?.id ?: return badRequest()
    if (id in PROTECTED_POLICY_ID) {
      return badRequest()
    }
    return if (
      ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
        updateOne(
          filter = idFilter(contact.id),
          update = Document(
            "\$pull",
            Document(
              ContactDocument::policies.name,
              Document("_id", id)
            )
          )
        )
      }.modifiedOne()
    ) {
      PolicyCompileCache.invalidateById(id)
      success()
    }
    else internalServerError()
  }

  /**
   * 保存群插件配置
   */
  @AronaBackendEndpointPost("/{id}/plugin/preference")
  suspend fun ApplicationCall.savePreference() {
    val obj = kotlin.runCatching {
      receive<PluginPreferenceResp>()
    }.onFailure {
      return badRequest()
    }.getOrThrow()
    val value = PluginWebuiConfigRecorder.checkDataSafety(obj) ?: return badRequest()
    val ev = auditOrAllow(value)
    if (ev?.isBlock == true) return errorMessage("内容审核失败: ${ev.message}")
    contact.updatePluginConfig(
      obj.id,
      obj.key,
      value
    )
    return success()
  }

  /**
   * 获取用户自定义的群插件配置
   */
  @AronaBackendEndpointGet("/{id}/member/plugin/member-preference")
  suspend fun ApplicationCall.getMemberPreference() {
    val pid = request.queryParameters["pid"] ?: return badRequest()
    val key = request.queryParameters["key"] ?: return badRequest()
    val member = contact.findContactMemberOrNull(aronaUser.id) ?: return internalServerError()
    member.readPluginConfigStringOrNull(pid, key)?.also {
      return success(it)
    }
    return success("")
  }

  /**
   * 保存用户自定义的群插件配置
   */
  @AronaBackendEndpointPost("/{id}/member/plugin/member-preference")
  suspend fun ApplicationCall.saveMemberPreference() {
    val obj = kotlin.runCatching { receive<PluginPreferenceResp>() }.getOrNull() ?: return badRequest()
    val value = PluginWebuiConfigRecorder.checkDataSafety(obj) ?: return badRequest()
    val ev = auditOrAllow(value)
    if (ev?.isBlock == true) return errorMessage("内容审核失败: ${ev.message}")
    contact.findContactMemberOrNull(aronaUser.id)?.also {
      it.updatePluginConfig(
        obj.id,
        obj.key,
        value,
        contact.id,
      )
      return success()
    }
    return internalServerError()
  }
}
