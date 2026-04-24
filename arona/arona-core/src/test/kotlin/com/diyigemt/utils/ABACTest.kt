package com.diyigemt.utils

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.createBaseAdminRole
import com.diyigemt.arona.database.permission.ContactRole.Companion.createBaseMemberRole
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import com.diyigemt.arona.permission.Permission.Companion.RootPermission
import com.diyigemt.arona.permission.Permission.Companion.fullPermissionId
import com.diyigemt.arona.permission.Permission.Companion.testPermission
import com.diyigemt.arona.permission.PermissionId
import com.diyigemt.arona.permission.PermissionImpl
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 端到端验证 `Permission.testPermission` 在自研 evaluator 下的行为. 使用内存 fixture 避免 Mongo 依赖.
 */
class ABACTest {

  private fun buildContactFixture(member: ContactMember) = ContactDocument(
    id = "contact.test",
    roles = listOf(createBaseAdminRole(), createBaseMemberRole()),
    policies = mutableListOf(createBaseContactAdminPolicy()).apply {
      addAll(createBaseMemberPolicy())
    },
    members = listOf(member),
  )

  @Test
  fun testContactBaseAdminPolicy() {
    runBlocking {
      val admin = ContactMember("u.admin", "管理员", listOf(DEFAULT_ADMIN_CONTACT_ROLE_ID))
      val contact = buildContactFixture(admin)
      val permission = PermissionImpl(PermissionId("com.diyigemt.arona", "*"), "", RootPermission)
      assertTrue(
        permission.testPermission(admin, contact.policies),
        "admin 角色应允许任意资源"
      )
    }
  }

  @Test
  fun testPermissionFather() {
    val root = PermissionImpl(PermissionId("buildIn", "*"), "root permission", RootPermission)
    val firstChild = PermissionImpl(PermissionId("command.bind", "*"), "第一个子代", root)
    val secondChild = PermissionImpl(PermissionId("bind_a", "*"), "第二个子代", firstChild)

    assertEquals("buildIn:*", root.fullPermissionId())
    assertEquals("buildIn:*", firstChild.fullPermissionId())
    assertEquals("buildIn:command.bind:*", secondChild.fullPermissionId())
  }

  @Test
  fun testMemberDeniedFromOwnerResource() {
    runBlocking {
      val member = ContactMember("u.member", "成员", listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID))
      val contact = buildContactFixture(member)
      val permissionOwner = PermissionImpl(PermissionId("buildIn.owner", "admin"), "", RootPermission)
      val permissionArona = PermissionImpl(PermissionId("com.diyigemt.arona", "*"), "", RootPermission)

      assertFalse(
        permissionOwner.testPermission(member, contact.policies),
        "member 访问 buildIn.owner 资源应被 deny"
      )
      assertTrue(
        permissionArona.testPermission(member, contact.policies),
        "member 访问 com.diyigemt.arona 资源应允许"
      )
    }
  }
}
