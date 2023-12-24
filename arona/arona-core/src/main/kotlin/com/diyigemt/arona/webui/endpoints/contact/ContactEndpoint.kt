@file:Suppress("unused")

package com.diyigemt.arona.webui.endpoints.contact

import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.*
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole
import com.diyigemt.arona.database.permission.Policy.Companion.PROTECTED_POLICY_ID
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.*
import com.diyigemt.arona.webui.endpoints.*
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.bson.Document
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
internal data class UserContactDocument(
  @BsonId
  val id: String,
  val contactName: String,
  val contactType: ContactType = ContactType.Group,
  val members: List<ContactMember> = listOf(),
  val roles: List<ContactRole> = listOf(),
  val config: Map<String, Map<String, String>> = mapOf()
)

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
  private val NoRequestContactIdPath = listOf("/contacts")
  private val RequestContactAdminPath = listOf(
    "/contact", "/contact-basic", "/roles", "/members", "/policies",
    "/role", "/policy"
  )
  private val PipelineContext<Unit, ApplicationCall>.contactId
    get() = request.queryParameters["id"] ?: context.parameters["id"]!!

  private val ContextContactAttrKey = AttributeKey<ContactDocument>("contact")

  private var PipelineContext<Unit, ApplicationCall>._contact: ContactDocument?
    get() = context.attributes.getOrNull(ContextContactAttrKey)
    set(value) = context.attributes.put(ContextContactAttrKey, value as ContactDocument)

  private val PipelineContext<Unit, ApplicationCall>.contact: ContactDocument
    get() = context.attributes[ContextContactAttrKey]

  @AronaBackendRouteInterceptor(priority = RouteInterceptorPriority.LOW)
  suspend fun PipelineContext<Unit, ApplicationCall>.contactIdInterceptor() {
    val method = context.request.httpMethod
    val path = context.request.path()
    if (method == HttpMethod.Get && NoRequestContactIdPath.none { path.endsWith(it) }) {
      if ((context.parameters["id"] ?: request.queryParameters["id"]) == null) {
        errorMessage("缺少请求参数")
        return finish()
      }
    }
    // 检查权限?
    if (method == HttpMethod.Get) {
      context.parameters["id"] ?: request.queryParameters["id"]
    } else {
      context.parameters["id"] ?: context.receiveJsonOrNull<IdBody>()?.id
    }?.let {
      findContactDocumentByIdOrNull(it)
    }?.run {
      _contact = this
    }
    if (RequestContactAdminPath.any { path.endsWith(it) }) {
      if (_contact != null) {
        if (!contact.checkAdminPermission(aronaUser.id)) {
          errorPermissionDeniedMessage()
          return finish()
        }
      } else {
        errorMessage("群/频道信息查询失败")
        return finish()
      }
    }
  }

  /**
   * 获取用户所有群/频道列表
   */
  @AronaBackendEndpointGet("/contacts")
  suspend fun PipelineContext<Unit, ApplicationCall>.contacts() {
    val filter = Aggregates.match(Filters.eq("${ContactDocument::members.name}._id", aronaUser.id))
    val projection = Aggregates.project(
      Projections.fields(
        Document("_id", 1),
        Document(ContactDocument::contactName.name, 1),
        Document(ContactDocument::contactType.name, 1),
        Document(ContactDocument::roles.name, 1),
        Document(ContactDocument::config.name, 1),
        Document(
          ContactDocument::members.name,
          Document(
            "\$filter",
            Document(
              mapOf(
                "input" to "\$${ContactDocument::members.name}",
                "as" to "mem",
                "cond" to Document(
                  "\$eq", listOf("\$\$mem._id", aronaUser.id)
                )
              )
            )
          )
        ),
      )
    )
    val manageContacts = ContactDocument.withCollection<ContactDocument, List<UserContactDocument>> {
      aggregate<UserContactDocument>(listOf(filter, projection)).toList()
    }
    success(manageContacts)
  }

  /**
   * 根据id获取一个contact的所有信息(仅管理员
   */
  @AronaBackendEndpointGet("/contact")
  suspend fun PipelineContext<Unit, ApplicationCall>.contact() {
    success(contact)
  }

  /**
   * 根据id获取一个contact的基本信息 用户自定义名称、群名、成员列表
   */
  @AronaBackendEndpointGet("/contact-base")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactBase() {
    success(
      UserContactDocument(
        contactId,
        contact.contactName,
        contact.contactType,
        contact.members,
        contact.roles
      )
    )
  }

  /**
   * 根据id更新contact的基本信息
   */
  @AronaBackendEndpointPost("/contact-basic")
  suspend fun PipelineContext<Unit, ApplicationCall>.updateContactBasic() {
    val data = context.receive<ContactUpdateReq>()
    ContactDocument.withCollection<ContactDocument, UpdateResult> {
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
  suspend fun PipelineContext<Unit, ApplicationCall>.contactRoles() {
    return success(contact.roles)
  }

  /**
   * 获取某个群/频道成员列表
   */
  @AronaBackendEndpointGet("/members")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactMembers() {
    return success(contact.members)
  }

  /**
   * 获取某个群/频道策略列表
   */
  @AronaBackendEndpointGet("/{id}/policies")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactPolicies() {
    return success(contact.policies)
  }

  /**
   * 更新用户信息
   */
  @AronaBackendEndpointPut("/{id}/member")
  suspend fun PipelineContext<Unit, ApplicationCall>.updateMember() {
    val data = context.receiveJsonOrNull<ContactMemberUpdateReq>() ?: return badRequest()
    // 检查权限 只能更新自己的或者自己管理的他人的
    val permit = data.id == aronaUser.id || contact.checkAdminPermission(aronaUser.id)
    if (permit) {
      return if (
        ContactDocument.withCollection<ContactDocument, UpdateResult> {
          updateOne(
            filter = Filters.and(
              idFilter(contact.id),
              Filters.eq("${ContactDocument::members.name}._id", data.id)
            ),
            update = Updates.combine(
              Updates.set("${ContactDocument::members.name}.$.${ContactMember::name.name}", data.name),
              Updates.set("${ContactDocument::members.name}.$.${ContactMember::roles.name}", data.roles),
            )
          )
        }.modifiedCount == 1L
      ) success()
      else internalServerError()
    } else {
      return errorPermissionDeniedMessage()
    }
  }

  /**
   * 创建角色
   */
  @AronaBackendEndpointPost("/{id}/role")
  suspend fun PipelineContext<Unit, ApplicationCall>.createRole() {
    val data = context.receiveJsonOrNull<ContactRoleCreateReq>() ?: return badRequest()
    return if (
      ContactDocument.withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = idFilter(contact.id),
          update = Updates.addToSet(
            ContactDocument::roles.name,
            ContactRole.createRole(data.name)
          )
        )
      }.modifiedCount == 1L
    ) success()
    else internalServerError()
  }

  /**
   * 删除角色
   */
  @AronaBackendEndpointDelete("/{id}/role")
  suspend fun PipelineContext<Unit, ApplicationCall>.deleteRole() {
    val data = context.receiveJsonOrNull<IdBody>() ?: return badRequest()
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
  suspend fun PipelineContext<Unit, ApplicationCall>.updateRole() {
    val data = context.receiveJsonOrNull<ContactRoleCreateReq>() ?: return badRequest()
    return if (
      ContactDocument.withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = Filters.and(
            idFilter(contact.id),
            Filters.eq("${ContactDocument::roles.name}._id", data.id)
          ),
          update = Updates.set("${ContactDocument::roles.name}.$.${ContactRole::name.name}", data.name),
        )
      }.modifiedCount == 1L
    ) success()
    else internalServerError()
  }

  /**
   * 获取策略
   */
  @AronaBackendEndpointGet("/{id}/policy")
  suspend fun PipelineContext<Unit, ApplicationCall>.policy() {
    val id = request.queryParameters["pid"] ?: return badRequest()
    return contact.policies.firstOrNull { it.id == id }?.let {
      success(it)
    } ?: success()
  }

  /**
   * 更新策略
   */
  @AronaBackendEndpointPut("/{id}/policy")
  suspend fun PipelineContext<Unit, ApplicationCall>.updatePolicy() {
    val policy = context.receiveJsonOrNull<Policy>() ?: return badRequest()
    if (policy.id in PROTECTED_POLICY_ID || ContactRole.checkHasProtectedRoleId(policy)) {
      return badRequest()
    }
    return if (
      ContactDocument.withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = Filters.and(
            idFilter(contact.id),
            Filters.eq("${ContactDocument::policies.name}._id", policy.id)
          ),
          update = Updates.set("${ContactDocument::policies.name}.$", policy)
        )
      }.modifiedCount == 1L
    ) success()
    else internalServerError()
  }

  /**
   * 创建策略
   */
  @AronaBackendEndpointPost("/{id}/policy")
  suspend fun PipelineContext<Unit, ApplicationCall>.createPolicy() {
    val data = context.receiveJsonOrNull<Policy>() ?: return badRequest()
    if (contact.policies.any { it.id == data.id }) {
      return badRequest()
    }
    if (data.id in PROTECTED_POLICY_ID || ContactRole.checkHasProtectedRoleId(data)) {
      return badRequest()
    }
    val policy = Policy(
      Policy.randomPolicyId(),
      data.name,
      data.effect,
      data.rules
    )
    return if (
      ContactDocument.withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = idFilter(contact.id),
          update = Updates.push(ContactDocument::policies.name, policy)
        )
      }.modifiedCount == 1L
    ) success()
    else internalServerError()
  }

  /**
   * 删除策略
   */
  @AronaBackendEndpointDelete("/{id}/policy")
  suspend fun PipelineContext<Unit, ApplicationCall>.deletePolicy() {
    val id = context.receiveJsonOrNull<IdBody>()?.id ?: return badRequest()
    if (id in PROTECTED_POLICY_ID) {
      return badRequest()
    }
    return if (
      ContactDocument.withCollection<ContactDocument, UpdateResult> {
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
      }.modifiedCount == 1L
    ) success()
    else internalServerError()
  }
}
