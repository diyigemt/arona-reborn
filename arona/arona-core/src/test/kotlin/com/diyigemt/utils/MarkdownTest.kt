package com.diyigemt.utils

import com.diyigemt.arona.communication.message.*
import kotlin.test.Test


class MarkdownTest {
  @Test
  fun testBuildMarkdownDsl() {
    val test = tencentCustomMarkdown {
      title {
        content = "一级标题"
      }
      title {
        content = "二级标题"
        level = TitleElement.TitleElementLevel.H2
      }
      +"文字测试"
      text {
        content = "文字测试2"
        style = TextElement.TextElementStyle.Bold
      }
      "文字测试3" style TextElement.TextElementStyle.Italic
      br()
      link {
        href = "https://114.514.com"
      }
      link {
        "https://114.514.com" to "腾讯网"
      }
      image {
        href = "https://114.514.com"
        w = 100
        h = 100
      }
      block {
        + "测试"
        + "引用"
        text {
          content = "测试"
        }
        text {
          "测试2" style TextElement.TextElementStyle.BoldItalic
        }
      }
      divider()
      list {
        + "测试列表"
        + "无序列表"
      }
      list {
        + "测试列表"
        + "无序列表"
        hasIndex = true
      }
      list {
        list("测试嵌套") {
          "这是嵌套的列表1-1" style TextElement.TextElementStyle.StrikeThrough
          "这是嵌套的列表1-2" style TextElement.TextElementStyle.StarItalic
        }
        list("测试嵌套2") {
          "这是嵌套的列表2-1" style TextElement.TextElementStyle.StrikeThrough
          "这是嵌套的列表2-2" style TextElement.TextElementStyle.StarItalic
        }
      }
    }
    println(test.content)
  }
  @Test
  fun testKb() {
    val kb = tencentCustomKeyboard("") {
      listOf(1,2,3,4,5,6).windowed(2, 2, true).forEach { r ->
        r.forEach { c ->
          row {
            button(c.toString())
          }
        }
      }
    }
    println(kb)
  }
}
