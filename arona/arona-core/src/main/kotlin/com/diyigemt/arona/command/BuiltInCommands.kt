@file:Suppress("unused")

package com.diyigemt.arona.command

import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.isPrivateChannel
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.AutoSavePluginDataHolder
import com.diyigemt.arona.config.internal.MultiFilePluginDataStorageImpl
import com.diyigemt.arona.config.value
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.database.RedisPrefixKey
import com.diyigemt.arona.database.permission.ContactDocument.Companion.createContactAndUser
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.plugins.PluginManager.pluginsConfigPath
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

object BuiltInCommands : AutoSavePluginDataHolder {

  internal fun registerAll() {
    // 注册指令权限
    PermissionService.register(
      PermissionService.allocatePermissionIdForBuildInCommandOwner(
        BuildInCommandOwner,
        "command.*"
      ),
      "内置指令父级权限"
    )
    PermissionService.register(
      PermissionService.allocatePermissionIdForBuildInCommandOwner(
        BuildInOwnerCommandOwner,
        "command.*"
      ),
      "内置指令父级权限"
    )
    PermissionService.register(
      PermissionService.allocatePermissionIdForBuildInCommandOwner(
        BuildInSuperAdminCommandOwner,
        "command.*"
      ),
      "内置指令父级权限"
    )
    @Suppress("UNCHECKED_CAST")
    BuiltInCommands::class.nestedClasses.forEach {
      (it as? KClass<out AbstractCommand>)?.also { a ->
        CommandManager.registerCommandSignature(a, false)
      }
    }
    PluginWebuiConfigRecorder.register(BuildInCommandOwner, BaseConfig.serializer())
    val storage = MultiFilePluginDataStorageImpl(pluginsConfigPath)
    storage.load(BuiltInCommands, ConsoleConfig)
  }

