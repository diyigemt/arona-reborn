package com.diyigemt.arona.database.permission

import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.MongoWriteOutcome
import com.diyigemt.arona.database.classify
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.dot
import com.diyigemt.arona.database.matchedOne
import com.diyigemt.arona.database.memberPositional
import com.diyigemt.arona.database.membersIdPath
import com.diyigemt.arona.database.pluginConfigPath
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.createBaseAdminRole
import com.diyigemt.arona.database.permission.ContactRole.Companion.createBaseMemberRole
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.arona.webui.endpoints.aronaUser
import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.diyigemt.arona.webui.pluginconfig.preparePluginConfigWrite
import com.diyigemt.arona.webui.pluginconfig.resolveConfigKey
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import com.mongodb.kotlin.client.coroutine.AggregateFlow
import com.mongodb.kotlin.client.coroutine.FindFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

@Serializable
enum class ContactType {
  Private,
  PrivateGuild,
  Group,
  Guild,
}

/**
 * 主动消息开关的持久化三态. [UNKNOWN] 显式表达"从未收到开关事件"的存量态, 不把未知伪装成允许/拒绝.
 * 枚举名字面值即 Mongo 持久化契约 (经 kotlinx codec 存为字符串), 不可重命名;
 * 由 ProactiveMessageStateCompatTest 钉住.
 */
@Serializable
enum class ProactiveMessageState {
  UNKNOWN,
  ALLOW,
  REJECT,
}

/**
 * 主动消息状态的乱序防回写 filter: 仅当文档"从未更新过 (字段缺失, 即存量文档)"或"已存时间戳
 * 不晚于本事件"时才允许更新. `$lte` 不匹配缺字段文档, 必须显式 `$exists:false` 兜底, 否则存量
 * 文档的第一次开关事件会 matched==0 静默丢失. 同时间戳后到覆盖先到 —— 平台事件无序号可依,
 * 只能 arrival-wins. Contact/User 两集合字段名一致, 共用本 filter.
 */
internal fun proactiveMessageFreshnessFilter(id: String, timestamp: Long): Bson {
  val updatedAt = ContactDocument::proactiveMessageStateUpdatedAt.name
  return Filters.and(
    Filters.eq("_id", id),
    Filters.or(
      Filters.exists(updatedAt, false),
      Filters.lte(updatedAt, timestamp),
    ),
  )
}

abstract class PluginContactDocument : PluginVisibleData() {
  abstract val id: String
  abstract val contactName: String
  abstract val contactType: ContactType
  abstract var roles: List<ContactRole>
  abstract var members: List<ContactMember>
  fun findContactMemberOrNull(memberId: String) = members.firstOrNull { it.id == memberId }
  fun findContactMember(memberId: String) = members.first { it.id == memberId }

  /**
   * 写入群级插件配置. 仅持久化主 key, 不回写 alias 数据.
   * 该 raw 写入不做 check/audit/canonical, 仅供 endpoint 在自己 prepare 之后落库使用;
   * 业务代码请走带类型参数的 inline 重载, 它会经过 [preparePluginConfigWrite] 的完整守卫.
   */
  abstract suspend fun updatePluginConfig(pluginId: String, key: String, value: JsonObject)

  /**
   * 命令侧 typed 写入入口: 经过 [preparePluginConfigWrite] 后再落库.
   * - [audit] 默认 true, 与 endpoint 同款; 写入纯机器派生状态 (计数/开关) 的热路径可显式 false 跳过 3s 审核超时
   * - 失败抛 [com.diyigemt.arona.webui.pluginconfig.PluginConfigWriteRejectedException]
   */
  @OptIn(InternalSerializationApi::class)
  suspend inline fun <reified T : PluginWebuiConfig> updatePluginConfig(
    plugin: CommandOwner,
    value: T,
    key: String = resolveConfigKey(T::class.serializer()),
    audit: Boolean = true,
  ) {
    val ns = plugin.permission.id.nameSpace.toMongodbKey()
    val prepared = preparePluginConfigWrite(ns, key, value, T::class.serializer(), audit = audit)
    updatePluginConfig(ns, prepared.canonicalKey, prepared.element)
  }
}

abstract class PluginContactMember : PluginVisibleData() {
  abstract val id: String // 指向UserDocument.id
  abstract val name: String
  abstract val roles: List<String>

