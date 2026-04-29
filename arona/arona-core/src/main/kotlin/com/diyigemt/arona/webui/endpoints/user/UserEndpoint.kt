package com.diyigemt.arona.webui.endpoints.user

import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.database.MongoWriteOutcome
import com.diyigemt.arona.database.RedisPrefixKey
import com.diyigemt.arona.database.classify
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.MongoUserDocument
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.IpRateLimiter
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.internalServerError
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.*
import com.diyigemt.arona.webui.event.auditOrAllow
import com.diyigemt.arona.webui.event.isBlock
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.github.crackthecodeabhi.kreds.args.SetOption
import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

@Serializable
internal data class AuthResp(
  val status: Int, // 0 1 2 无效 等待 成功
  val token: String = "",
)

@Serializable
internal data class UserProfileResp(
  val id: String,
  val username: String,
)

@Serializable
internal data class UserProfileUpdateReq(
  val username: String,
)

private const val LoginCodeLength = 6
private const val WebTokenBytes = 24 // 192 bit, base64url 后 32 char
private const val IssueRetry = 5
private const val LoginCodeTtlSeconds = 600L
private const val WebTokenTtlSeconds = 3600L

@Suppress("unused")
@AronaBackendEndpoint("/user")
internal object UserEndpoint {
  private val secureRandom = SecureRandom()
  private val webTokenEncoder = Base64.getUrlEncoder().withoutPadding()
  // 拆分两个桶, 避免前端每 3 秒一次的轮询打满取码额度:
  //  - issueLimiter: 仅在不带 token 参数时消耗, 严格防止枚举 6 位短码 (5 次 / IP / 分钟).
  //  - pollLimiter:  仅在带 token 参数查询登录结果时消耗, 与前端 3s 轮询匹配 (60 次 / IP / 分钟).
  private val issueLimiter = IpRateLimiter(capacity = 5, refillTokens = 5, refillSeconds = 60)
  private val pollLimiter = IpRateLimiter(capacity = 60, refillTokens = 60, refillSeconds = 60)

  private fun generateNumericCode(): String = buildString(LoginCodeLength) {
    repeat(LoginCodeLength) { append(secureRandom.nextInt(10)) }
  }

  private fun generateWebToken(): String {
    val bytes = ByteArray(WebTokenBytes)
    secureRandom.nextBytes(bytes)
    return webTokenEncoder.encodeToString(bytes)
  }

  /**
   * 用 SET NX EX 原子分配一个不冲突的 redis key, 返回未编入 prefix 的纯 token.
   * 若连续 [IssueRetry] 次都撞 key, 抛 [IllegalStateException] 由调用方降级 500.
   */
  private suspend fun issueRandomKey(
    prefix: RedisPrefixKey,
    value: String,
    ttlSeconds: Long,
    generator: () -> String,
  ): String {
    repeat(IssueRetry) {
      val token = generator()
      val key = RedisPrefixKey.buildKey(prefix, token)
      val ok = redisDbQuery {
        // SetOption.Builder(nx=true, exSeconds=...) 一次往返完成 NX + TTL, 避免 setnx + expire 之间的窗口.
        set(key, value, SetOption.Builder(nx = true, exSeconds = ttlSeconds.toULong()).build())
      } == "OK"
      if (ok) return token
    }
    throw IllegalStateException("failed to allocate unique redis token for $prefix after $IssueRetry retries")
  }

