package com.diyigemt.arona.database.permission

import codes.laurence.warden.atts.HasAtts
import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable

@Serializable
enum class ContactType {
  Private,
  PrivateGuild,
  Group,
  Guild,
}

@Serializable
internal data class ContactRole(
  val id: String,
  val name: String,
)

@Serializable
internal data class ContactMember(
  val id: String, // 指向UserDocument.id
  val name: String,
  val roles: List<String>, // 指向ContactDocument.roles.id
) {
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
internal data class ContactDocument(
  val id: String,
  val contactName: String = "",
  val contactType: ContactType = ContactType.Group,
  var policies: List<Policy> = listOf(),
  var roles: List<ContactRole> = listOf(),
  var members: List<ContactMember> = listOf(),
  val registerTime: String = currentDateTime(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "Contact"

    suspend fun findContactDocumentByIdOrNull(id: String): ContactDocument? = withCollection {
      find(idFilter(id)).limit(1).firstOrNull()
    }

    fun ContactDocument.createRole(name: String) = ContactRole("", name)
    fun ContactDocument.createBaseAdminRole() = ContactRole("role.admin", "管理员")
    fun ContactDocument.createBaseMemberRole() = ContactRole("role.default", "普通成员")
    fun ContactDocument.findContactMemberOrNull(memberId: String) = members.firstOrNull { it.id == memberId }

    suspend fun ContactDocument.addMember(userId: String): ContactMember {
      return when (val existMember = members.firstOrNull { it.id == userId }) {
        is ContactMember -> existMember
        else -> {
          val defaultRole = roles.first { it.id == "role.default" }
          val member = ContactMember(userId, "用户", listOf(defaultRole.id))
          withCollection<ContactDocument, UpdateResult> {
            updateOne(
              filter = idFilter(id),
              update = Updates.addToSet("members", member)
            )
          }
          withCollection<UserDocument, UpdateResult> {
            updateOne(
              filter = idFilter(member.id),
              update = Updates.addToSet("contacts", id)
            )
          }
          member
        }
      }
    }

    suspend fun ContactDocument.updateMemberRole(memberId: String, roleId: String): ContactDocumentUpdateException {
      members.firstOrNull { it.id == memberId } ?: return ContactDocumentUpdateException.MemberNotFoundException(
        memberId
      )
      val role =
        roles.firstOrNull { it.id == roleId } ?: return ContactDocumentUpdateException.RoleNotFoundException(roleId)
      withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = Filters.and(Filters.eq("_id", id), Filters.eq("member._id", memberId)),
          update = Updates.addToSet("member.$.list", role.id)
        )
      }
      return ContactDocumentUpdateException.Success()
    }

    suspend fun createContactDocument(id: String, type: ContactType = ContactType.Group) = ContactDocument(
      id,
      contactType = type,
    )
      .apply {
        roles = listOf(createBaseAdminRole(), createBaseMemberRole())
        policies =
          mutableListOf(createBaseContactAdminPolicy()).apply { addAll(createBaseMemberPolicy()) }
      }
      .also {
        withCollection { insertOne(it) }
      }
  }
}

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
}
