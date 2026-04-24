# IS_CHILD 迁移审计报告

> 对照新 IS_CHILD 语义 ([is-child-operator.md](./is-child-operator.md)) 与历史 warden 实现, 审计 arona 存量策略是否存在行为差异。

## 审计范围

### 代码内置策略 (生效于每个 ContactDocument)
- `Policy.Companion.createBaseContactAdminPolicy()` — 管理员权限 (IS_CHILD value=`"*"`)
- `Policy.Companion.createBaseMemberPolicy()` — 普通成员权限 2 条 (IS_CHILD value=`"*"`, `"buildIn.owner:*"`)
- `Policy.Companion.BuildInAllowPolicy` / `BuildInDenyPolicy` — 内置超级管理员策略 (IS_CHILD value=`"buildIn.super:*"`)

### 左值样本
```
*
(空串)
com.diyigemt.arona
com.diyigemt.arona:command.call_me
com.diyigemt.arona:command.call_me.sub
com.diyigemt.kivotos:command.list
buildIn.super:admin
buildIn.super:anything
buildIn.owner:admin
buildIn.owner:manage.user
buildIn.normal:help
buildIn:*
console:command.test
a:b
a.b.c:d.e
```

### Mongo 存量策略 (用户自定义)
**未在本次 CI 审计中覆盖**。用户自定义策略需在目标部署环境运行时审计, 入口见 [运行时审计](#运行时审计)。

## 结果

### 代码内置策略
**Diff = 0** (自动化测试 `IsChildAuditTest` 保证, 任何回归都会触发 CI 失败)。

共涉及 3 种 IS_CHILD `value`:
| value | 说明 | 左值样本命中情况 | 新旧一致性 |
|---|---|---|---|
| `"*"` | 任意资源 | 全部 left (含空串) 命中 | ✅ 一致 |
| `"buildIn.owner:*"` | owner 分组下任意资源 | `buildIn.owner:admin`, `buildIn.owner:manage.user` 命中 | ✅ 一致 |
| `"buildIn.super:*"` | super 分组下任意资源 | `buildIn.super:admin`, `buildIn.super:anything` 命中 | ✅ 一致 |

### 运行时审计 (Mongo 存量)

Phase 2 部署前, 在生产环境执行以下流程:

```kotlin
// arona-core 内部执行 (例如通过临时 console command)
val allContacts = ContactDocument.withCollection {
  find().toList()
}
val allPolicies = allContacts.flatMap { it.policies }
val samples = PermissionService.permissions.keys().map { it.toString() } + listOf("", "*")
val report = IsChildMatcher.auditMigration(allPolicies, samples)

if (report.hasDiff) {
  log.error("IS_CHILD migration audit failed: ${report.diffs.size} diffs")
  report.diffs.forEach { log.error("  $it") }
} else {
  log.info("IS_CHILD migration audit: 0 diffs, safe to roll out")
}
```

若 diff > 0, 需逐条分析 (通常是 `value` 形如 `"xxx*"` 无 `.` 的非法通配; 新语义按字面处理, 与用户意图可能不同) 并在 Phase 2 之前人工修正或迁移策略。

## 结论

代码内置策略零 diff, 满足 Phase 1 退出条件。Mongo 存量审计需运维在生产环境手动触发, 流程已提供入口函数。

---

**审计时间**: 2026-04-24  
**Phase**: 1  
**审计者**: IsChildAuditTest (自动化) + 人工 review