  /**
   * 写入"用户 × 群"维度的插件配置. cid 必填 —— 历史上存在过 3-arg 重载, 没有 cid 时只 warn 不写库,
   * 误用会被静默吞掉; 现在用编译期签名强制要求传入所在群 id.
   *
   * 该 raw 写入不做 check/audit/canonical, 仅供 endpoint 在自己 prepare 之后落库使用;
   * 业务代码请走带类型参数的 inline 重载, 它会经过 [preparePluginConfigWrite] 的完整守卫.
   */
  abstract suspend fun updatePluginConfig(pluginId: String, key: String, value: JsonObject, cid: String)

  /**
   * 命令侧 typed 写入入口: 经过 [preparePluginConfigWrite] 后再落库.
   * - [cid] 不提供默认值, 必须显式传入所在群 id, 避免回归到"3-arg 静默吞 cid"的历史行为
   * - [audit] 默认 true, 与 endpoint 同款; 写入纯机器派生状态 (计数/开关) 的热路径可显式 false 跳过 3s 审核超时
   * - 失败抛 [com.diyigemt.arona.webui.pluginconfig.PluginConfigWriteRejectedException]
   */
  @OptIn(InternalSerializationApi::class)
  suspend inline fun <reified T : PluginWebuiConfig> updatePluginConfig(
    plugin: CommandOwner,
    value: T,
    cid: String,
    key: String = resolveConfigKey(T::class.serializer()),
    audit: Boolean = true,
  ) {
    val ns = plugin.permission.id.nameSpace.toMongodbKey()
    val prepared = preparePluginConfigWrite(ns, key, value, T::class.serializer(), audit = audit)
    updatePluginConfig(ns, prepared.canonicalKey, prepared.element, cid)
  }
}


@Serializable
data class ContactRole(
  val id: String,
  val name: String,
) {
  companion object {
    internal const val DEFAULT_MEMBER_CONTACT_ROLE_ID = "role.default"
    internal const val DEFAULT_ADMIN_CONTACT_ROLE_ID = "role.admin"
    internal const val DEFAULT_SUPER_ROLE_ID = "role.super" // 只有机器人部署者才有的权限
    internal val PROTECTED_ROLE_ID = listOf(DEFAULT_SUPER_ROLE_ID)
    internal fun checkHasProtectedRoleId(p: Policy): Boolean {
      return p.rules.map { checkHasProtectedRoleIdNode(it) }.any { it }
    }

    private fun checkHasProtectedRoleIdNode(node: PolicyNode): Boolean {
      return (node.rule?.map { checkHasProtectedRoleIdRule(it) }?.any { it } ?: false) ||
          (node.children?.map { checkHasProtectedRoleIdNode(it) }?.any { it } ?: false)
    }

    private fun checkHasProtectedRoleIdRule(rule: PolicyRule): Boolean {
      val a = rule.type == PolicyRuleType.Subject && rule.key == "roles"
      val b = rule.value.split(",").any { it in PROTECTED_ROLE_ID }
      return a && b
    }

    fun createBaseAdminRole() = ContactRole(DEFAULT_ADMIN_CONTACT_ROLE_ID, "管理员")
    fun createBaseMemberRole() = ContactRole(DEFAULT_MEMBER_CONTACT_ROLE_ID, "普通成员")
    fun createRole(name: String) = ContactRole(
      "role.${uuid()}",
      name
    )
  }
}

@Serializable
data class ContactMember(
  override val id: String, // 指向UserDocument.id
  override val name: String,
  override val roles: List<String>, // 指向ContactDocument.roles.id
  override val config: Map<String, Map<String, JsonObject>> = mapOf(),
) : PluginContactMember() {
  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: JsonObject,
    cid: String,
  ) {
    ContactDocument.withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(
          idFilter(cid),
          Filters.eq(membersIdPath(), id)
        ),
        update = Updates.set(memberPositional(ContactMember::config, pluginId.toMongodbKey(), key), value)
      )
    }
  }
  companion object {
    data class ContactMemberPermissionSubject(
      val id: String,
      val roles: List<String>,
    )

    internal fun ContactMember.toPermissionSubject() = ContactMemberPermissionSubject(
      id,
      roles
    )
  }
}
@Serializable
internal data class SimplifiedContactDocument(
  @SerialName("_id")
  val id: String,
  val contactName: String,
  val contactType: ContactType = ContactType.Group,
)
/**
 * 只读查找已存在的 contact 文档 (按 [com.diyigemt.arona.communication.contact.Contact.fatherSubjectIdOrSelf] 的 id),
 * 不存在返回 null, **不建档**.
 *
 * 与 `UserCommandSender.contactDocument()` 的区别: 后者会走 [com.diyigemt.arona.database.service.ContactService.createContactAndUser]
 * (建群档 / 建用户档 / `$addToSet` 成员 / 补角色), 是"用户主动用指令"场景的建档入口. 订阅全量消息、需要按群读取
 * [com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig] 做门控的插件必须用本函数, 否则每条群消息都会触发
 * 一次建档写放大, 并为从未用过指令的成员创建用户档.
 */
