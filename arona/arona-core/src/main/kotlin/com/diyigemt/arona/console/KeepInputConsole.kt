package com.diyigemt.arona.console

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import com.diyigemt.arona.utils.commandLineLogger
import com.github.ajalt.mordant.terminal.ConversionResult
import com.github.ajalt.mordant.terminal.Terminal
// Mordant 3: prompt 是 PromptKt 顶层 extension, 需显式 import 才能在 Terminal.confirm 里用.
import com.github.ajalt.mordant.terminal.prompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.fusesource.jansi.AnsiConsole
import org.jline.reader.*
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString

internal val lineReader: LineReader by lazy {
  AnsiConsole.systemInstall()
  // 不注册自定义 signalHandler: 曾经的空 handler 会把 SIGINT 整个吞掉,
  // 控制台循环意外退出后 Ctrl+C 连进程都杀不掉, 只能 kill -9. 默认 SIG_DFL 下
  // readLine 期间 Ctrl+C 走 UserInterruptException (清行), 其余时刻正常终止 JVM.
  val terminal = TerminalBuilder
    .builder()
    .jna(true)
    .jansi(true)
    .system(true)
    .build()
  LineReaderBuilder.builder().terminal(terminal).completer { _, _, candidates ->
    candidates.addAll(
      CommandMain.registeredCommands().map {
        val name = it.commandName
        Candidate(AttributedString.stripAnsi(name), name, null, null, null, null, true)
      }
    )
  }.build()
}

fun Terminal.confirm(
  prompt: String,
  default: String = "Y",
  showDefault: Boolean = true,
  showChoices: Boolean = true,
  hideInput: Boolean = false,
  promptSuffix: String = ": ",
  invalidChoiceMessage: String = "Invalid value, choose from ",
) = prompt(
  prompt, default == "Y", showDefault, showChoices, hideInput, listOf(true, false), promptSuffix,
  invalidChoiceMessage
) {
  return@prompt if (it in listOf("Y", "N")) {
    ConversionResult.Valid(it == "Y")
  } else {
    ConversionResult.Invalid("Y or N")
  }
  // prompt 被 Ctrl+C / EOF 中断时返回 null, 调用方都拿 confirm 当危险操作的闸门, 视为拒绝;
  // 只对 null 兜底, 非 Boolean 类型错误仍然抛出.
} as Boolean? ?: false

suspend fun launchConsole() {
  while (true) {
    // 协程取消时线程中断可能被 JLine 包装成 UserInterruptException, 单靠 catch 分不清
    // 用户 Ctrl+C 和关停取消; 每轮开头显式检查, 保证 closeAronaPools 后循环能退出.
    currentCoroutineContext().ensureActive()
    try {
      val input = lineReader.readLine("> ")
      CommandMain.run(
        input.split(" ").filterNot { it.isBlank() }
      )
    } catch (_: UserInterruptException) {
      // readLine 期间的 Ctrl+C 只丢弃当前输入行, 重新出提示符; 命令执行期间的
      // Ctrl+C 走默认 SIGINT 语义 (终止 JVM). 常规退出用 exit 命令.
    } catch (_: EndOfFileException) {
      // stdin 已关闭 (Ctrl+D / systemd 无 TTY), 继续读只会立刻再抛, 退出避免忙循环.
      return
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      commandLineLogger.error("控制台读取/执行命令异常", e)
      // 终端持续故障时避免异常忙循环刷爆日志.
      delay(100)
    }
  }
}

fun appendConsole(message: String? = null) {
  // JLine printAbove(null) 会在 String.endsWith 处 NPE, 兜底成空行.
  lineReader.printAbove(message ?: "")
}

class CustomAppender : AppenderBase<ILoggingEvent>() {
  private val encoder: LayoutWrappingEncoder<ILoggingEvent> = LayoutWrappingEncoder()
  private val layout: PatternLayout = PatternLayout()
  private var pattern: String = "%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %logger{72} - %msg%n"

  override fun start() {
    super.start()
    encoder.context = context
    encoder.layout = layout
    layout.context = context
    layout.pattern = pattern
    layout.start()
    encoder.start()
  }

  override fun stop() {
    encoder.stop()
    super.stop()
  }

  override fun append(event: ILoggingEvent) {
    lineReader.printAbove(layout.doLayout(event))
  }
}
