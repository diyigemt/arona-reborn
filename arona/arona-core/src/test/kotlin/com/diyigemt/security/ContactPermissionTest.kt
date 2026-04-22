package com.diyigemt.security

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_SUPER_ROLE_ID
import com.diyigemt.arona.database.permission.ContactType
import com.diyigemt.arona.webui.endpoints.contact.ContactMemberUpdateReq
import com.diyigemt.arona.webui.endpoints.contact.MemberUpdateDecision
import com.diyigemt.arona.webui.endpoints.contact.requiresContactAdmin
import com.diyigemt.arona.webui.endpoints.contact.resolveMemberUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactPermissionTest {

  private fun buildContact(): ContactDocument = ContactDocument(
    id = "contact-1",
    contactName = "测试群",
    contactType = ContactType.Group,
    roles = listOf(
      ContactRole(DEFAULT_ADMIN_CONTACT_ROLE_ID, "管理员"),
      ContactRole(DEFAULT_MEMBER_CONTACT_ROLE_ID, "普通成员"),
    ),
    members = listOf(
      ContactMember("admin-user", "管理员", listOf(DEFAULT_ADMIN_CONTACT_ROLE_ID)),
      ContactMember("normal-user", "普通成员", listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID)),
      ContactMember("target-user", "目标成员", listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID)),
    ),
  )

  // ---------- requiresContactAdmin ----------

  @Test
  fun `路径末段为受保护资源时需要管理员`() {
    listOf(
      "/api/v1/contact/123/member",
      "/api/v1/contact/123/members",
      "/api/v1/contact/123/role",
      "/api/v1/contact/123/roles",
      "/api/v1/contact/123/policy",
      "/api/v1/contact/123/policies",
      "/api/v1/contact/123/preference",
      "/api/v1/contact/contact-basic",
    ).forEach {
      assertTrue(requiresContactAdmin(it), "expected admin required for: $it")
    }
  }

  @Test
  fun `公开列表与未知路径无需管理员`() {
    listOf(
      "/api/v1/contact/contacts",
      "/api/v1/contact/manage-contacts",
      "/api/v1/contact/123/something-else",
    ).forEach {
      assertFalse(requiresContactAdmin(it), "expected NOT admin for: $it")
    }
  }

  @Test
  fun `带尾斜杠的受保护路径仍应视为需要管理员`() {
    listOf(
      "/api/v1/contact/123/member/",
      "/api/v1/contact/123/role/",
      "/api/v1/contact/123/policy///",
    ).forEach {
      assertTrue(requiresContactAdmin(it), "expected admin required for trailing slash variant: $it")
    }
  }

  // ---------- resolveMemberUpdate ----------

  @Test
  fun `普通成员改自己的名字时只允许改名`() {
    val contact = buildContact()
    val req = ContactMemberUpdateReq("normal-user", "新名字", listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID))
    val decision = resolveMemberUpdate(contact, "normal-user", contact.findContactMemberOrNull(req.id), req)
    assertEquals(MemberUpdateDecision.Allow(updateRoles = false), decision)
  }

  @Test
  fun `普通成员把自己 roles 改为 admin 时应被忽略 roles 字段`() {
    val contact = buildContact()
    val req = ContactMemberUpdateReq("normal-user", "改名", listOf(DEFAULT_ADMIN_CONTACT_ROLE_ID))
    val decision = resolveMemberUpdate(contact, "normal-user", contact.findContactMemberOrNull(req.id), req)
    assertEquals(MemberUpdateDecision.Allow(updateRoles = false), decision)
  }

  @Test
  fun `普通成员修改他人时应被拒绝`() {
    val contact = buildContact()
    val req = ContactMemberUpdateReq("target-user", "x", listOf(DEFAULT_ADMIN_CONTACT_ROLE_ID))
    val decision = resolveMemberUpdate(contact, "normal-user", contact.findContactMemberOrNull(req.id), req)
    assertEquals(MemberUpdateDecision.Deny, decision)
  }

  @Test
  fun `管理员可同时修改成员的 name 与 roles`() {
    val contact = buildContact()
    val req = ContactMemberUpdateReq("target-user", "新名字", listOf(DEFAULT_ADMIN_CONTACT_ROLE_ID))
    val decision = resolveMemberUpdate(contact, "admin-user", contact.findContactMemberOrNull(req.id), req)
    assertEquals(MemberUpdateDecision.Allow(updateRoles = true), decision)
  }

  @Test
  fun `任何人请求把目标 roles 设为 super 都应拒绝`() {
    val contact = buildContact()
    val reqByAdmin = ContactMemberUpdateReq("target-user", "x", listOf(DEFAULT_SUPER_ROLE_ID))
    assertEquals(
      MemberUpdateDecision.Deny,
      resolveMemberUpdate(contact, "admin-user", contact.findContactMemberOrNull(reqByAdmin.id), reqByAdmin),
    )
    val reqBySelf = ContactMemberUpdateReq("normal-user", "x", listOf(DEFAULT_SUPER_ROLE_ID))
    assertEquals(
      MemberUpdateDecision.Deny,
      resolveMemberUpdate(contact, "normal-user", contact.findContactMemberOrNull(reqBySelf.id), reqBySelf),
    )
  }

  @Test
  fun `目标成员不存在时应拒绝`() {
    val contact = buildContact()
    val req = ContactMemberUpdateReq("ghost-user", "x", listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID))
    val decision = resolveMemberUpdate(contact, "admin-user", contact.findContactMemberOrNull(req.id), req)
    assertEquals(MemberUpdateDecision.Deny, decision)
  }
}
