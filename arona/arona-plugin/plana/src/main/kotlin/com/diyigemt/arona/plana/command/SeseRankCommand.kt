package com.diyigemt.arona.plana.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plana.PluginMain
import com.diyigemt.arona.plana.db.PlanaRepository
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.int
import kotlin.math.ceil
import kotlin.math.max

/**
 * 累计涩图次数排行, 全局聚合, 每页 10 条, 倒序。可选页码参数, 默认第 1 页。
 */
@Suppress("unused")
class SeseRankCommand : AbstractCommand(
  PluginMain,
  "色色排行",
  description = "查看累计涩图次数排行",
  help = "/色色排行 [页码]"
) {
  private val page by argument(name = "页码", help = "要查看的页码, 从 1 开始").int().default(1)

  suspend fun UserCommandSender.seseRank() {
    val total = PlanaRepository.rankTotal()
    val lastPage = max(1, ceil(total.toDouble() / PAGE_SIZE).toInt())
    val current = page.coerceIn(1, lastPage)
    val rows = PlanaRepository.rankPage(current, PAGE_SIZE)
    val startRank = (current - 1) * PAGE_SIZE

    val md = tencentCustomMarkdown {
      h1("色色排行")
      if (rows.isEmpty()) {
        +"暂无记录"
      } else {
        rows.forEachIndexed { idx, row ->
          // 行内 at 渲染, 与 AtElement 的输出一致; 单行内同时呈现名次、@用户、次数。
          +"${startRank + idx + 1}. <qqbot-at-user id=\"${row.userId}\" />\t\t${row.count}次"
        }
      }
      +""
      +"    当前第${current}页"
    }

    val hasPrev = current > 1
    val hasNext = current < lastPage
    if (!hasPrev && !hasNext) {
      sendMessage(md)
      return
    }

    val kb = tencentCustomKeyboard {
      row {
        if (hasPrev) {
          button("prev") {
            render { label = "上一页" }
            action { data = "/色色排行 ${current - 1}"; enter = true }
          }
        }
        if (hasNext) {
          button("next") {
            render { label = "下一页" }
            action { data = "/色色排行 ${current + 1}"; enter = true }
          }
        }
      }
    }
    sendMessage(MessageChainBuilder().append(md).append(kb).build())
  }

  private companion object {
    const val PAGE_SIZE = 10
  }
}
