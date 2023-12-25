package com.diyigemt.arona.webui.endpoints.user

import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.database.RedisPrefixKey
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.internalServerError
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.*
import com.diyigemt.arona.webui.endpoints._aronaUser
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable

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

@Suppress("unused")
@AronaBackendEndpoint("/user")
internal object UserEndpoint {
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
      // 请求参数无token, 证明为获取登录凭证
      else -> {
        val password = generateNumber()
        var passwordKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, password)
        redisDbQuery {
          while (get(passwordKey) != null) {
            passwordKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, generateNumber())
          }
        }
        redisDbQuery {
          set(passwordKey, "1")
          expire(passwordKey, 600u)
        }
        success(password)
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
    return if (
      UserDocument.withCollection<UserDocument, UpdateResult> {
        updateOne(
          filter = idFilter(aronaUser.id),
          update = Updates.set(UserDocument::username.name, data.username)
        )
      }.modifiedCount == 1L
    ) {
      success()
    } else {
      internalServerError()
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
