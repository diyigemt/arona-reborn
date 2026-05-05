package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.message.MessageReceiptImpl
import com.diyigemt.arona.communication.message.TencentGuildRaw
import com.diyigemt.arona.communication.message.TencentMessageMediaInfo
import com.diyigemt.arona.communication.message.getMediaUrlFromMediaInfo
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 锁住 shadow (流量灰度) 模式的核心契约:
//  1. shadow=true 时所有 callOpenapi 都不出网, 返回 "看起来成功" 的 stub, 让 send/recall/uploadImage
//     等链路的 post-* 副作用 (事件广播, TencentOfflineImage 包装) 仍能跑完.
//  2. uploadImage(url) 路径里 callOpenapi 失败会 fallback 到 bot.client.get(url) 真实下载图片 ——
//     shadow 必须显式给 PostFriend/GroupRichMessage 一个 MEDIA stub, 不能让通用 "{}" fallback 漏过去.
//  3. throwaway request build 仍要执行: 让 JSON encode / multipart 构造里的本地 bug 在灰度阶段暴露,
//     而不是等真实回归时才被发现.
//  4. shadow=false 时行为不变 (回归保护).
class TencentBotShadowModeTest {

  private val baseConfig = TencentBotConfig(
    id = "shadow-bot",
    appId = "app-shadow",
    token = "tk",
    secret = "sk",
    shadow = true,
  )
  private var bot: TencentBotClient? = null

  @AfterTest
  fun teardown() {
    bot?.close()
    bot = null
  }

  private fun newBot(config: TencentBotConfig = baseConfig): TencentBotClient =
    TencentBotClient(config).also { bot = it }

  // ---------- buildShadowOpenapiStub helper 单测 ----------

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `Unit serializer 走 UNIT 分支`() {
    val stub = buildShadowOpenapiStub(json, TencentEndpoint.PostGroupMessage, Unit.serializer())
    assertEquals(ShadowOpenApiStubKind.UNIT, stub.kind)
    assertTrue(stub.result.isSuccess)
    assertSame(Unit, stub.result.getOrNull())
  }

  @Test
  fun `PostFriendRichMessage 必须显式 MEDIA stub 不走通用 fallback`() {
    // fileInfo 字段无默认值, 通用 "{}" 会 MissingFieldException, 进而让 uploadImage(url) fallback 真实出网,
    // 这条路径必须由显式分支兜底.
    val stub = buildShadowOpenapiStub(
      json,
      TencentEndpoint.PostFriendRichMessage,
      TencentMessageMediaInfo.serializer(),
    )
    assertEquals(ShadowOpenApiStubKind.MEDIA, stub.kind)
    val media = stub.result.getOrThrow()
    assertEquals("", media.fileInfo)
    assertEquals("shadow", media.fileUuid)
    assertEquals(0L, media.ttl)
  }

  @Test
  fun `PostGroupRichMessage 同样走 MEDIA 分支`() {
    val stub = buildShadowOpenapiStub(
      json,
      TencentEndpoint.PostGroupRichMessage,
      TencentMessageMediaInfo.serializer(),
    )
    assertEquals(ShadowOpenApiStubKind.MEDIA, stub.kind)
  }

  @Test
  fun `MessageReceiptImpl 全字段默认 走 EMPTY_OBJECT 分支`() {
    val stub = buildShadowOpenapiStub(
      json,
      TencentEndpoint.PostGroupMessage,
      MessageReceiptImpl.serializer(),
    )
    assertEquals(ShadowOpenApiStubKind.EMPTY_OBJECT, stub.kind)
    val receipt = stub.result.getOrThrow()
    assertEquals("", receipt.id)
    assertEquals("", receipt.timestamp)
  }

  @Test
  fun `ListSerializer 走 EMPTY_LIST 分支`() {
    val stub = buildShadowOpenapiStub(
      json,
      TencentEndpoint.GetBotGuildList,
      ListSerializer(TencentGuildRaw.serializer()),
    )
    assertEquals(ShadowOpenApiStubKind.EMPTY_LIST, stub.kind)
    assertTrue(stub.result.getOrThrow().isEmpty())
  }

  @Test
  fun `无 default 且非 list 的 decoder 落到 UNSUPPORTED 返回 failure`() {
    // TencentMessageMediaInfo 在非 rich-message endpoint 上属于"未登记"组合, 应当 failure 而非伪成功.
    val stub = buildShadowOpenapiStub(
      json,
      TencentEndpoint.PostGroupMessage,
      TencentMessageMediaInfo.serializer(),
    )
    assertEquals(ShadowOpenApiStubKind.UNSUPPORTED, stub.kind)
    assertTrue(stub.result.isFailure)
    val cause = stub.result.exceptionOrNull()
    assertTrue(cause is ShadowOpenApiUnsupportedException)
    assertEquals(TencentEndpoint.PostGroupMessage, cause.endpoint)
  }

