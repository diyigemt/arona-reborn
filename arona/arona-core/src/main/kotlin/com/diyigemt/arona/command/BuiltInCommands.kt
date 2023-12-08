package com.diyigemt.arona.command

import com.diyigemt.arona.command.CommandManager.register
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.database.RedisPrefixKey
import com.diyigemt.arona.database.permission.ContactDocument.Companion.addMember
import com.diyigemt.arona.database.permission.ContactDocument.Companion.createContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactDocument.Companion.updateMemberRole
import com.diyigemt.arona.database.permission.ContactType
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.permission.UserDocument.Companion.createUserDocument
import com.diyigemt.arona.database.permission.UserDocument.Companion.findUserDocumentByUidOrNull
import com.diyigemt.arona.database.permission.UserDocument.Companion.updateUserContact
import com.diyigemt.arona.database.permission.UserSchema
import com.github.ajalt.clikt.parameters.arguments.argument

object BuiltInCommands {

  internal fun registerAll() {
    BuiltInCommands::class.nestedClasses.forEach {
      (it.objectInstance as? Command)?.register()
    }
  }

  object LoginCommand : AbstractCommand(
    BuildInCommandOwner,
    "登录",
    help = "登录webui"
  ) {
    private val token by argument("登录凭证")
    suspend fun UserCommandSender.login() {
      val tokenKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, token)
      if (redisDbQuery { get(tokenKey) } == "1") {
        val documentUser = findUserDocumentByUidOrNull(user.id) ?: createUserDocument(user.id, subject.id)
        when (val saveUser = UserSchema.findById(user.id)) {
          is UserSchema -> {
            saveUser.uid = documentUser.id
          }

          else -> {
            UserSchema.new(user.id) {
              from = subject.id
              uid = documentUser.id
            }.also {
              if (it.id.value !in documentUser.uid) {
                documentUser.updateUserContact(subject.id)
              }
            }
          }
        }

        redisDbQuery {
          set(tokenKey, documentUser.id)
          expire(tokenKey, 100u)
        }
        sendMessage("认证成功")
      } else {
        sendMessage("token无效")
      }
    }
  }

  object BindCommand : AbstractCommand(
    BuildInCommandOwner,
    "绑定",
    help = "绑定已经注册的用户信息"
  ) {
    private val token by argument("在个人信息界面生成的唯一token")
    suspend fun UserCommandSender.bind() {
      val bindKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_BINDING, token)
      when (val userId = redisDbQuery {
        get(bindKey)
      }) {
        is String -> {
          // 拿到目标id
          when (val user = findUserDocumentByUidOrNull(userId)) {
            is UserDocument -> {
              redisDbQuery {
                set(bindKey, "success")
                expire(bindKey, 600u)
              }
              sendMessage("绑定成功")
            }
            else -> {
              sendMessage("用户未找到, 请再试一次")
            }
          }
        }
        else -> {
          sendMessage("token无效")
        }
      }
    }
  }

  internal fun registerListeners() {
    GlobalEventChannel.subscribeAlways<TencentBotUserChangeEvent> {
      when (it) {
        is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> {
          // 检查有无记录, 无则创建并初始化
          val contact = findContactDocumentByIdOrNull(it.subject.id) ?: createContactDocument(
            it.subject.id,
            when (it) {
              is TencentFriendAddEvent -> ContactType.Private
              is TencentGroupAddEvent -> ContactType.Group
              is TencentGuildAddEvent -> ContactType.Guild
              else -> ContactType.PrivateGuild
            }
          )
          // 防止用户删了又加回来的情况
          val user = findUserDocumentByUidOrNull(user.id) ?: createUserDocument(user.id, subject.id)
          val member = contact.addMember(user.id)
          contact.updateMemberRole(member.id, "role.admin")
        }

        else -> {
          //TODO 删除聊天事件
        }
      }
    }
  }

}
