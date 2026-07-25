package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.message.TencentGuildMessage
import com.diyigemt.arona.communication.message.TencentMessageBuilder
import com.diyigemt.arona.communication.message.TencentMessageMediaInfo
import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.diyigemt.arona.communication.message.TencentOfflineMedia
import com.diyigemt.arona.communication.message.TencentRichMessageType
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

// uploadMedia 是富媒体全类型 (视频/语音/文件) 的唯一入口, 与 uploadImage 共用 postRichMedia 咽喉.
// 本测试从 StubBot 记录的请求断言路由与 wire body (file_type/url/file_data/srv_send_msg),
// 同时钉住 uploadImage 重构后的行为回归 —— 两条链路共用 helper, 单测一条会漏掉另一条的回退.
@OptIn(ExperimentalEncodingApi::class)
class UploadMediaTest {

  private fun botWithMediaResult(
    fileInfo: String = "file-info",
    fileUuid: String = "file-uuid",
    ttl: Long = 60L,
  ) = StubBot(callOpenapiResult = Result.success(TencentMessageMediaInfo(fileInfo, fileUuid, ttl)))

  private fun StubOpenapiCall.body() = Json.parseToJsonElement(request.body as String).jsonObject

  @Test
  fun `群视频 url 上传路由与 wire body 正确`() {
    val bot = botWithMediaResult()
    try {
      val media = runBlocking {
        GroupImpl(bot, bot.coroutineContext, "group-1")
          .uploadMedia("https://example.test/video.mp4", TencentRichMessageType.VIDEO)
      }

      assertEquals(TencentRichMessageType.VIDEO, media.mediaType)
      assertEquals("file-info", media.resourceId)
      assertEquals("file-uuid", media.resourceUuid)
      assertEquals(60L, media.ttl)
      assertEquals("https://example.test/video.mp4", media.url)

      val call = bot.calls.single()
      assertEquals(TencentEndpoint.PostGroupRichMessage, call.endpoint)
      assertEquals("group-1", call.placeholders["group_openid"])
      assertEquals(HttpMethod.Post, call.request.method)
      val body = call.body()
      assertEquals(2, body["file_type"]!!.jsonPrimitive.int)
      assertEquals("https://example.test/video.mp4", body["url"]!!.jsonPrimitive.content)
      assertEquals(false, body["srv_send_msg"]!!.jsonPrimitive.boolean)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `单聊文件 bytes 上传携带 base64 file_data`() {
    val bot = botWithMediaResult()
    val bytes = byteArrayOf(1, 2, 3, 4)
    try {
      val media = runBlocking {
        FriendUserImpl(bot, bot.coroutineContext, "friend-1", null)
          .uploadMedia(bytes, TencentRichMessageType.FILE)
      }

      assertEquals(TencentRichMessageType.FILE, media.mediaType)
      assertEquals("file-info", media.resourceId)

      val call = bot.calls.single()
      assertEquals(TencentEndpoint.PostFriendRichMessage, call.endpoint)
      assertEquals("friend-1", call.placeholders["openid"])
      val body = call.body()
      assertEquals(4, body["file_type"]!!.jsonPrimitive.int)
      assertEquals(Base64.encode(bytes), body["file_data"]!!.jsonPrimitive.content)
      assertEquals(false, body["srv_send_msg"]!!.jsonPrimitive.boolean)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `频道侧 uploadMedia 抛异常且不出网`() {
    val bot = botWithMediaResult()
    try {
      val guild = EmptyGuildImpl(bot, "guild-1")
      runBlocking {
        assertFailsWith<UnsupportedOperationException> {
          guild.uploadMedia("https://example.test/video.mp4", TencentRichMessageType.VIDEO)
        }
        assertFailsWith<UnsupportedOperationException> {
          guild.uploadMedia(byteArrayOf(1), TencentRichMessageType.VOICE)
        }
      }
      assertEquals(0, bot.attempts, "不支持的 Contact 必须在进入 openapi 前拦截")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `群成员 uploadMedia 委托到 C2C 富媒体路由`() {
    val bot = botWithMediaResult()
    try {
      val group = GroupImpl(bot, bot.coroutineContext, "group-1")
      val member = GroupMemberImpl(bot.coroutineContext, "member-1", group)
      val media = runBlocking {
        member.uploadMedia("https://example.test/voice.silk", TencentRichMessageType.VOICE)
      }

      assertEquals(TencentRichMessageType.VOICE, media.mediaType)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.PostFriendRichMessage, call.endpoint)
      assertEquals("member-1", call.placeholders["openid"])

      // bytes 版是独立 override, 单测 url 版护不住它.
      runBlocking { member.uploadMedia(byteArrayOf(5, 6), TencentRichMessageType.FILE) }
      val bytesCall = bot.calls[1]
      assertEquals(TencentEndpoint.PostFriendRichMessage, bytesCall.endpoint)
      assertEquals("member-1", bytesCall.placeholders["openid"])
    } finally {
      bot.close()
    }
  }

  @Test
  fun `uploadMedia 失败直接透传不做下载回落`() {
    val failure = IllegalStateException("upload failed")
    val bot = StubBot(callOpenapiResult = Result.failure(failure))
    try {
      val thrown = runBlocking {
        assertFailsWith<IllegalStateException> {
          GroupImpl(bot, bot.coroutineContext, "group-1")
            .uploadMedia("https://example.test/video.mp4", TencentRichMessageType.VIDEO)
        }
      }
      // uploadImage(url) 的下载回落会触达 StubBot.client 并 error; 走到这里且异常同一, 证明没回落.
      assertSame(failure, thrown)
      assertEquals(1, bot.attempts)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `图片与富媒体同链 build 群分支拒绝而频道分支忽略`() {
    val builder = TencentMessageBuilder()
      .append(TencentOfflineImage(resourceId = "image-info", resourceUuid = "image-uuid", ttl = 60L, url = "https://example.test/a.png"))
      .append(
        TencentOfflineMedia(
          mediaType = TencentRichMessageType.VIDEO,
          resourceId = "video-info",
          resourceUuid = "video-uuid",
          ttl = 60L,
        )
      )

    assertFailsWith<IllegalArgumentException>("群/单聊 wire 只有一个富媒体槽位, 必须 fail-fast") {
      builder.build(isPrivateChannel = false)
    }
    // 频道协议没有 media 槽位, media 被忽略, 不应误伤.
    assertIs<TencentGuildMessage>(builder.build(isPrivateChannel = true))
  }

  @Test
  fun `uploadImage 重构后两条路由行为回归`() {
    val bot = botWithMediaResult()
    val bytes = byteArrayOf(9, 8, 7)
    try {
      runBlocking {
        GroupImpl(bot, bot.coroutineContext, "group-image").uploadImage("https://example.test/image.png")
        FriendUserImpl(bot, bot.coroutineContext, "friend-image", null).uploadImage(bytes)
      }

      assertEquals(2, bot.calls.size)
      val groupCall = bot.calls[0]
      assertEquals(TencentEndpoint.PostGroupRichMessage, groupCall.endpoint)
      assertEquals("group-image", groupCall.placeholders["group_openid"])
      assertEquals(1, groupCall.body()["file_type"]!!.jsonPrimitive.int)
      assertEquals("https://example.test/image.png", groupCall.body()["url"]!!.jsonPrimitive.content)

      val friendCall = bot.calls[1]
      assertEquals(TencentEndpoint.PostFriendRichMessage, friendCall.endpoint)
      assertEquals("friend-image", friendCall.placeholders["openid"])
      assertEquals(1, friendCall.body()["file_type"]!!.jsonPrimitive.int)
      assertEquals(Base64.encode(bytes), friendCall.body()["file_data"]!!.jsonPrimitive.content)
    } finally {
      bot.close()
    }
  }
}