  /**
   * 获取登录凭证 / 查询登录结果.
   * 不带 query token: 颁发 6 位登录验证码, 写入 redis (TTL 10 分钟), 用于在 QQ 端二次确认.
   * 带 query token:  查验证码状态; 若 QQ 端确认完成, 颁发长 web token (TTL 1 小时) 并失效验证码.
   */
  @AronaBackendEndpointGet("/login")
  suspend fun PipelineContext<Unit, ApplicationCall>.login() {
    val codeQuery = request.queryParameters["token"]
    val limiter = if (codeQuery == null) issueLimiter else pollLimiter
    if (!limiter.tryConsume(ip)) {
      return errorMessage("请求过于频繁, 请稍后再试")
    }
    if (codeQuery == null) {
      val code = runCatching {
        issueRandomKey(RedisPrefixKey.WEB_LOGIN, "1", LoginCodeTtlSeconds, ::generateNumericCode)
      }.getOrElse {
        commandLineLogger.warn("login code allocation failed: ${it.message}")
        return internalServerError()
      }
      return success(code)
    }
    val codeKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, codeQuery)
    return when (val userId = redisDbQuery { get(codeKey) }) {
      null -> success(AuthResp(0))
      "1" -> success(AuthResp(1))
      else -> {
        val webToken = runCatching {
          issueRandomKey(RedisPrefixKey.WEB_TOKEN, userId, WebTokenTtlSeconds, ::generateWebToken)
        }.getOrElse {
          commandLineLogger.warn("web token allocation failed: ${it.message}")
          return internalServerError()
        }
        redisDbQuery { del(codeKey) }
        success(AuthResp(2, webToken))
      }
    }
  }

  /**
   * 获取个人信息
   */
  @AronaBackendEndpointGet("")
  suspend fun PipelineContext<Unit, ApplicationCall>.profile() {
    return success(
      UserProfileResp(
        aronaUser.id,
        aronaUser.username
      )
    )
  }

  /**
   * 更新个人信息
   */
  @AronaBackendEndpointPut("")
  suspend fun PipelineContext<Unit, ApplicationCall>.updateProfile() {
    val data = context.receiveJsonOrNull<UserProfileUpdateReq>() ?: return badRequest()
    if (data.username.length > 15) {
      return errorMessage("用户名不能超过15个字符")
    }
    // 用户名对外可见, 风险较高: 采用 fail-closed —— 审核超时/异常时也拒绝改名.
    val audit = auditOrAllow(data.username, level = 80)
    if (audit == null) {
      return errorMessage("内容审核暂不可用, 请稍后重试")
    }
    if (audit.isBlock) {
      return errorMessage("内容审核失败: ${audit.message}")
    }
    // 幂等: 同值更新时 modifiedCount=0 仍视为成功, 仅在 filter 未命中 (用户不存在) 时才报错.
    return if (
      UserDocument.withCollection<MongoUserDocument, UpdateResult> {
        updateOne(
          filter = idFilter(aronaUser.id),
          update = Updates.set(UserDocument::username.name, data.username)
        )
      }.classify() != MongoWriteOutcome.NotMatched
    ) {
      success()
    } else {
      internalServerError()
    }
  }

  /**
   * 获取绑定凭证 / 查询绑定结果. 与 [login] 共用一套 NX 颁发与限流策略.
   */
  @AronaBackendEndpointGet("/bind")
  suspend fun PipelineContext<Unit, ApplicationCall>.bind() {
    val codeQuery = request.queryParameters["token"]
    val limiter = if (codeQuery == null) issueLimiter else pollLimiter
    if (!limiter.tryConsume(ip)) {
      return errorMessage("请求过于频繁, 请稍后再试")
    }
    if (codeQuery == null) {
      val userId = aronaUser.id
      val code = runCatching {
        issueRandomKey(RedisPrefixKey.WEB_BINDING, userId, LoginCodeTtlSeconds, ::generateNumericCode)
      }.getOrElse {
        commandLineLogger.warn("bind code allocation failed: ${it.message}")
        return internalServerError()
      }
      return success(code)
    }
    val codeKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_BINDING, codeQuery)
    return when (redisDbQuery { get(codeKey) }) {
      null -> success(AuthResp(0))
      "success" -> {
        redisDbQuery { del(codeKey) }
        success(AuthResp(2))
      }
      else -> success(AuthResp(1))
    }
  }
}
