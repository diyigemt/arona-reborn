package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.AbstractCommandSender
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.contact.Contact.Companion.toContactDocumentOrNull
import com.diyigemt.arona.communication.contact.User.Companion.toUserDocumentOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.Permission.Companion.testPermission
import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.currentTime
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

internal sealed class DynamicCommandExecutor(
  val path: List<String>,
  private val primaryName: String,
  protected val parentSignature: CommandSignature,
) : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(primaryName)) {
  val runningCounter = atomic(0)
  private val minimumSignature: List<CommandSignature> = run {
    var ps = parentSignature
    val result = mutableListOf<CommandSignature>()
    val p = path.toMutableList().also {
      it.removeFirst()
    }
    for (it in p) {
      val tmp = ps.children.firstOrNull { c -> c.primaryName == it } ?: break
      result.add(tmp)
      ps = tmp
    }
    result
  }

  protected fun createCommandInstance() = when (this) {
    is DynamicStaticCommandExecutor -> parentSignature.createInstance()
    // Contextual 路径每次新建实例避免跨调用污染; 用 CommandSignature.instanceFactory() 缓存
    // 全可选构造器, 跳过 KClass.createInstance() 内部每次 constructors.singleOrNull 的反射查找.
    is DynamicContextualCommandExecutor -> parentSignature.instanceFactory().also {
      var c = it
      for (child in minimumSignature) {
        val tmp = child.instanceFactory()
        c.subcommands(tmp)
        c = tmp
      }
    }
  }

  protected suspend fun hasCommandPermission(
    caller: CommandSender,
    args: List<String>,
  ): Boolean {
    val document = caller.subject?.toContactDocumentOrNull()
    val user = caller.user?.toUserDocumentOrNull()
    return if (document != null) {
      val u = document.findContactMemberOrNull(user?.id ?: "") ?: ContactMember(
        "",
        "",
        listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID)
      )
      val environment = mapOf(
        "time" to currentTime().substringBeforeLast(":"),
        "date" to currentDate(),
        "datetime" to currentDateTime(),
        "param1" to (args.getOrNull(0) ?: ""),
        "param2" to (args.getOrNull(1) ?: "")
      )
      return permission.testPermission(u, document.policies, environment)
    } else {
      true
    }
  }

  abstract val runningWorkers: Int
  abstract val idleWorkers: Int
  abstract val pendingTasks: Int
  abstract val permission: Permission

  abstract suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult>
}

internal class DynamicStaticCommandExecutor(
  path: List<String>,
  primaryName: String,
  parentSignature: CommandSignature,
) : DynamicCommandExecutor(
  path,
  primaryName,
  parentSignature,
) {
  override val idleWorkers
    get() = 1 - runningWorkers
  override val pendingTasks
    get() = 0
  override val runningWorkers
    get() = runningCounter.value

  // Static 路径的 worker 是 object 单例 + 通过 CommandSignature.createInstance() 递归挂载的子命令实例,
  // 整体长期复用. Clikt 5 的 context {} 是严格累积式 (configureContext 把新闭包包在旧闭包外层),
  // 若每次 execute 都调用一次, _contextConfig 链长会随调用次数线性增长, parse() 通过 resetContext
  // 遍历整条链, 最终撑爆内存并把 parse 退化为 O(N). 解决: 构造期一次性挂载 context {}, 闭包内通过
  // shared state 的 pendingCallContext 读取 per-call 数据; lock.withLock 串行保证 holder 在 parse 期间稳定可读.
  // Mutex 仍必须覆盖 "parse + run" 全段: option/argument 委托和子命令实例的状态不能跨调用串扰.
  //
  // shared state 按 worker (即 object 单例) 维度共享, 而不是 per-executor:
  // 同一 object 命令可能被多次 register/unregister 触发 executor 重建; 若每个 executor 各自挂载自己的
  // pendingCallContext 闭包, 后建的 executor 会覆盖前者的 _contextConfig, 导致前者残留的 in-flight 请求
  // (例如 reload 窗口) 在 checkNotNull 上抛错. 通过 StaticWorkerState 维持 per-singleton 共享, 使所有
  // 引用同一 worker 的 executor 共用同一把 Mutex 和 holder, 无论何时执行都读到当前调用的 obj.
  //
  // 反射重置 _contextConfig 也只在该 singleton 第一次被绑定时触发, 不会随 executor 重建累积.
  // 反射只在 init 路径调用 (非热路径); Clikt 5.x 后续若暴露 reset/replace 官方 API, 可平移替换.
  private val worker: AbstractCommand
  private val sharedState: StaticWorkerState
  override val permission: Permission

  init {
    assert(parentSignature.clazz.objectInstance != null)
    val cmd = createCommandInstance()
    worker = cmd
    sharedState = staticWorkerStates.computeIfAbsent(cmd) { command ->
      command.resetContextConfig()
      val state = StaticWorkerState()
      command.context {
        obj = checkNotNull(state.pendingCallContext) {
          "pendingCallContext 必须在 lock 持有期间被赋值后再触发 parse"
        }
        terminal = commandTerminal
        localization = crsiveLocalization
      }
      state
    }
    permission = cmd.permission
  }

  override suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult> {
    if (checkPermission && !hasCommandPermission(caller, args)) {
      return async {
        CommandExecuteResult.PermissionDenied(worker)
      }
    }
    return async {
      runningCounter.incrementAndGet()
      try {
        sharedState.lock.withLock {
          sharedState.pendingCallContext = DynamicCommandContext(caller as AbstractCommandSender, parentSignature)
          try {
            worker.parseAndCatch(args)
          } finally {
            sharedState.pendingCallContext = null
          }
        }
      } finally {
        runningCounter.decrementAndGet()
      }
    }
  }
}

