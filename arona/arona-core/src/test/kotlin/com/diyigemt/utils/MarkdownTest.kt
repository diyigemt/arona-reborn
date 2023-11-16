package com.diyigemt.utils

//import com.aspose.html.HTMLDocument
//import com.aspose.html.converters.Converter
//import com.aspose.html.saving.ImageSaveOptions
import java.io.ByteArrayInputStream
import kotlin.test.Test


class MarkdownTest {
  @Test
  fun testConvertMarkdown() {
    val savePath = "./convert.png"
    ByteArrayInputStream("""
**test**

# 这是一级标题

- [ ] 未完成
- [x] 已完成
    """.trimIndent().toByteArray()).use {
//      val document = Converter.convertMarkdown(it, "test") ?: return
//      // Convert Markdown to HTML
//      runCatching {
//        Converter.convertHTML(document.apply { body.style.backgroundColor = "white" }, ImageSaveOptions(), savePath)
//        document.dispose()
//      }
    }
  }
  @Test
  fun testDecodeText() {
    val s = b(intArrayOf(39, 1, 18, 4, 66, 29, 28, 84, 11, 11, 13, 17, 71, 27, 22, 84, 26, 22, 20, 0, 0, 32))
  }
  private fun jL(var0: String): String {
    val var1 = "node.txt"
    val var2 = StringBuilder(var0.length)
    for (var3 in var0.indices) {
      var2.append((var0[var3].code xor "node.txt"[var3 % "node.txt".length].code).toChar())
    }
    return var2.toString()
  }

  private fun b(var0: IntArray?): String? {
    return if (var0 == null) {
      null
    } else if (var0.isEmpty()) {
      ""
    } else {
      var var2 = (var0.size - 1) % 97
      val var3 = java.lang.StringBuilder(var0.size - 1)
      for (var4 in 0 until var0.size - 1) {
        var3.append(var0[var4].toChar())
        var2 = (var2 + var0[var4]) % 97
      }
      if (var0[var0.size - 1] != var2) {
        throw IllegalStateException()
      } else {
        jL(var3.toString())
      }
    }
  }
}
