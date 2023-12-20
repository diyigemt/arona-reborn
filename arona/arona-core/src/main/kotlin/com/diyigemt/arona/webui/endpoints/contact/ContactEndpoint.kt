@file:Suppress("unused")

package com.diyigemt.arona.webui.endpoints.contact

import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactType
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.*
import com.diyigemt.arona.webui.plugins.receiveJson
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

@AronaBackendEndpoint("/contact")
internal object ContactEndpoint {
  private val NoRequestContactIdPath = listOf("/contacts")
  private val RequestContactAdminPath = listOf("/contact", "/contact-basic", "/roles", "/members")
  private val PipelineContext<Unit, ApplicationCall>.contactId
    get() = request.queryParameters["id"]!!

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
      if (request.queryParameters["id"] == null) {
        errorMessage("缺少请求参数")
        return finish()
      }
    }
    // 检查权限?
    if (RequestContactAdminPath.any { path.endsWith(it) }) {
      val id: String? = if (method == HttpMethod.Get) {
        contactId
      } else {
        kotlin.runCatching {
          context.receiveJson<IdBody>().id
        }.getOrNull()
      }
      if (id != null) {
        when (val contact = findContactDocumentByIdOrNull(id)) {
          is ContactDocument -> {
            if (contact.members.none { it.roles.contains(DEFAULT_ADMIN_CONTACT_ROLE_ID) && it.id == aronaUser.id }) {
              errorMessage("权限不足")
              return finish()
            }
            _contact = contact
          }

          else -> {
            errorMessage("群/频道信息查询失败")
            return finish()
          }
        }
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
}
