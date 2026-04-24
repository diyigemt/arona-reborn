package com.diyigemt.arona.permission.abac.extract

import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactMember.Companion.ContactMemberPermissionSubject
import com.diyigemt.arona.permission.Permission

/**
 * 手写的 attribute 提取. arona 只有两个类通过 ABAC 求值时需要提属性 (Subject 和 Resource),
 * 字段都是 String 或 `List<String>`, 没有嵌套结构. 零反射, 编译期可检.
 */
internal fun ContactMemberPermissionSubject.toAttrs(): Map<String, Any> = mapOf(
  "id" to id,
  "roles" to roles,
)

internal fun ContactMember.toSubjectAttrs(): Map<String, Any> = mapOf(
  "id" to id,
  "roles" to roles,
)

internal fun Permission.Companion.Resource.toAttrs(): Map<String, Any> = mapOf(
  "id" to id,
)
