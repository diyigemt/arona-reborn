package com.diyigemt.security

import com.diyigemt.arona.utils.BusinessCode
import com.diyigemt.arona.utils.ServerResponse
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusinessCodeSerializationTest {
  // 与 Ktor ContentNegotiation 默认配置 (encodeDefaults=false) 对齐, 确保测试结论与运行时一致.
  private val json = Json

  @Test
  fun `业务码值稳定`() {
    assertEquals(200, BusinessCode.OK.code)
    assertEquals(601, BusinessCode.BUSINESS_REJECTED.code)
    assertEquals("操作失败", BusinessCode.BUSINESS_REJECTED.defaultMessage)
  }

  @Test
  fun `成功响应不含 traceId 字段`() {
    val text = json.encodeToString(ServerResponse.serializer(String.serializer()), ServerResponse.success("ok"))
    val obj = json.parseToJsonElement(text).jsonObject
    assertEquals(200, obj["code"]?.jsonPrimitive?.int)
    assertEquals("成功", obj["message"]?.jsonPrimitive?.content)
    assertEquals("ok", obj["data"]?.jsonPrimitive?.content)
    assertFalse("traceId" in obj, "成功响应不应携带 traceId 字段, 节省带宽")
  }

  @Test
  fun `异常响应附带 traceId 字段`() {
    val text = json.encodeToString(
      ServerResponse.serializer(String.serializer()),
      ServerResponse.business(BusinessCode.INTERNAL_ERROR, traceId = "abc123def456"),
    )
    val obj = json.parseToJsonElement(text).jsonObject
    assertEquals(500, obj["code"]?.jsonPrimitive?.int)
    assertEquals("abc123def456", obj["traceId"]?.jsonPrimitive?.content)
  }

  @Test
  fun `业务拒绝响应保留自定义 message`() {
    val text = json.encodeToString(
      ServerResponse.serializer(String.serializer()),
      ServerResponse.business(BusinessCode.BUSINESS_REJECTED, message = "权限不足"),
    )
    val obj = json.parseToJsonElement(text).jsonObject
    assertEquals(601, obj["code"]?.jsonPrimitive?.int)
    assertEquals("权限不足", obj["message"]?.jsonPrimitive?.content)
  }

  @Test
  fun `errorMessage 不再依赖 reason phrase 注入`() {
    // 直接构造业务拒绝响应; 验证 message 内容能原样保留, 而不是被塞进 HTTP 状态行.
    val resp = ServerResponse.business<Unit>(
      BusinessCode.BUSINESS_REJECTED,
      message = "包含 \r\n 的恶意输入也只是普通字符串字段",
    )
    assertEquals(601, resp.code)
    assertEquals("包含 \r\n 的恶意输入也只是普通字符串字段", resp.message)
    assertNull(resp.data)
    assertTrue(resp.traceId == null)
  }
}
