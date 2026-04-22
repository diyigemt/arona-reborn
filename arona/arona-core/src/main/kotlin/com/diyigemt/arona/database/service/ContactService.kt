package com.diyigemt.arona.database.service

import com.diyigemt.arona.communication.contact.Channel
import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.contact.FriendUser
import com.diyigemt.arona.communication.contact.Group
import com.diyigemt.arona.communication.contact.Guild
import com.diyigemt.arona.communication.contact.User
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.matchedOne
import com.diyigemt.arona.database.modifiedOne
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocumentUpdateException
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactType
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.runSagaOrRollback
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import org.bson.Document

/**
 * 跨 SQL+Mongo 的群/频道+用户初始化链路. 从 [ContactDocument.Companion] 搬出,
 * 让 schema 只保留数据定义 + 单点写 (updateMemberRole / updateContactDocumentName / …).
 *
 * 事务边界: 整个链路涉及 Contact/User 两个 Mongo 集合 + SQL, 没有跨库事务;
 * 任何一步失败都会按相反顺序回滚已生效的副作用, 保证不留下"半挂"成员.
 */
internal object ContactService {

  /**
   * 创建/合并群与用户的初始关系.
   * 顺序:
   *  1. 若 contact 不存在 → [ContactDocument.createContactDocument]
   *  2. 若 user 不存在 → [UserService.createUserTracking]
   *  3. [addMember] 双写 Contact.members + User.contacts
   *  4. 角色分配 (如需)
   * 任意失败按 4 → 3 → 2 → 1 顺序补偿已生效的步骤.
   */
  suspend fun createContactAndUser(contact: Contact, user: User, role: String): UserDocument {
    val id = contact.fatherSubjectIdOrSelf
    var createdContact = false
    var createdMember = false
    var userUndo: (suspend () -> Unit)? = null

    val contactDocument = ContactDocument.findContactDocumentByIdOrNull(id)
      ?: ContactDocument.createContactDocument(id, resolveContactType(contact)).also { createdContact = true }

    var userDocument: UserDocument? = null
    try {
      userDocument = UserDocument.findUserDocumentByUidOrNull(user.id)
        ?: UserService.createUserTracking(user.id, id).let {
          userUndo = it.undo
          it.document
        }

      val before = contactDocument.findContactMemberOrNull(userDocument.id)
      val member = addMember(contactDocument, userDocument.id)
      createdMember = before == null

      if (role !in member.roles) {
        when (val r = contactDocument.updateMemberRole(member.id, role)) {
          is ContactDocumentUpdateException.Success -> Unit
          else -> throw IllegalStateException("updateMemberRole failed: ${r.cause}")
        }
      }
      return userDocument
    } catch (t: Throwable) {
      // 回滚顺序与生效顺序相反; 任意 rollback 失败被 suppress 不掩盖根因.
      if (createdMember && userDocument != null) {
        runCatching { rollbackAddedMember(contactDocument, userDocument.id) }
          .onFailure { t.addSuppressed(it) }
      }
      // userUndo 仅在本次真正创建用户时被赋值, 复用既有 user 不触发, 避免误删 SQL 行.
      userUndo?.let { undo ->
        runCatching { undo() }.onFailure { t.addSuppressed(it) }
      }
      if (createdContact) {
        runCatching { deleteContactDocument(contactDocument.id) }
          .onFailure { t.addSuppressed(it) }
      }
      throw t
    }
  }

  /**
   * 把成员加入群; 已存在则补齐 user.contacts (修补历史脏数据).
   * 双写: Contact.members + User.contacts; 第二步失败通过 saga 回滚第一步.
   */
  private suspend fun addMember(contactDocument: ContactDocument, userId: String): ContactMember {
    val existMember = contactDocument.members.firstOrNull { it.id == userId }
    if (existMember != null) {
      val res = UserDocument.withCollection<UserDocument, UpdateResult> {
        updateOne(
          filter = idFilter(existMember.id),
          update = Updates.addToSet(UserDocument::contacts.name, contactDocument.id),
        )
      }
      if (!res.matchedOne()) {
        throw IllegalStateException(
          "addMember inconsistency: contact ${contactDocument.id} already lists member ${existMember.id} " +
            "but the corresponding UserDocument is missing"
        )
      }
      return existMember
    }

    val defaultRole = contactDocument.roles.first { it.id == DEFAULT_MEMBER_CONTACT_ROLE_ID }
    val member = ContactMember(userId, "用户", listOf(defaultRole.id))
    val firstWrite = ContactDocument.withCollection<ContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(contactDocument.id),
        update = Updates.addToSet(ContactDocument::members.name, member),
      )
    }
    val firstActuallyInserted = firstWrite.modifiedOne()
    return runSagaOrRollback(
      rollback = {
        if (firstActuallyInserted) {
          rollbackAddedMember(contactDocument, member.id)
        }
      },
    ) {
      val secondWrite = UserDocument.withCollection<UserDocument, UpdateResult> {
        updateOne(
          filter = idFilter(member.id),
          update = Updates.addToSet(UserDocument::contacts.name, contactDocument.id),
        )
      }
      // matched == 0 说明 user 文档不存在; 视为不一致, 回滚.
      if (!secondWrite.matchedOne()) {
        throw IllegalStateException(
          "addMember failed: user.contacts not updated. contact=${contactDocument.id} member=${member.id}"
        )
      }
      member
    }
  }

  private suspend fun rollbackAddedMember(contactDocument: ContactDocument, memberId: String) {
    ContactDocument.withCollection<ContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(contactDocument.id),
        update = Document("\$pull", Document(ContactDocument::members.name, Document("_id", memberId))),
      )
    }
    UserDocument.withCollection<UserDocument, UpdateResult> {
      updateOne(
        filter = idFilter(memberId),
        update = Document("\$pull", Document(UserDocument::contacts.name, contactDocument.id)),
      )
    }
  }

  private suspend fun deleteContactDocument(contactId: String) {
    ContactDocument.withCollection<ContactDocument, Unit> { deleteOne(idFilter(contactId)) }
  }

  private fun resolveContactType(contact: Contact): ContactType = when (contact) {
    is FriendUser -> ContactType.Private
    is Group -> ContactType.Group
    is Guild, is Channel -> ContactType.Guild
    else -> ContactType.PrivateGuild
  }
}
