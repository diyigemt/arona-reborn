package com.diyigemt.arona.chatbot

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 图片纯函数: 魔数嗅探、只读头部的尺寸探测、表情启发式与入库判定.
class ImagesTest {
  private fun png(width: Int, height: Int): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

  @Test
  fun `魔数识别四种格式, 其它为 null`() {
    assertEquals("image/png", sniffMime(png(1, 1)))
    assertEquals("image/jpeg", sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    assertEquals("image/gif", sniffMime("GIF89a".encodeToByteArray()))
    assertEquals("image/webp", sniffMime("RIFF0000WEBPVP8 ".encodeToByteArray()))
    assertNull(sniffMime("<html>".encodeToByteArray()))
    assertNull(sniffMime(ByteArray(0)))
    assertNull(sniffMime("RIFF".encodeToByteArray()), "截断的 RIFF 头不越界")
  }

  @Test
  fun `PNG 头部探测尺寸, 非图片为 null`() {
    assertEquals(640 to 2, probeDimensions(png(640, 2)))
    assertNull(probeDimensions("not an image".encodeToByteArray()))
  }

  @Test
  fun `表情启发式 - 最长边与长宽比, 尺寸未知放行`() {
    assertTrue(looksLikeSticker(300 to 300, maxSide = 1024, maxAspect = 3.0))
    assertFalse(looksLikeSticker(1080 to 1920, maxSide = 1024, maxAspect = 3.0), "手机截图")
    assertFalse(looksLikeSticker(640 to 2, maxSide = 1024, maxAspect = 3.0), "长图")
    assertTrue(looksLikeSticker(null, maxSide = 1024, maxAspect = 3.0), "WebP 无 reader 时交给模型")
    assertFalse(looksLikeSticker(0 to 10, maxSide = 1024, maxAspect = 3.0))
  }

  @Test
  fun `入库判定 - 不够格一律 captioned, 够格走审核流`() {
    val meme = StickerAnalysis(isMeme = true, summary = "s", tags = listOf("t"), nsfwRisk = "low")
    fun status(analysis: StickerAnalysis = meme, sticker: Boolean = true, auto: Boolean = false, dims: Pair<Int, Int>? = 300 to 300, bytes: Int = 1024) =
      captureStatus(sticker, auto, analysis, dims, bytes, maxSide = 1024, maxAspect = 3.0, maxBytes = 2048L)

    assertEquals(StickerStatus.PENDING, status())
    assertEquals(StickerStatus.READY, status(auto = true))
    assertEquals(StickerStatus.PENDING, status(analysis = meme.copy(nsfwRisk = "mid"), auto = true), "autoApprove 只放行 low")
    assertEquals(StickerStatus.CAPTIONED, status(sticker = false), "该群未开收集")
    assertEquals(StickerStatus.CAPTIONED, status(analysis = meme.copy(isMeme = false)), "非表情")
    assertEquals(StickerStatus.CAPTIONED, status(analysis = meme.copy(nsfwRisk = "high")))
    assertEquals(StickerStatus.CAPTIONED, status(dims = 1080 to 1920), "超尺寸")
    assertEquals(StickerStatus.CAPTIONED, status(bytes = 4096), "超字节")
  }

  @Test
  fun `data URL 与扩展名`() {
    val img = DownloadedImage(byteArrayOf(1, 2, 3), "image/jpeg")
    assertEquals("data:image/jpeg;base64,AQID", img.dataUrl())
    assertEquals("jpg", img.extension)
    assertEquals(64, img.sha256.length)
  }
}
