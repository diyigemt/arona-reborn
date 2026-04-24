# IS_CHILD 操作符规范

> 本文档定义自研 ABAC 评估器中 `IS_CHILD` 操作符的行为规范。Phase 1 起以本规范为准; 与历史 warden 实现有个别差异, 参见 [§4 与 warden 的差异](#_4-与-warden-的差异).

## 1. 用途

`IS_CHILD` 用于判断一个 **权限 ID** (左值) 是否属于一个 **权限模式** (右值) 所描述的层级子空间。常见于资源类规则:

- `resource.id IsChild "com.diyigemt.arona:*"` — 匹配所有 `com.diyigemt.arona` 命名空间下的资源
- `resource.id IsChild "buildIn.owner:*"` — 匹配所有 `buildIn.owner` 分组下的资源
- `resource.id IsChild "*"` — 匹配任意字符串

## 2. 术语

权限 ID 采用双层分隔:

- **冒号 `:`** — 主级分隔, 切出若干 **冒号段 (colon-section)**。例: `com.diyigemt.arona:command.call_me` 有两个冒号段, 分别是 `com.diyigemt.arona` 和 `command.call_me`。
- **点号 `.`** — 子级分隔, 每个冒号段内部可继续用点号切成 **点号子段 (dot-segment)**。例: 冒号段 `command.call_me` 有两个点号子段, 分别是 `command` 和 `call_me`。
- **通配符 `*`** — 仅允许以下两种位置:
  - 单独出现作为整个右值冒号段, 表示匹配任意内容 (例: `*`, `foo:*`)。
  - 作为右值冒号段的最末点号子段 (例: `foo.*`, `foo.bar.*`)。

其他位置的 `*` 不是通配符, 按字面字符对待。

## 3. 匹配规则

给定左值 `L` 与右值 `R` (两者均为 `String`), 判定 `L IS_CHILD R`:

1. **类型守卫**: 若任一方不是 `String`, 返回 `false`。
2. **空字符串守卫**: 若 `R` 为空字符串 `""`, 返回 `false` (显式拒绝; 详见 §4)。
3. **拆分冒号段**: `LCols = L.split(":")`, `RCols = R.split(":")`。
4. **冒号段数比较**: 若 `RCols.size > LCols.size`, 返回 `false`。
5. **逐对冒号段比较**: 对每组 `(RCols[i], LCols[i])`:
   - 若 `RCols[i] == "*"`, 该对匹配。
   - 若 `RCols[i]` 以 `.*` 结尾 (点号通配): 按点号切成 `RDots` 和 `LDots` (`RDots` 含末位 `*`):
     - 若 `LDots.size < RDots.size`, 该对**不匹配** (左值至少得有一段内容落在通配 `*` 下)。
     - 否则比较前缀: 对所有 `j in [0, RDots.size - 1)`, 要求 `LDots[j] == RDots[j]`; 全等该对匹配。
   - 若 `RCols[i]` 不以 `.*` 结尾 (含整段为 `"foo*"` / `"a*b"` 等, `*` 不视为通配): 要求 `LCols[i] == RCols[i]` 才匹配。
6. 所有冒号段全部匹配, 返回 `true`; 否则 `false`。

### 3.1 示例

| Left | Right | Result | 说明 |
|---|---|---|---|
| `foo` | `*` | true | 右值为单独通配 |
| `com.diyigemt.arona:command.call_me` | `com.diyigemt.arona:*` | true | 第二段通配 |
| `com.other:cmd.x` | `com.diyigemt.arona:*` | false | 首段严格不等 |
| `buildIn.owner:admin` | `buildIn.owner:*` | true | 第二段通配 |
| `a.b.c.x` | `a.b.c.*` | true | 点号前缀匹配 |
| `a.b.d.x` | `a.b.c.*` | false | 点号前缀第 3 段不等 |
| `a.b` | `a.b.*` | false | 左值点号段数不足 |
| `a:b` | `a:b:c` | false | 右值冒号段数多于左值 |
| `buildIn.super:admin` | `buildIn.super:*` | true | 标准 super 权限模式 |
| `buildIn.owner:any` | `buildIn.super:*` | false | 首段 dot-segment 不等 |

## 4. 与 warden 的差异

本规范**显式定义**几个 warden 实现中语义不清或可能抛异常的边界。实际测试结果:

| 输入 | warden 旧行为 | 新语义 | 说明 |
|---|---|---|---|
| `R = "*"` | true | **true** | 保持 |
| `R = ""`, `L != ""` | false (字面比较 `"" == L` → false) | **false** | 保持 |
| `R = ""`, `L = ""` | **true** (字面比较 `"" == ""`) | **false** | **行为改变**: 新语义显式守卫空右值, 不允许空模式匹配空资源 |
| `L = ""` | 常规比较 | **常规比较** | 保持 |
| `L` 或 `R` 非 String | false (类型守卫) | **false** | 保持 |
| `R = "a"` (无通配), `L = "a.b"` | false | **false** | 保持 (严格字面要求整个冒号段相等) |
| `R = "a.*"`, `L = "a"` | false (点号段数不足) | **false** | 保持 |
| `R = "a.*"`, `L = "a.b.c"` | true | **true** | 保持 |
| `R = "a.b"` (无通配), `L = "a.b.c"` | false | **false** | 保持 |
| `R = "foo*"` (无 `.`, 只以 `*` 结尾), `L = "x.y"` (左值点号段数更多) | **抛 `UnsupportedOperationException`** (reduce 空集合) | **false** | **行为改变**: 新语义将非 `.*`/非单 `*` 形式的 `*` 当字面量, 不触发异常 |
| `R = "foo*"`, `L = "foo*"` | true (走 `l == r` 字面相等分支, 不抛异常) | **true** (新语义也按字面比较) | 保持 |

**行为改变**集中在右值形如 `"xxx*"` (以 `*` 结尾但不含 `.`) 且左值点号段数多于右值的情况: warden 旧实现进入 `else` 分支, 调 `removeLast()` 后对空集合 `reduce()`, 抛 `UnsupportedOperationException`; 新语义将这种形式视作字面量比较 (因为非法通配位置), 不再抛异常, 按字面等值返回 false。arona 的存量 policy 中 `IsChild.value` 目前都是 `"*"`, `"com.diyigemt.arona:*"`, `"buildIn.super:*"`, `"buildIn.owner:*"` 等符合规范的模式, 不触发此路径; 另一个 `R=""`, `L=""` 的边界也从 true 改为 false。若用户自定义 policy 中出现上述两种情形, 视作配置错误, 新语义的 deny/字面处理是更友好的兜底。

## 5. 审计

存量 policy 的 `IS_CHILD` `value` 审计由 [is-child-migration-audit.md](is-child-migration-audit.md) 产出。若审计结果 diff > 0, 在 Phase 2 评估器切换前必须对齐处理方案。

## 6. 实现位置

- 规范实现: `arona/arona-core/src/main/kotlin/com/diyigemt/arona/permission/abac/eval/IsChildMatcher.kt` (Phase 1 新增)
- 单元测试: `arona/arona-core/src/test/kotlin/com/diyigemt/arona/permission/abac/eval/IsChildMatcherTest.kt`
- 审计入口: `IsChildMatcher.auditMigration(policies: List<Policy>): AuditReport`