// 按 object 单例 worker 共享的可变状态. 同一 worker 不论被多少 DynamicStaticCommandExecutor 实例引用,
// 都通过这一份 holder 串行 + 取 per-call obj. ConcurrentHashMap 永不主动清理: static command 数量天然
// 有界, 且 worker 是 object 单例本身永驻; 加 WeakHashMap 反而引入并发复杂度.
private class StaticWorkerState {
  @Volatile var pendingCallContext: DynamicCommandContext? = null
  val lock = Mutex()
}

private val staticWorkerStates = ConcurrentHashMap<AbstractCommand, StaticWorkerState>()

// 通过反射把 BaseCliktCommand._contextConfig 重置为空闭包. 仅 DynamicStaticCommandExecutor 在
// 绑定 singleton 阶段调用, 防止跨 executor 生命周期对 object 单例的累积污染. Clikt 5.x 后续若暴露
// 官方 reset/replace API, 直接替换此处实现即可.
//
// 字段查找用 lazy: 反射的失败影响范围严格收敛在 static 修复路径, 不波及 DynamicContextualCommandExecutor
// 的 execute 扩展函数 (即使共处一个 file facade).
private val EMPTY_CONTEXT_CONFIG: Context.Builder.() -> Unit = {}
private val contextConfigField: Field by lazy {
  var cls: Class<*>? = BaseCliktCommand::class.java
  var found: Field? = null
  while (cls != null && found == null) {
    found = cls.declaredFields.firstOrNull { it.name == "_contextConfig" }
    cls = cls.superclass
  }
  checkNotNull(found) {
    "Clikt BaseCliktCommand._contextConfig 反射查找失败, 升级版本后字段是否改名? 请同步更新此处"
  }.apply { isAccessible = true }
}

private fun BaseCliktCommand<*>.resetContextConfig() {
  contextConfigField.set(this, EMPTY_CONTEXT_CONFIG)
}

// 仅做 parse + 异常归类, 不触碰 context. Static 路径的 context 已在构造期一次性挂载,
// 重复调用会触发 _contextConfig 累积 (见 DynamicStaticCommandExecutor 的注释).
internal fun AbstractCommand.parseAndCatch(
  args: List<String>,
): CommandExecuteResult {
  return runCatching {
    parse(args)
    CommandExecuteResult.Success(this)
  }.getOrElse {
    when (it) {
      is MissingArgument -> CommandExecuteResult.UnmatchedSignature(it, this)
      is TimeoutCancellationException -> CommandExecuteResult.Success(this)
      is PrintHelpMessage -> CommandExecuteResult.UnmatchedSignature(it, this)
      else -> CommandExecuteResult.ExecutionFailed(it, this)
    }
  }
}

// 仅给 DynamicContextualCommandExecutor 用: 它每次 createCommandInstance() 新建实例,
// 这里追加的 context 闭包随实例 GC 释放, 不存在累积.
// Clikt 5 的 obj 是 Any?, 用类型化容器替代旧 fork 的 mutableMapOf("caller", "signature");
// AbstractCommand 子类通过 requireObject<DynamicCommandContext>() 一次拿全.
internal fun AbstractCommand.execute(
  caller: CommandSender,
  signature: CommandSignature,
  args: List<String>,
): CommandExecuteResult {
  context {
    obj = DynamicCommandContext(caller as AbstractCommandSender, signature)
    terminal = commandTerminal
    localization = crsiveLocalization
  }
  return parseAndCatch(args)
}

internal class DynamicContextualCommandExecutor(
  path: List<String>,
  primaryName: String,
  parentSignature: CommandSignature,
) : DynamicCommandExecutor(
  path,
  primaryName,
  parentSignature,
) {
  // Contextual 路径对应 class 命令: createCommandInstance() 每次返回新实例, 天然不存在跨调用污染.
  // 实例构造走 CommandSignature.instanceFactory() 缓存全可选构造器, 避免每次 execute 重复反射查找.
  // 因此既不需要 Mutex 也不需要 worker pool: 并发上限交给上层 Dispatcher;
  // pendingTasks 永久 0, idleWorkers 报 Int.MAX_VALUE 表示没有人为容量阻塞.
  // (旧 fork 时代用 worker pool + context2 重置维持复用; Clikt 5 后 context2 不存在, 用一次性实例代价更小.)
  override val permission: Permission = createCommandInstance().permission
  override val idleWorkers
    get() = Int.MAX_VALUE
  override val pendingTasks
    get() = 0
  override val runningWorkers
    get() = runningCounter.value

  override suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult> {
    if (checkPermission && !hasCommandPermission(caller, args)) {
      return async {
        CommandExecuteResult.PermissionDenied(createCommandInstance())
      }
    }
    return async {
      runningCounter.incrementAndGet()
      try {
        createCommandInstance().execute(caller, parentSignature, args)
      } finally {
        runningCounter.decrementAndGet()
      }
    }
  }
}
