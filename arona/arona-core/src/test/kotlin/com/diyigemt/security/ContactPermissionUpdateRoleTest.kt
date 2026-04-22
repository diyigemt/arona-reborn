package com.diyigemt.security

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocumentUpdateException
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactType
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * 覆盖 [ContactDocument.validateMemberRoleUpdate] 的纯函数语义,
 * 不触 Mongo, 用以固化 P2 的 C5 修复 (updateMemberRole 假成功).
 */
class ContactPermissionUpdateRoleTest {
  private fun buildContact() = ContactDocument(
    id = "contact-1",
    contactName = "测试群",
    contactType = ContactType.Group,
    roles = listOf(
      ContactRole(DEFAULT_MEMBER_CONTACT_ROLE_ID, "普通成员"),
      ContactRole(DEFAULT_ADMIN_CONTACT_ROLE_ID, "管理员"),
    ),
    members = listOf(
      ContactMember("user-1", "张三", listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID)),
    ),
  )

  @Test
  fun `成员存在且角色存在时返回 Success`() {
    val r = buildContact().validateMemberRoleUpdate("user-1", DEFAULT_ADMIN_CONTACT_ROLE_ID)
    assertIs<ContactDocumentUpdateException.Success>(r)
  }

  @Test
  fun `成员不存在时返回 MemberNotFoundException`() {
    val r = buildContact().validateMemberRoleUpdate("ghost-user", DEFAULT_ADMIN_CONTACT_ROLE_ID)
    assertIs<ContactDocumentUpdateException.MemberNotFoundException>(r)
  }

  @Test
  fun `角色不存在时返回 RoleNotFoundException`() {
    val r = buildContact().validateMemberRoleUpdate("user-1", "role.ghost")
    assertIs<ContactDocumentUpdateException.RoleNotFoundException>(r)
  }
}
