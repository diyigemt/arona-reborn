package com.diyigemt.arona.arona

import com.diyigemt.arona.communication.TencentApiErrorResp
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.TencentCustomMarkdown
import com.diyigemt.arona.communication.message.TencentImage
import io.ktor.http.encodeURLPath

// 腾讯发送接口返回该 code 表示"消息内容违规"(图片/文本被风控). 此时通用的"message: ..."提示对用户无意义,
// 若违规内容是 arona 图床的图片, 改为引导用户去直连页查看原图.
internal const val CONTENT_VIOLATION_CODE = 40034006

// arona 自有图床直链前缀, 以及违规时引导用户跳转的直连页. 直连页以图片名作为 name 查询参数定位资源.
internal const val ARONA_IMAGE_PREFIX = "https://arona.cdn.diyigemt.com/image/"
internal const val TUTORIAL_DIRECT_URL = "https://tutorial.arona.diyigemt.com/home?name="

// 仅匹配 markdown 图片语法 ![alt](url) 中的目标 url, 不匹配普通链接 [text](url)——后者按需求不算"包含图片".
// url 段用 [^\s)]+: markdown 构造时英文括号已被转义为 %28/%29, 不含真正的 ')', 故不会被提前截断.
private val AronaMarkdownImageRegex = Regex("""!\[[^\]]*]\((${Regex.escape(ARONA_IMAGE_PREFIX)}[^\s)]+)\)""")

/**
 * 从 arona 图床直链中提取图片名(去掉路径、查询/锚点与扩展名)。非 arona 图床或提取结果为空时返回 null。
 *
 * 例: `https://arona.cdn.diyigemt.com/image/some/日服人权.png` -> `日服人权`。
 * 仅去掉最后一级扩展名(`foo.tar.gz` -> `foo.tar`); 无扩展名时原样返回(`日服人权` -> `日服人权`)。
 */
internal fun extractAronaImageNameFromUrl(url: String): String? {
  if (!url.startsWith(ARONA_IMAGE_PREFIX)) return null
  val fileName = url
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('/')
    .substringBeforeLast('.') // 无 '.' 时 substringBeforeLast 默认返回原串, 正好保留无扩展名的文件名
  return fileName.takeIf { it.isNotBlank() }
}

/**
 * 按消息链顺序找出第一张命中 arona 图床的图片名:
 *  - [TencentImage] 元素直接取其 url;
 *  - [TencentCustomMarkdown] 仅从 ![..](url) 图片语法里取 url。
 * 都没有命中则返回 null。
 */
internal fun extractAronaImageName(chain: MessageChain): String? {
  for (message in chain) {
    val name = when (message) {
      is TencentImage -> extractAronaImageNameFromUrl(message.url)
      is TencentCustomMarkdown -> AronaMarkdownImageRegex.findAll(message.content)
        .mapNotNull { extractAronaImageNameFromUrl(it.groupValues[1]) }
        .firstOrNull()
      else -> null
    }
    if (name != null) return name
  }
  return null
}

/**
 * 构造发送失败时回复给用户的提示消息。
 *
 * 当错误为"消息内容违规"且本次发送的内容里含有 arona 图床图片时, 引导用户到直连页查看原图;
 * 其余情况沿用历史通用提示(两行: "错误发生" / "message: ...")。两段 [MessageChainBuilder.append]
 * 在上行 content 中由 [com.diyigemt.arona.communication.message.TencentMessageBuilder] 以 "\n" 连接, 故天然两行。
 */
internal fun buildErrorNotice(
  sourceId: String,
  source: TencentApiErrorResp,
  sentChain: MessageChain,
): MessageChain {
  val imageName = if (source.code == CONTENT_VIOLATION_CODE) extractAronaImageName(sentChain) else null
  return MessageChainBuilder(sourceId).apply {
    if (imageName != null) {
      append("错误发生，消息内容违规，请直接使用直连查看：")
      // 图片名按需求原样拼接, 不做 query 参数编码: 图床命名受控(中文/字母/数字), 直连页按原值匹配。
      append("$TUTORIAL_DIRECT_URL${imageName.encodeURLPath()}")
    } else {
      append("错误发生")
      append("message: ${source.message}")
    }
  }.build()
}