  // ---------- TencentBotClient.callOpenapi 短路集成测 ----------

  @Test
  fun `shadow callOpenapi 短路 不读 token 不出网 返回 success stub`() = runBlocking {
    val client = newBot()
    // 不调 auth(), 所以 token snapshot 是 Empty; 真实路径会带空 token 出网, shadow 必须先于此短路.
    var blockInvoked = false
    val result = client.callOpenapi(
      TencentEndpoint.PostGroupMessage,
      MessageReceiptImpl.serializer(),
      mapOf("group_openid" to "g1"),
    ) {
      blockInvoked = true
      method = HttpMethod.Post
      contentType(ContentType.Application.Json)
    }
    assertTrue(result.isSuccess, "shadow 模式应短路并返回成功的 stub")
    assertTrue(blockInvoked, "block 仍应在 throwaway builder 上执行, 让本地 encode 异常能被演练")
  }

  @Test
  fun `shadow Unit 版 callOpenapi 同样短路`() = runBlocking {
    val client = newBot()
    val result = client.callOpenapi(
      TencentEndpoint.DeleteGroupMessage,
      mapOf("group_openid" to "g1", "message_id" to "m1"),
    ) { method = HttpMethod.Delete }
    assertTrue(result.isSuccess, "Unit 版 forward 到 <T> 版后也应被 shadow 短路")
  }

  @Test
  fun `shadow 下 block 抛异常 返回 Result failure 不出网`() = runBlocking {
    val client = newBot()
    val boom = IllegalArgumentException("local encode broke")
    val result = client.callOpenapi(
      TencentEndpoint.PostGroupMessage,
      MessageReceiptImpl.serializer(),
      mapOf("group_openid" to "g1"),
    ) {
      throw boom
    }
    assertTrue(result.isFailure)
    assertSame(boom, result.exceptionOrNull(), "本地构造异常必须原样抛出供调用方诊断")
  }

  @Test
  fun `shadow 下 rich message 上传 stub 让 uploadImage url 不会 fallback 到真实 GET`() = runBlocking {
    val client = newBot()
    val result = client.callOpenapi(
      TencentEndpoint.PostGroupRichMessage,
      TencentMessageMediaInfo.serializer(),
      mapOf("group_openid" to "g1"),
    ) {
      method = HttpMethod.Post
      contentType(ContentType.Application.Json)
    }
    assertTrue(result.isSuccess, "rich message stub 失败会让 uploadImage(url) fallback 真实下载图片, 必须保证成功")
    val media = result.getOrThrow()
    assertEquals("shadow", media.fileUuid)
  }

  @Test
  fun `shadow=false 时 isShadow flag 与 config 对齐`() = runBlocking {
    // shadow=false 路径不能在不真实出网的情况下断言, 这里只验证 isShadow flag 与 config 完全对齐,
    // 短路逻辑只看 isShadow, 因此 flag 正确即等价于不进入 shadow 分支.
    val client = newBot(baseConfig.copy(shadow = false))
    assertFalse(client.isShadow, "shadow=false 时 isShadow 必须为 false, 否则会误把生产流量短路")
  }

  @Test
  fun `TencentBotConfig 默认 shadow=false 兼容旧 config_yaml`() {
    val cfg = TencentBotConfig(id = "i", appId = "a", token = "t", secret = "s")
    assertFalse(cfg.shadow, "默认值必须是 false, 旧 config.yaml 不写 shadow 字段时不能误开灰度")
  }

  @Test
  fun `getMediaUrlFromMediaInfo 空 fileInfo 返回 placeholder 不抛 protobuf 异常`() {
    // shadow MEDIA stub 返回 fileInfo="", uploadImage(ByteArray) 链路会随后调 getMediaUrlFromMediaInfo
    // 来包装 TencentOfflineImage; 若此处仍走 protobuf decode 会抛, 整条上传链路在 shadow 下崩溃.
    val url = getMediaUrlFromMediaInfo("")
    assertEquals("shadow://media", url)
  }

  @Test
  fun `MEDIA stub 与 getMediaUrlFromMediaInfo 一起跑通整条 ByteArray 上传链路 (shadow 下不出网)`() = runBlocking {
    val client = newBot()
    val result = client.callOpenapi(
      TencentEndpoint.PostGroupRichMessage,
      TencentMessageMediaInfo.serializer(),
      mapOf("group_openid" to "g1"),
    ) {}
    val media = result.getOrThrow()
    // 模拟 Contact.uploadImage(ByteArray) 的 .let { TencentOfflineImage(..., getMediaUrlFromMediaInfo(it.fileInfo)) }
    val packagedUrl = getMediaUrlFromMediaInfo(media.fileInfo)
    assertEquals("shadow://media", packagedUrl, "shadow 下 byte 上传整链不应抛, 必须能产出占位 URL")
  }
}
