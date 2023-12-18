package com.diyigemt.arona.database.permission

import codes.laurence.warden.atts.HasAtts
import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
enum class ContactType {
  Private,
  PrivateGuild,
  Group,
  Guild,
}

@Serializable
internal data class ContactRole(
  @BsonId
  val id: String,
  val name: String,
) {
  companion object {
    internal const val DEFAULT_MEMBER_CONTACT_ROLE_ID = "role.default"
    internal const val DEFAULT_ADMIN_CONTACT_ROLE_ID = "role.admin"
  }
}

@Serializable
internal data class ContactMember(
  @BsonId
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
  @BsonId
  val id: String,
  val contactName: String = "",
  val contactType: ContactType = ContactType.Group,
  var policies: List<Policy> = listOf(),
  var roles: List<ContactRole> = listOf(),
  var members: List<ContactMember> = listOf(),
  val registerTime: String = currentDateTime(),
) {
  @Serializable
  data class ContactDocumentWithName(
    @BsonId
    val id: String,
    val contactName: String,
  )

  companion object : DocumentCompanionObject {
    override val documentName = "Contact"

    suspend fun findContactDocumentByIdOrNull(id: String): ContactDocument? = withCollection {
      find(idFilter(id)).limit(1).firstOrNull()
    }

    fun ContactDocument.createBaseAdminRole() = ContactRole(DEFAULT_ADMIN_CONTACT_ROLE_ID, "管理员")
    fun ContactDocument.createBaseMemberRole() = ContactRole(DEFAULT_MEMBER_CONTACT_ROLE_ID, "普通成员")
    fun ContactDocument.findContactMemberOrNull(memberId: String) = members.firstOrNull { it.id == memberId }
    suspend fun ContactDocument.updateContactDocumentName(name: String) {
      withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = idFilter(id),
          update = Updates.set(ContactDocument::contactName.name, name)
        )
      }
    }

    suspend fun ContactDocument.addMember(userId: String): ContactMember {
      return when (val existMember = members.firstOrNull { it.id == userId }) {
        is ContactMember -> existMember
        else -> {
          val defaultRole = roles.first { it.id == DEFAULT_MEMBER_CONTACT_ROLE_ID }
          val member = ContactMember(userId, "用户", listOf(defaultRole.id))
          withCollection<ContactDocument, UpdateResult> {
            updateOne(
              filter = idFilter(id),
              update = Updates.addToSet(ContactDocument::members.name, member)
            )
          }
          withCollection<UserDocument, UpdateResult> {
            updateOne(
              filter = idFilter(member.id),
              update = Updates.addToSet(UserDocument::contacts.name, id)
            )
          }
          member
        }
      }
    }

    suspend fun ContactDocument.updateMemberRole(memberId: String, roleId: String): ContactDocumentUpdateException {
      withCollection<ContactDocument, UpdateResult> {
        updateOne(
          filter = Filters.and(idFilter(id), Filters.eq("${ContactDocument::members.name}._id", memberId)),
          update = Updates.addToSet("${ContactDocument::members.name}.$.${ContactMember::roles.name}", roleId)
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
