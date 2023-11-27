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
      "The parent of any built-in commands"
    )
  }

  override fun permissionId(name: String): PermissionId = PermissionId("console", name)

}
