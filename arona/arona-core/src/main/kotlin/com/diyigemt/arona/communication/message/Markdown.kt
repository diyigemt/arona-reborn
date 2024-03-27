package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.command.UserCommandSender
import io.ktor.http.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@DslMarker
annotation class MarkdownDsl

@MarkdownDsl
abstract class MarkdownElement {
  abstract fun build(): String
}
data class Markdown(
  val content: String = "",
) : MarkdownElement() {
  val children: MutableList<MarkdownElement> = mutableListOf()

  operator fun String.unaryPlus() {
    children.add(TextElement(this))
  }

  infix fun String.style(style: TextElement.TextElementStyle) {
    children.add(TextElement(this, style))
  }

  override fun build(): String {
    return children.joinToString("") {
      when (it) {
        is BlockElement, is ListElement -> "${it.build()}\n"
        is InlineCommandElement -> it.build()
        else -> it.build() + "\n"
      }
    }
  }
}

data class TitleElement(
  var content: String = "",
  var level: TitleElementLevel = TitleElementLevel.H1,
) : MarkdownElement() {

  operator fun String.unaryPlus() {
    content = this
  }

  override fun build(): String {
    return when (level) {
      TitleElementLevel.H1 -> "# $content"
      TitleElementLevel.H2 -> "## $content"
    }
  }

  enum class TitleElementLevel {
    H1,
    H2
  }
}

data class TextElement(
  var content: String = "",
  var style: TextElementStyle = TextElementStyle.Normal,
) : MarkdownElement() {

  infix fun String.style(style: TextElementStyle) {
    content = this
    this@TextElement.style = style
  }

  override fun build(): String {
    return when (style) {
      TextElementStyle.Normal -> content
      TextElementStyle.Bold -> "**$content**"
      TextElementStyle.Italic -> "_${content}_"
      TextElementStyle.StarItalic -> "*$content*"
      TextElementStyle.BoldItalic -> "***$content***"
      TextElementStyle.StrikeThrough -> "~~$content~~"
      TextElementStyle.BoldUnderline -> "__${content}__"
    }
  }

  enum class TextElementStyle {
    Normal, // 无
    Bold,   // 加粗
    Italic, // 斜体
    StarItalic, // 星号斜体
    BoldItalic, // 加粗斜体
    StrikeThrough,  // 删除线
    BoldUnderline   // 下划线加粗
  }
}

data class LinkElement(
  var href: String = "",
  var placeholder: String = "",
) : MarkdownElement() {

  infix fun String.to(other: String) {
    href = this
    placeholder = other
  }

  override fun build(): String {
    return if (placeholder.isNotEmpty()) {
      "[$placeholder]($href)"
    } else {
      "<$href>"
    }
  }
}

data class AtElement(
  val target: String,
) : MarkdownElement() {

  override fun build(): String {
    return "<@$target>"
  }
}

data class ImageElement(
  var href: String = "",
  var h: Int = 0,
  var w: Int = 0,
  var placeholder: String = "",
) : MarkdownElement() {
  override fun build(): String {
    return "![$placeholder #$w #$h]($href)"
  }
}

data class ListElementItem(
  var content: TextElement = TextElement(),
  var child: ListElement? = null,
) : MarkdownElement() {
  constructor(s: String) : this(TextElement(s))

  override fun build(): String {
    return if (child != null) {
      val tmp = child!!.build().split("\n").joinToString("\n") { "    $it" }
      "${content.build()}\n$tmp"
    } else {
      content.build()
    }
  }
}

data class ListElement(
  val content: MutableList<ListElementItem> = mutableListOf(),
  var hasIndex: Boolean = false,
) : MarkdownElement() {
  operator fun String.unaryPlus() {
    content.add(ListElementItem(this))
  }

  operator fun ListElement.unaryPlus() {
    content.add(ListElementItem(child = this))
  }

  infix fun String.style(style: TextElement.TextElementStyle) {
    this@ListElement.content.add(ListElementItem(TextElement(this, style)))
  }

  override fun build(): String {
    return content.mapIndexed { idx, it ->
      "${
        if (hasIndex) "$idx." else "-"
      } ${it.build()}"
    }.joinToString("\n")
  }
}

