package com.diyigemt.arona.permission

internal interface PermissionNameSpace {
  val permission: Permission
}

internal data class PermissionId(
  val nameSpace: String,
  val name: String
)

internal interface Permission {
  val id: PermissionId
  val parent: Permission
  val description: String

  companion object {
    val RootPermission = PermissionImpl(PermissionId("*", "*"), "The root permission").also { it.parent = it }
  }
}

internal data class PermissionImpl(
  override val id: PermissionId,
  override val description: String
) : Permission {
  override lateinit var parent: Permission

  constructor(id: PermissionId, description: String, parent: Permission) : this(id, description) {
    this.parent = parent
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as PermissionImpl

    if (id != other.id) return false
    if (description != other.description) return false
    if (parent !== other.parent) return false

    return true
  }

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + description.hashCode()
    result = 31 * result + if (parent == this) 1 else parent.hashCode()
    return result
  }
}