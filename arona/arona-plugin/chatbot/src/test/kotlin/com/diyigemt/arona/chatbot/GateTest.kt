package com.diyigemt.arona.chatbot

import com.diyigemt.arona.webui.event.ContentAuditEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateTest {
  private val cfg = ChatbotConfig(enabled = true, mustPrefixes = listOf("阿罗娜"), cooldownSec = 10, muteKeywords = listOf("闭嘴"))
  private val now = 1_000_000L

  private fun run(
    content: String,
    isAtBot: Boolean = false,
    cfg: ChatbotConfig = this.cfg,
    state: GroupState = GroupState(cfg.pityBase),
    timestampMillis: Long? = now,
    allowRate: () -> Boolean = { true },
    random: () -> Double = { 0.0 },
  ) = gate(content, isAtBot, timestampMillis, now, cfg, staleSec = 60, state = state, allowRate = allowRate, random = random)

  @Test
  fun `Must 判定 - @ 命中 先导词加空格命中 无空格不命中`() {
    assertTrue(detectMust("你好", true, emptyList()).must)
    detectMust("阿罗娜 你好", false, listOf("阿罗娜")).let {
      assertTrue(it.must)
      assertEquals("你好", it.text, "先导词要从正文剥掉")
    }
    assertEquals(false, detectMust("阿罗娜你好", false, listOf("阿罗娜")).must)
  }

  @Test
  fun `STALE 门 - 超过 staleSec 或缺失 timestamp 都不进入后续`() {
    assertEquals(Gate.Skip(NoopReason.STALE), run("阿罗娜 在吗", timestampMillis = now - 61_000))
    assertEquals(Gate.Skip(NoopReason.STALE), run("阿罗娜 在吗", timestampMillis = null))
    assertIs<Gate.Proceed>(run("阿罗娜 在吗", timestampMillis = now - 59_000))
  }

  @Test
  fun `timestamp 解析 - 平台 ISO8601 带时区偏移`() {
    assertEquals(1_688_540_803_000L, parseTimestampMillis("2023-07-05T15:06:43+08:00"))
    assertEquals(1_688_540_803_000L, parseTimestampMillis("1688540803"))
    assertNull(parseTimestampMillis("not-a-time"))
    assertNull(parseTimestampMillis(null))
  }

  @Test
  fun `PITY - 未中累加 发出后由流水线重置`() {
    val pity = cfg.copy(probabilityMode = ProbabilityMode.PITY, pityBase = 0.0005, pityStep = 0.0001)
    val state = GroupState(pity.pityBase)
    repeat(3) { assertEquals(Gate.Skip(NoopReason.PROBABILITY_MISS), run("随便聊聊", cfg = pity, state = state, random = { 0.5 })) }
    assertEquals(0.0008, state.pity, 1e-9)
    // 骰子落在累计概率之内即命中
    assertIs<Gate.Proceed>(run("随便聊聊", cfg = pity, state = state, random = { 0.0007 }))
    assertEquals(0.0008, state.pity, 1e-9, "重置是发送成功后的事, gate 不动它")
  }

  @Test
  fun `FIXED - 无状态 不累加`() {
    val fixed = cfg.copy(probabilityMode = ProbabilityMode.FIXED, fixedProbability = 0.1)
    val state = GroupState(fixed.pityBase)
    assertEquals(Gate.Skip(NoopReason.PROBABILITY_MISS), run("随便聊聊", cfg = fixed, state = state, random = { 0.5 }))
    assertEquals(fixed.pityBase, state.pity, "FIXED 期间 pity 保持在基数")
    // 从 PITY 带着累计切到 FIXED, 累计被清掉
    val carried = GroupState(0.9)
    run("阿罗娜 在吗", cfg = fixed, state = carried, random = { 0.5 })
    assertEquals(fixed.pityBase, carried.pity, "Must 消息也要触发归零, 否则 FIXED 期间只来 @ 消息就带着旧累计切回 PITY")
    assertIs<Gate.Proceed>(run("随便聊聊", cfg = fixed, state = state, random = { 0.05 }))
  }

  @Test
  fun `Must 跳过概率与冷却 概率路径受冷却`() {
    val state = GroupState(cfg.pityBase).apply { lastReplyAt = now - 1_000 }
    assertEquals(Gate.Skip(NoopReason.COOLDOWN), run("随便聊聊", state = state, random = { 0.0 }))
    assertIs<Gate.Proceed>(run("阿罗娜 在吗", state = state, random = { 1.0 }))
  }

  @Test
  fun `概率路径被限流时静默 Must 路径才带提示`() {
    assertEquals(Gate.Skip(NoopReason.RATE_LIMITED, hint = null), run("随便聊聊", allowRate = { false }))
    assertEquals(Gate.Skip(NoopReason.RATE_LIMITED, hint = RATE_LIMIT_HINT), run("阿罗娜 在吗", allowRate = { false }))
  }

  @Test
  fun `闭嘴 - 只有 Must 触发才生效 生效期间一律 MUTED`() {
    val state = GroupState(cfg.pityBase)
    assertEquals(Gate.Skip(NoopReason.PROBABILITY_MISS), run("闭嘴", state = state, random = { 1.0 }), "别人让别人闭嘴不关 bot 的事")
    assertEquals(Gate.Mute(now + 600_000), run("阿罗娜 闭嘴", state = state))
    assertEquals(Gate.Skip(NoopReason.MUTED), run("阿罗娜 在吗", state = state))
    assertEquals(Gate.Skip(NoopReason.MUTED), run("随便聊聊", state = state))
    assertEquals(Gate.Skip(NoopReason.MUTED), run("阿罗娜 闭嘴", state = state), "静默期内再说闭嘴也不回确认")
  }

  @Test
  fun `EMPTY 与 TOO_LONG`() {
    assertEquals(Gate.Skip(NoopReason.EMPTY), run("   ", isAtBot = true), "只 @ 不说话")
    assertEquals(Gate.Skip(NoopReason.TOO_LONG), run("阿罗娜 " + "啊".repeat(cfg.maxUserChars + 1)))
  }

  @Test
  fun `审核 fail-closed - 超时 未审 都不发 拒绝为 BLOCK`() {
    assertEquals(NoopReason.AUDIT_UNAVAILABLE, auditVerdict(null))
    assertEquals(NoopReason.AUDIT_UNAVAILABLE, auditVerdict(ContentAuditEvent("x")))
    assertEquals(NoopReason.AUDIT_BLOCK, auditVerdict(ContentAuditEvent("x", pass = false).apply { audited = true }))
    assertNull(auditVerdict(ContentAuditEvent("x").apply { audited = true }))
  }
}