suspend fun findContactPluginDocumentOrNull(contactId: String): PluginContactDocument? =
  ContactDocument.findContactDocumentByIdOrNull(contactId)

@Serializable
internal data class ContactDocument(
  override val id: String,
  override val contactName: String = "",
  override val contactType: ContactType = ContactType.Group,
  var policies: List<Policy> = listOf(),
  override var roles: List<ContactRole> = listOf(),
  override var members: List<ContactMember> = listOf(),
  val registerTime: String = currentDateTime(),
  // 主动消息开关状态. 未按 appId 分区 —— 与现有 /webhook 单 bot 路由缺口同级, 多 bot 化时一并演进.
  val proactiveMessageState: ProactiveMessageState = ProactiveMessageState.UNKNOWN,
  val proactiveMessageStateUpdatedAt: Long = 0L,
  override val config: Map<String, Map<String, JsonObject>> = mapOf(), // 环境自定义的,插件专有的配置项
): PluginContactDocument() {

  /**
   * 检查member是否拥有这个群的role.admin权限
   */
  fun checkAdminPermission(userId: String) =
    members.any { it.roles.contains(DEFAULT_ADMIN_CONTACT_ROLE_ID) && it.id == userId }

  suspend fun updateContactDocumentName(name: String) {
    withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(ContactDocument::contactName.name, name)
      )
    }
  }

  /**
   * 不触 Mongo 的纯函数校验, 便于单元测试. 返回 [ContactDocumentUpdateException.Success] 表示输入合法.
   */
  internal fun validateMemberRoleUpdate(memberId: String, roleId: String): ContactDocumentUpdateException {
    if (findContactMemberOrNull(memberId) == null) {
      return ContactDocumentUpdateException.MemberNotFoundException(memberId)
    }
    if (roles.none { it.id == roleId }) {
      return ContactDocumentUpdateException.RoleNotFoundException(roleId)
    }
    return ContactDocumentUpdateException.Success()
  }

  suspend fun updateMemberRole(memberId: String, roleId: String): ContactDocumentUpdateException {
    when (val v = validateMemberRoleUpdate(memberId, roleId)) {
      is ContactDocumentUpdateException.Success -> Unit
      else -> return v
    }
    val res = withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(idFilter(id), Filters.eq(membersIdPath(), memberId)),
        update = Updates.addToSet(memberPositional(ContactMember::roles), roleId),
      )
    }
    return when {
      !res.matchedOne() -> ContactDocumentUpdateException.MemberNotFoundException(memberId)
      // matched 但 modified=0 表示 role 已存在; 调用方语义上视为成功.
      else -> ContactDocumentUpdateException.Success()
    }
  }

  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: JsonObject,
  ) {
    withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(pluginConfigPath(ContactDocument::config, pluginId, key), value)
      )
    }
  }

  companion object : DocumentCompanionObject {
    override val documentName = "Contact"

    /**
     * 按事件时间条件更新主动消息开关状态, filter 语义见 [proactiveMessageFreshnessFilter].
     * [MongoWriteOutcome.NotMatched] 无法区分"文档不存在"与"已存状态更新", 调用方日志需两说,
     * 且不得 upsert —— 裸 upsert 会造出缺 roles/policies 的半残文档破坏权限体系.
     * webhook 是先应答后异步落库, 写失败拿不到腾讯重投, 靠下一次开关事件自愈.
     */
    suspend fun updateProactiveMessageState(
      id: String,
      state: ProactiveMessageState,
      timestamp: Long,
    ): MongoWriteOutcome = withCollection<MongoContactDocument, UpdateResult> {
      updateOne(
        filter = proactiveMessageFreshnessFilter(id, timestamp),
        update = Updates.combine(
          // 写枚举 name 字符串, 与 kotlinx codec 的 enum wire 形态一致 (CompatTest 有编码侧钉子).
          Updates.set(ContactDocument::proactiveMessageState.name, state.name),
          Updates.set(ContactDocument::proactiveMessageStateUpdatedAt.name, timestamp),
        ),
      )
    }.classify()

    suspend fun findContactDocumentByIdOrNull(id: String): ContactDocument? =
      withCollection<MongoContactDocument, MongoContactDocument?> {
        find(idFilter(id)).limit(1).firstOrNull()
      }?.toDomain()

    suspend fun createContactDocument(id: String, type: ContactType = ContactType.Group): ContactDocument {
      val cd = ContactDocument(
        id,
        roles = listOf(createBaseAdminRole(), createBaseMemberRole()),
        policies = mutableListOf(createBaseContactAdminPolicy()).apply { addAll(createBaseMemberPolicy()) },
        contactType = type,
      )
      withCollection<MongoContactDocument, Unit> { insertOne(cd.toMongo()) }
      return cd
    }

    /**
     * 查询用户可见的群/频道; 先用 match 筛出当前用户所在集合, 再把 members 数组只保留当前用户自身,
     * 并剔除 config 等敏感字段. 内部 aggregate 走 [MongoUserContactDocument] 解码,
     * 边界 mapper 转 [UserContactDocument] DTO 后返回; 调用方拿到的是 domain 表示.
     */
    internal suspend fun findVisibleToUser(userId: String): List<UserContactDocument> =
      withCollection<MongoContactDocument, List<MongoUserContactDocument>> {
        aggregate<MongoUserContactDocument>(visibleToUserPipeline(userId)).toList()
      }.map { it.toDomain() }

    internal suspend fun contacts(): List<SimplifiedContactDocument> {
      val filter = Aggregates.match(Filters.eq(ContactDocument::contactType.name, ContactType.Group.name))
      return withCollection<MongoContactDocument, List<SimplifiedContactDocument>> {
        aggregate<SimplifiedContactDocument>(
          listOf(
            filter,
            Aggregates.project(
              Projections.fields(
                Document("_id", 1),
                Document(ContactDocument::contactName.name, 1),
                Document(ContactDocument::contactType.name, 1)
              )
            )
          )
        ).toList()
      }
    }

    internal suspend fun guilds(): List<SimplifiedContactDocument> {
      val filter = Aggregates.match(Filters.eq(ContactDocument::contactType.name, ContactType.Guild.name))
      return withCollection<MongoContactDocument, List<SimplifiedContactDocument>> {
        aggregate<SimplifiedContactDocument>(
          listOf(
            filter,
            Aggregates.project(
              Projections.fields(
                Document("_id", 1),
                Document(ContactDocument::contactName.name, 1),
                Document(ContactDocument::contactType.name, 1)
              )
            )
          )
        ).toList()
      }
    }
  }
}

