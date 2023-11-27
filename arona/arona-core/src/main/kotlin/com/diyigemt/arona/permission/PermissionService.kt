package com.diyigemt.arona.permission

import java.util.concurrent.ConcurrentHashMap

class PermissionRegistryConflictException(
  val newInstance: Permission,
  val existingInstance: Permission,
) : Exception("Conflicting Permission registry. new: $newInstance, existing: $existingInstance")

internal object PermissionService {
  val permissions: ConcurrentHashMap<PermissionId, PermissionImpl> = ConcurrentHashMap()
  val rootPermission get() = Permission.RootPermission
  fun register(id: PermissionId, description: String, parent: Permission = rootPermission): PermissionImpl {
    val instance = createPermission(id, description, parent)
    val old = permissions.putIfAbsent(id, instance)
    if (old != null) throw PermissionRegistryConflictException(instance, old)
    return instance
  }
  fun createPermission(id: PermissionId, description: String, parent: Permission): PermissionImpl =
    PermissionImpl(id, description, parent)
  operator fun get(id: PermissionId): PermissionImpl? = permissions[id]
}
