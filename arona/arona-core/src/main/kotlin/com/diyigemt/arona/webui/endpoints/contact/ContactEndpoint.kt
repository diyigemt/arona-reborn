@file:Suppress("unused")
package com.diyigemt.arona.webui.endpoints.contact

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendAdminRouteInterceptor
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.request
import com.mongodb.client.model.Projections
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.toList
import org.bson.Document

@AronaBackendEndpoint("/contact")
object ContactEndpoint {
  private val RequestContactIdPath = listOf("/roles")
  @AronaBackendAdminRouteInterceptor
  suspend fun PipelineContext<Unit, ApplicationCall>.contactIdInterceptor() {
    val method = context.request.httpMethod
    val path = context.request.path()
    if (method == HttpMethod.Get && RequestContactIdPath.any { path.endsWith(it) }) {
      // TODO
    }
  }
  /**
   * 获取用户所有管理的群/频道列表
   */
  @AronaBackendEndpointGet("/contacts")
  suspend fun PipelineContext<Unit, ApplicationCall>.contacts() {
    val manageContacts = ContactDocument.withCollection<ContactDocument, List<ContactDocument.ContactDocumentWithName>> {
      find<ContactDocument.ContactDocumentWithName>(
        Document(ContactDocument::members.name, Document("\$elemMatch", Document(ContactMember::roles.name, DEFAULT_ADMIN_CONTACT_ROLE_ID)))
      ).projection(
        Projections.include(ContactDocument::contactName.name)
      ).toList()
    }
    success(manageContacts)
  }

  /**
   * 获取某个群/频道自定义的角色列表
   */
  @AronaBackendEndpointGet("/roles")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactRoles() {
    val contactId = request.queryParameters["id"] ?: return errorMessage("缺少请求参数")
    val contact = findContactDocumentByIdOrNull(contactId) ?: return errorMessage("群/频道信息查询失败")
    return success(contact.roles)
  }
  /**
   * 获取某个群/频道成员列表
   */
  @AronaBackendEndpointGet("/members")
  suspend fun PipelineContext<Unit, ApplicationCall>.contactMembers() {
    val contactId = request.queryParameters["id"] ?: return errorMessage("缺少请求参数")

  }
}