/**
 * 聚合管道: 匹配当前用户所在的群/频道, 并用 $filter 把 members 数组裁剪为当前用户自身,
 * 最后只投影列表页所需字段 (剔除 config, 防止群级敏感插件配置外泄).
 */
internal fun visibleToUserPipeline(userId: String): List<org.bson.conversions.Bson> = listOf(
  Aggregates.match(Filters.eq(membersIdPath(), userId)),
  Aggregates.project(
    Projections.fields(
      Document("_id", 1),
      Document(ContactDocument::contactName.name, 1),
      Document(ContactDocument::contactType.name, 1),
      Document(ContactDocument::roles.name, 1),
      Document(
        ContactDocument::members.name,
        Document(
          "\$filter",
          Document(
            mapOf(
              "input" to "\$${ContactDocument::members.name}",
              "as" to "mem",
              "cond" to Document("\$eq", listOf("\$\$mem._id", userId)),
            )
          )
        )
      ),
    )
  ),
  Aggregates.project(
    Projections.fields(
      Document("_id", 1),
      Document(ContactDocument::contactName.name, 1),
      Document(ContactDocument::contactType.name, 1),
      Document(ContactDocument::roles.name, 1),
      Document(membersIdPath(), 1),
      Document(ContactDocument::members dot ContactMember::name, 1),
      Document(ContactDocument::members dot ContactMember::roles, 1),
    )
  ),
)

internal sealed class ContactDocumentUpdateException {
  abstract val cause: String

  class Success : ContactDocumentUpdateException() {
    override val cause: String = ""
  }

  class MemberNotFoundException(memberId: String) : ContactDocumentUpdateException() {
    override val cause: String = "member: $memberId not found"
  }

  class RoleNotFoundException(roleId: String) : ContactDocumentUpdateException() {
    override val cause: String = "role: $roleId not found"
  }

  class PolicyNotFoundException(policyId: String) : ContactDocumentUpdateException() {
    override val cause: String = "policy: $policyId not found"
  }

  class InternalFailureException(override val cause: String) : ContactDocumentUpdateException()
}