  class LoginCommand : AbstractCommand(
    BuildInCommandOwner,
    "登录",
    description = "登录webui",
    help = """
      /登录 <code> 登录webui
      
      code 可以在 https://www.kivotos.com.cn/login 处获得
      
      具体可看: https://doc.arona.diyigemt.com/v2/manual/webui
    """.trimIndent()
  ) {
    private val token by argument("登录凭证")
    suspend fun UserCommandSender.login() {
      val tokenKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_LOGIN, token)
      if (redisDbQuery { get(tokenKey) } == "1") {
        val documentUser = createContactAndUser(subject, user, DEFAULT_MEMBER_CONTACT_ROLE_ID)
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

  class ContactManagementCommand : AbstractCommand(
    BuildInCommandOwner,
    "管理",
    description = "管理系列指令",
    help = """
      /管理 配置名称 <名称> 配置该群/频道在webui中的名称
      
      /管理 管理员认证 将自己加入该群/频道下的管理员用户组
    """.trimIndent()
  ) {
    @SubCommand
    class AuthAdminPriorityCommand : AbstractCommand(
      BuildInCommandOwner,
      "管理员认证",
      description = "给管理员用, 将自己加入该群/频道下的管理员用户组",
      help = """
        /管理 管理员认证
        
        非管理员无法点击认证按钮
      """.trimIndent()
    ) {
      private val md = tencentCustomMarkdown {
        +"请点击认证按钮完成认证"
        +"非管理员不用试了, 点着没反应的"
      }
      private val kb by lazy {
        tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
          row {
            button(1) {
              render {
                label = "认证"
                visitedLabel = "认证成功"
              }
              action {
                type = TencentKeyboardButtonActionType.CALLBACK
                clickLimit = 1
                permission = TencentKeyboardButtonActionPermissionData(
                  type = TencentKeyboardButtonActionDataType.MANAGER
                )
              }
            }
          }
        }
      }

      suspend fun UserCommandSender.authAdmin() {
        if (isPrivateChannel()) {
          sendMessage("不支持频道私聊使用")
          return
        }
        MessageChainBuilder(kb, md).build().also { sendMessage(it) }
        val auth = withTimeoutOrNull(10000L) {
          nextButtonInteraction()
        }
        if (auth != null) {
          auth.accept()
          createContactAndUser(subject, user, DEFAULT_ADMIN_CONTACT_ROLE_ID)
          sendMessage("认证成功")
        }
      }
    }

    @SubCommand
    class BindContactNameCommand : AbstractCommand(
      BuildInCommandOwner,
      "配置名称",
      description = "配置该群/频道在webui中的名称",
      help = """
        /管理 配置名称 <名称>
        
        配置该群/频道在webui中的名称
      """.trimIndent()
    ) {
      private val name by argument("要设置的群名/频道名称")
      suspend fun UserCommandSender.bindContactName() {
        val contact = findContactDocumentByIdOrNull(subject.fatherSubjectIdOrSelf)
        if (contact == null) {
          sendMessage("当前环境信息查找失败, 去翻文档看看怎么解决吧")
          return
        }
        contact.updateContactDocumentName(name)
        sendMessage("绑定成功")
      }
    }

    suspend fun UserCommandSender.contactManagement() {
      if (currentContext.invokedSubcommand != null) {
        return
      }
      val md = tencentCustomMarkdown {
        +"非管理员不用尝试了, 点着没反应的"
        (getFormattedHelp() ?: "").split("\n").filter { it.isNotEmpty() }.forEach {
          +it
        }
      }
      val kb = tencentCustomKeyboard {
        var idx = 0
        registeredSubcommands().map { it.commandName }.windowed(2, 2, true).forEach { r ->
          row {
            r.forEach { c ->
              button(idx++) {
                render {
                  label = c
                }
                action {
                  data = "/管理 $c"
                  permission = TencentKeyboardButtonActionPermissionData(
                    type = TencentKeyboardButtonActionDataType.MANAGER
                  )
                }
              }
            }
          }
        }
      }
      MessageChainBuilder().append(md).append(kb).build().also {
        sendMessage(it)
      }
    }
  }

  // TODO 总之没做完
//  object BindCommand : AbstractCommand(
//    BuildInCommandOwner,
//    "绑定账号",
//    help = "绑定已经注册的用户信息"
//  ) {
//    private val token by argument("在个人信息界面生成的唯一token")
//    suspend fun UserCommandSender.bind() {
//      val bindKey = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_BINDING, token)
//      when (val userId = redisDbQuery {
//        get(bindKey)
//      }) {
//        is String -> {
//          // 拿到绑定的用户本体
//          when (val user = findUserDocumentByIdOrNull(userId)) {
//            is UserDocument -> {
//              // TODO 询问是否合并信息?
//              redisDbQuery {
//                set(bindKey, "success")
//                expire(bindKey, 600u)
//              }
//              sendMessage("绑定成功")
//            }
//
//            else -> {
//              sendMessage("用户未找到, 请再试一次")
//            }
//          }
//        }
//
//        else -> {
//          sendMessage("token无效")
//        }
//      }
//    }
//  }

  internal fun registerListeners() {
    GlobalEventChannel.subscribeAlways<TencentBotUserChangeEvent> {
      when (it) {
        is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> {
          createContactAndUser(it.subject, it.user, DEFAULT_ADMIN_CONTACT_ROLE_ID)
        }

        else -> {
          //TODO 删除聊天事件
        }
      }
    }

//    GlobalEventChannel.subscribeAlways<TencentMessageEvent> {
//      createContactAndUser(it.subject, it.sender, DEFAULT_MEMBER_CONTACT_ROLE_ID)
//    }
  }

  override val autoSaveIntervalMillis: LongRange = (30 * 1000L)..(10 * 1000L)

  override val dataHolderName = "Console"
  override val coroutineContext: CoroutineContext = EmptyCoroutineContext + CoroutineName("Console Config Saver")
}

@Serializable
data class DbConfig(
  val host: String = "",
  val db: String = "",
  val user: String = "arona",
  val password: String = "",
)

object ConsoleConfig : AutoSavePluginData("Console") {
  val db by value(DbConfig())
}