data class BlockElement(
  val content: MutableList<TextElement> = mutableListOf(),
) : MarkdownElement() {

  operator fun String.unaryPlus() {
    content.add(TextElement(this))
  }

  operator fun TextElement.unaryPlus() {
    this@BlockElement.content.add(this)
  }

  infix fun String.style(style: TextElement.TextElementStyle) {
    this@BlockElement.content.add(TextElement(this, style))
  }

  override fun build(): String {
    return content.joinToString("\n") { ">${it.build()}" }
  }
}

data class CodeBlockElement(
  val type: String,
  val content: MutableList<String> = mutableListOf("``` $type"),
) : MarkdownElement() {

  operator fun String.unaryPlus() {
    content.add(this)
  }

  override fun build(): String {
    content.add("```")
    return content.joinToString("\n") { it }
  }
}

data class DividerElement(
  val text: String = "",
) : MarkdownElement() {
  override fun build(): String {
    return "***"
  }
}

data class BrElement(
  val text: String = "",
) : MarkdownElement() {
  override fun build(): String {
    return "\u200B"
  }
}

data class InlineCommandElement(
  var command: String = "",
  var placeholder: String = "",
  var enter: Boolean = false,
  var reply: Boolean = true
) : MarkdownElement() {
  override fun build(): String {
    if (enter) {
      reply = false
    }
    return "[$placeholder](mqqapi://aio/inlinecmd?command=${command.encodeURLPath()}&reply=$reply&enter=$enter)"
  }
}

fun Markdown.title(block: TitleElement.() -> Unit) {
  children.add(TitleElement().apply(block))
}

fun Markdown.h1(text: String) {
  children.add(TitleElement(text))
}

fun Markdown.h2(text: String) {
  children.add(TitleElement(text, TitleElement.TitleElementLevel.H2))
}

fun Markdown.text(block: TextElement.() -> Unit) {
  children.add(TextElement().apply(block))
}

fun Markdown.at(target: String) {
  children.add(AtElement(target))
}

context(UserCommandSender)
fun Markdown.at() {
  children.add(AtElement(user.id))
}

fun Markdown.link(block: LinkElement.() -> Unit) {
  children.add(LinkElement().apply(block))
}

fun Markdown.image(block: ImageElement.() -> Unit) {
  children.add(ImageElement().apply(block))
}

fun Markdown.block(block: BlockElement.() -> Unit) {
  children.add(BlockElement().apply(block))
}

fun Markdown.code(type: String, block: CodeBlockElement.() -> Unit) {
  children.add(CodeBlockElement(type).apply(block))
}

fun BlockElement.text(block: TextElement.() -> Unit) {
  content.add(TextElement().apply(block))
}

fun Markdown.divider() {
  children.add(DividerElement())
}

fun Markdown.br() {
  children.add(BrElement())
}

fun Markdown.inline(block: InlineCommandElement.() -> Unit) {
  children.add(InlineCommandElement().apply(block))
}

fun Markdown.list(block: ListElement.() -> Unit) {
  children.add(ListElement().apply(block))
}

fun ListElement.list(title: String, block: ListElement.() -> Unit) {
  content.add(ListElementItem(content = TextElement(title), child = ListElement().apply(block)))
}

fun ListElement.list(title: TextElement, block: ListElement.() -> Unit) {
  content.add(ListElementItem(content = title, child = ListElement().apply(block)))
}

fun tencentCustomMarkdown(block: Markdown.() -> Unit): TencentCustomMarkdown {
  return TencentCustomMarkdown(Markdown().apply(block).build())
}
