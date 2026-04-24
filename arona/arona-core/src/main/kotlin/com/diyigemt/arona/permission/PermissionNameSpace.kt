package com.diyigemt.arona.permission

import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactMember.Companion.toPermissionSubject
import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.Policy.Companion.BuildInDenyPolicySchema
import com.diyigemt.arona.database.permission.PolicyNodeEffect
import com.diyigemt.arona.permission.abac.AbacRequest
import com.diyigemt.arona.permission.abac.Decision
import com.diyigemt.arona.permission.abac.cache.PolicyCompileCache
import com.diyigemt.arona.permission.abac.eval.PolicyEvaluator
import com.diyigemt.arona.permission.abac.extract.toAttrs
import com.diyigemt.arona.utils.commandLineLogger

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
    )

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
      val permissionId = fullPermissionId()
      val allow = policies
        .filter { it.effect == PolicyNodeEffect.ALLOW }
        .flatMap { PolicyCompileCache.getOrCompile(it) }
      val deny = policies
        .filter { it.effect == PolicyNodeEffect.DENY }
        .flatMap { PolicyCompileCache.getOrCompile(it) }
        .toMutableList()
        .apply { addAll(PolicyCompileCache.getOrCompile(BuildInDenyPolicySchema)) }

      val req = AbacRequest(
        subject = subject.toPermissionSubject().toAttrs(),
        action = mapOf("type" to "effect"),
        resource = Resource(permissionId).toAttrs(),
        environment = environment,
      )
      return when (val decision = PolicyEvaluator.evaluate(allow, deny, req)) {
        is Decision.Permit -> true
        is Decision.Deny -> {
          commandLineLogger.info(
            "abac ${decision.kind} subjectId=${subject.id} " +
                "roles=${subject.roles.joinToString(",")} permission=$permissionId " +
                "reason=${decision.reason}"
          )
          false
        }
      }
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
