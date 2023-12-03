package com.diyigemt.arona.command

import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.PermissionId
import com.diyigemt.arona.permission.PermissionNameSpace
import com.diyigemt.arona.permission.PermissionService

interface CommandOwner : PermissionNameSpace {
  val permission: Permission
}

object ConsoleCommandOwner : CommandOwner {
  override val permission: Permission by lazy {
    PermissionService.register(
      permissionId("*"),
      "控制台指令, 只能通过控制台执行"
    )
  }

  override fun permissionId(name: String): PermissionId = PermissionId("console", name)
}

object BuildInCommandOwner : CommandOwner {
  override val permission: Permission by lazy {
    PermissionService.register(
      permissionId("*"),
      "内置指令, 非插件指令, 允许所有用户执行"
    )
  }

  override fun permissionId(name: String): PermissionId = PermissionId("buildIn.normal", name)
}

object BuildInOwnerCommandOwner : CommandOwner {
  override val permission: Permission by lazy {
    PermissionService.register(
      permissionId("*"),
      "内置指令, 非插件指令, 仅允许聊天环境的管理员执行"
    )
  }

  override fun permissionId(name: String): PermissionId = PermissionId("buildIn.owner", name)
}

object BuildInSuperAdminCommandOwner : CommandOwner {
  override val permission: Permission by lazy {
    PermissionService.register(
      permissionId("*"),
      "内置指令, 非插件指令, 仅允许机器人维护者执行"
    )
  }

  override fun permissionId(name: String): PermissionId = PermissionId("buildIn.super", name)
}