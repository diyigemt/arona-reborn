@file:Suppress("unused")

package com.diyigemt.arona.webui.endpoints.contact

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactType
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.*
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
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
)

@AronaBackendEndpoint("/contact")
object ContactEndpoint {
  private val RequestContactIdPath = listOf("/roles", "/members")
  private val PipelineContext<Unit, ApplicationCall>.contactId
    get() = request.queryParameters["id"]

  @AronaBackendAdminRouteInterceptor
  suspend fun PipelineContext<Unit, ApplicationCall>.contactIdInterceptor() {
    val method = context.request.httpMethod
    val path = context.request.path()
    if (method == HttpMethod.Get && RequestContactIdPath.any { path.endsWith(it) }) {
      if (contactId == null) {
        errorMessage("缺少请求参数")
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
        Document(
          ContactDocument::members.name,
          Document(
            "\$filter",
            Document(
              mapOf(
                "input" to "\$members",
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
   * 获取某个群/频道自定义的角色列表
   */
  @AronaBackendEndpointGet("/roles")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactRoles() {
    val cid = contactId!!
    val contact = findContactDocumentByIdOrNull(cid) ?: return errorMessage("群/频道信息查询失败")
    return success(contact.roles)
  }

  /**
   * 获取某个群/频道成员列表
   */
  @AronaBackendEndpointGet("/members")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactMembers() {
    val cid = contactId!!
    val contact = findContactDocumentByIdOrNull(cid) ?: return errorMessage("群/频道信息查询失败")
    return success(contact.members)
  }
}
