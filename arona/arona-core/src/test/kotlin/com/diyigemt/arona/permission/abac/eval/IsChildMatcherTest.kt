package com.diyigemt.arona.permission.abac.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsChildMatcherTest {

  // region §3 规范规则

  @Test
  fun `right 为单字符 星号 匹配任意左值`() {
    assertTrue(IsChildMatcher.matches("foo", "*"))
    assertTrue(IsChildMatcher.matches("com.x.y:cmd", "*"))
    assertTrue(IsChildMatcher.matches("", "*"))
  }

  @Test
  fun `left 或 right 非 String 返回 false`() {
    assertFalse(IsChildMatcher.matches(42, "*"))
    assertFalse(IsChildMatcher.matches("foo", 42))
    assertFalse(IsChildMatcher.matches(null, "*"))
    assertFalse(IsChildMatcher.matches("foo", null))
    assertFalse(IsChildMatcher.matches(listOf("a"), "*"))
  }

  @Test
  fun `right 为空字符串拒绝`() {
    // 新语义: 空右值永远 deny (相对 warden 旧行为在 L='' 时返回 true, 这里显式拒绝)
    assertFalse(IsChildMatcher.matches("anything", ""))
    assertFalse(IsChildMatcher.matches("", ""))
  }

  @Test
  fun `right 冒号段多于 left 返回 false`() {
    assertFalse(IsChildMatcher.matches("a:b", "a:b:c"))
    assertFalse(IsChildMatcher.matches("foo", "foo:bar"))
  }

  @Test
  fun `冒号段数相等且无通配必须严格字面相等`() {
    assertTrue(IsChildMatcher.matches("a:b", "a:b"))
    assertFalse(IsChildMatcher.matches("a:b", "a:c"))
    assertFalse(IsChildMatcher.matches("a.b", "a.c"))
  }

  @Test
  fun `冒号段通配星号匹配任意内容`() {
    assertTrue(IsChildMatcher.matches("com.diyigemt.arona:command.call_me", "com.diyigemt.arona:*"))
    assertTrue(IsChildMatcher.matches("com.diyigemt.arona:anything.goes.here", "com.diyigemt.arona:*"))
  }

  @Test
  fun `冒号段通配不匹配其他命名空间`() {
    assertFalse(IsChildMatcher.matches("com.other:x", "com.diyigemt.arona:*"))
    assertFalse(IsChildMatcher.matches("buildIn.super:admin", "buildIn.owner:*"))
  }

  @Test
  fun `点号段末位通配按前缀匹配`() {
    assertTrue(IsChildMatcher.matches("a.b.c.x", "a.b.c.*"))
    assertTrue(IsChildMatcher.matches("a.b.c.x.y", "a.b.c.*"))
    assertFalse(IsChildMatcher.matches("a.b.d.x", "a.b.c.*"))
  }

  @Test
  fun `点号末位通配要求左值点号段数严格多于通配前缀`() {
    // "a.*" 要求左值至少 "a.x" (2 段), 单独 "a" (1 段) 不够
    assertFalse(IsChildMatcher.matches("a", "a.*"))
    // "a.*" vs "a.b" (左值 2 段, 右值 2 段) -> 够, 且前缀 "a"==a ✓
    assertTrue(IsChildMatcher.matches("a.b", "a.*"))
    // "a.b.*" (右值 3 段) vs "a.b" (左值 2 段), 不够
    assertFalse(IsChildMatcher.matches("a.b", "a.b.*"))
    // "a.b.*" vs "a.b.c" (左值 3 段) -> 够, 前缀 "a.b"=="a.b" ✓
    assertTrue(IsChildMatcher.matches("a.b.c", "a.b.*"))
  }

  @Test
  fun `混合冒号与点号通配`() {
    assertTrue(IsChildMatcher.matches("foo.bar:baz.x", "foo.*:baz.*"))
    assertFalse(IsChildMatcher.matches("foo.bar:qux.x", "foo.*:baz.*"))
    assertTrue(IsChildMatcher.matches("buildIn.owner:manage.user", "buildIn.owner:*"))
  }

  @Test
  fun `多层冒号嵌套段匹配`() {
    assertTrue(IsChildMatcher.matches("a:b:c", "a:b:*"))
    assertTrue(IsChildMatcher.matches("a:b:c", "a:b:c"))
    assertFalse(IsChildMatcher.matches("a:b:c", "a:c:*"))
  }

  // endregion

  // region §4 历史 warden 行为对照 (无通配/通配等 -- 保持原有预期结果)

  @Test
  fun `right单字符星号匹配任意左值`() {
    assertTrue(IsChildMatcher.matches("anything", "*"))
  }

  @Test
  fun `非法通配 foo 星号 按字面处理`() {
    // 新语义: "foo*" 不是合法通配 (只有 ".*" 或单独 "*" 才是), 按字面比较 -> false
    assertFalse(IsChildMatcher.matches("x.y", "foo*"))
    // 字面量自身相等时返回 true
    assertTrue(IsChildMatcher.matches("foo*", "foo*"))
  }

  @Test
  fun `点号通配左值段数不足返回 false`() {
    assertEquals(false, IsChildMatcher.matches("a", "a.*"))
  }

  @Test
  fun `点号通配左值段数足够返回 true`() {
    assertEquals(true, IsChildMatcher.matches("a.b.c", "a.*"))
  }

  @Test
  fun `无通配段数不等返回 false`() {
    assertEquals(false, IsChildMatcher.matches("a.b.c", "a.b"))
  }

  @Test
  fun `非 String 类型返回 false`() {
    assertEquals(false, IsChildMatcher.matches(1, "*"))
    assertEquals(false, IsChildMatcher.matches("x", 1))
  }

  // endregion

  // region 真实样本 (arona 常用 IS_CHILD value)

  @Test
  fun `真实样本 - buildIn_super 通配命中 super 资源`() {
    assertTrue(IsChildMatcher.matches("buildIn.super:admin", "buildIn.super:*"))
    assertTrue(IsChildMatcher.matches("buildIn.super:anything", "buildIn.super:*"))
  }

  @Test
  fun `真实样本 - buildIn_super 通配不命中其他 buildIn 分组`() {
    assertFalse(IsChildMatcher.matches("buildIn.owner:admin", "buildIn.super:*"))
    assertFalse(IsChildMatcher.matches("buildIn.normal:list", "buildIn.super:*"))
  }

  @Test
  fun `真实样本 - buildIn_owner 通配命中 owner 资源`() {
    assertTrue(IsChildMatcher.matches("buildIn.owner:manage.user", "buildIn.owner:*"))
  }

  @Test
  fun `真实样本 - com_diyigemt_arona 命名空间匹配`() {
    assertTrue(IsChildMatcher.matches("com.diyigemt.arona:command.call_me", "com.diyigemt.arona:*"))
    assertFalse(IsChildMatcher.matches("com.diyigemt.kivotos:command.list", "com.diyigemt.arona:*"))
  }

  // endregion
}
