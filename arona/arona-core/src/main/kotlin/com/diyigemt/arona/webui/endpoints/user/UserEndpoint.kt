package com.diyigemt.arona.webui.endpoints.user

import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.database.RedisPrefixKey
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints._aronaUser
import com.diyigemt.arona.webui.endpoints.request
import io.ktor.server.application.*
import io.ktor.util.pipeline.*

data class AuthResp(
  val status: Int, // 0 1 2 无效 等待 成功
  val token: String = "",
)

@Suppress("unused")
@AronaBackendEndpoint("/user")
object UserEndpoint {
  private fun generateNumber(): String = (1..6).map { "0123456789".random() }.joinToString("")

  /**
   * 获取登录凭证/登录结果
   */
  @AronaBackendEndpointGet("/login")
  suspend fun PipelineContext<Unit, ApplicationCall>.login() {
    return when (val token = request.queryParameters["token"]?.let {
      RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, it)
    }) {
      // 请求参数有token, 证明为获取登录结果
      is String -> {
        when (val userId = redisDbQuery { get(token) }) {
          "1" -> {
            success(AuthResp(1))
          }

          is String -> {
            val uuid = generateNumber()
            val uuidKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_TOKEN, uuid)
            redisDbQuery {
              with(pipelined()) {
                del(token)
                set(uuidKey, userId)
                expire(uuidKey, 3600u)
                execute()
              }
            }
            success(AuthResp(2, uuid))
          }

          else -> {
            success(AuthResp(0))
          }
        }
      }
      // 请求参数无token, 证明为获取token认证结果
      else -> {
        val password = generateNumber()
        val passwordKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, password)
        redisDbQuery {
          set(passwordKey, "1")
          expire(passwordKey, 600u)
        }
        success(password)
      }
    }

  }

  /**
   * 获取绑定凭证/绑定结果
   */
  @AronaBackendEndpointGet("/bind")
  suspend fun PipelineContext<Unit, ApplicationCall>.bind() {
    when (val token = request.queryParameters["token"]?.let {
      RedisPrefixKey.buildKey(RedisPrefixKey.WEB_BINDING, it)
    }) {
      is String -> {
        val res = redisDbQuery {
          get(token)
        }
        when (res) {
          null -> success(AuthResp(0))
          "success" -> {
            redisDbQuery {
              del(token)
            }
            success(AuthResp(2))
          }
          else -> success(AuthResp(1))
        }
      }
      else -> {
        val password = generateNumber()
        val key = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_BINDING, password)
        redisDbQuery {
          set(key, _aronaUser?.id ?: "")
          expire(key, 600u)
        }
        success(password)
      }
    }
  }
}
