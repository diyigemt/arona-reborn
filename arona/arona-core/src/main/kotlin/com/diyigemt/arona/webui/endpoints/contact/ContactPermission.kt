package com.diyigemt.arona.webui.endpoints.contact

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_SUPER_ROLE_ID

/**
 * /contact 路由下需要管理员权限的末段集合 (按 path 末段精确匹配, 避免 /member vs /members 这类 endsWith 漏配).
 */
internal val ContactAdminPathSegments: Set<String> = setOf(
  "contact",
  "contact-basic",
  "member",
  "members",
  "role",
  "roles",
  "policy",
  "policies",
  "preference",
)

internal fun requiresContactAdmin(path: String): Boolean =
  // 先 trimEnd('/') 防御性处理 trailing slash, 否则 `/contact/123/member/` 末段会为空串而绕过校验.
  path.trimEnd('/').substringAfterLast('/') in ContactAdminPathSegments

internal sealed interface MemberUpdateDecision {
  data class Allow(val updateRoles: Boolean) : MemberUpdateDecision
  data object Deny : MemberUpdateDecision
}

/**
 * 计算 PUT /contact/{id}/member 的权限决策, 与 IO 解耦以便单元测试.
 * 规则:
 *  1. 目标成员不存在 -> 拒绝.
 *  2. 任何请求都不允许把成员角色集合写入 super 角色 (DEFAULT_SUPER_ROLE_ID).
 *  3. 管理员可以同时改名与角色.
 *  4. 普通用户仅在 actorId == 目标 id 时允许改名, 角色字段被忽略.
 *  5. 其他情况拒绝.
 */
internal fun resolveMemberUpdate(
  actor: ContactDocument,
  actorId: String,
  target: ContactMember?,
  req: ContactMemberUpdateReq,
): MemberUpdateDecision {
  if (target == null) return MemberUpdateDecision.Deny
  if (req.roles.any { it == DEFAULT_SUPER_ROLE_ID }) return MemberUpdateDecision.Deny
  if (actor.checkAdminPermission(actorId)) {
    return MemberUpdateDecision.Allow(updateRoles = true)
  }
  return if (actorId == req.id && target.id == req.id) {
    MemberUpdateDecision.Allow(updateRoles = false)
  } else {
    MemberUpdateDecision.Deny
  }
}
