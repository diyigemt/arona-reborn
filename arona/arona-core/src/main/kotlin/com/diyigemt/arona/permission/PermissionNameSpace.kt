package com.diyigemt.arona.permission

import codes.laurence.warden.AccessRequest
import codes.laurence.warden.atts.HasAtts
import codes.laurence.warden.enforce.EnforcementPointDefault
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactMember.Companion.toPermissionSubject
import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.Policy.Companion.BuildInAllowPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.BuildInDenyPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.build
import com.diyigemt.arona.database.permission.PolicyNodeEffect

interface PermissionNameSpace {
  fun permissionId(name: String): PermissionId
}

data class PermissionId(
  val nameSpace: String,
  val name: String,
) {
  override fun toString(): String = "$nameSpace:$name"
}

interface Permission {
  val id: PermissionId
  val parent: Permission
  val description: String

  companion object {
    data class Resource(
      val id: String,
    ) : HasAtts()

    val RootPermission = PermissionImpl(PermissionId("*", "*"), "The root permission").also { it.parent = it }
    fun Permission.fullPermissionId(): String {
      return if (parent == RootPermission) {
        id.toString()
      } else {
        val fathers = mutableListOf<Permission>()
        var tmpParent: Permission = parent
        while (tmpParent != RootPermission) {
          fathers.add(tmpParent)
          tmpParent = tmpParent.parent
        }
        fathers.reverse()
        fathers.joinToString(":") { it.id.nameSpace } + ":${id.name}"
      }
    }

    @PublishedApi
    internal suspend fun Permission.testPermission(
      subject: ContactMember,
      policies: List<Policy>,
      environment: Map<String, Any?> = mapOf(),
    ): Boolean {
      val allow = policies
        .filter { it.effect == PolicyNodeEffect.ALLOW }
        .map { it.build() }
        .flatten()
        .toMutableList()
        .apply {
          add(BuildInAllowPolicy)
        }
      // 添加常驻禁止策略
      val deny = policies
        .filter { it.effect == PolicyNodeEffect.DENY }
        .map { it.build() }
        .flatten()
        .toMutableList()
        .apply {
//          add(BuildInDenyPolicy)
        }
      return runCatching {
        EnforcementPointDefault(allow, deny).enforceAuthorization(
          AccessRequest(
            subject = subject.toPermissionSubject().atts(),
            action = mapOf("type" to "effect"),
            resource = Resource(fullPermissionId()).atts(),
            environment = environment
          )
        )
        true
      }.getOrDefault(false)
    }
  }
}

data class PermissionImpl(
  override val id: PermissionId,
  override val description: String,
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

  override fun toString(): String =
    "PermissionImpl(id=$id, description='$description', parent=${if (parent === this) "<self>" else parent.toString()})"
}
