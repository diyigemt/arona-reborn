package com.diyigemt.arona.database.service

import com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryReadUncommited
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.permission.UserSchema
import com.diyigemt.arona.database.permission.nextBaseId
import com.diyigemt.arona.database.withCollection

/**
 * 跨 SQL + Mongo 的用户创建编排. 从 [UserDocument.Companion] 搬出, 让 schema 只负责数据/单点写.
 *
 * 事务边界:
 * - SQL 段显式用 [sqlDbQueryReadUncommited] (内部走 [newSuspendedTransaction] + READ_UNCOMMITTED) 运行,
 *   看起来意图是避免并入外层 endpoint 事务; 具体为何选 READ_UNCOMMITTED 而非 READ_COMMITTED 需要 owner 确认.
 * - Mongo 段不在 SQL 事务内, 跨库一致性由 [createUserTracking] 返回的 undo 闭包负责补偿,
 *   上层 (如 [ContactService.createContactAndUser]) 在失败时调用.
 *
 * TODO owner 确认: 如果 READ_UNCOMMITTED 只是为了独立事务而非刻意允许脏读, 可换成 [sqlDbQueryWithIsolation] + READ_COMMITTED.
 */
internal object UserService {

  /** 精确跟踪本次调用的副作用, 便于上层 saga 回滚不误删既有数据. */
  internal data class CreateUserOutcome(
    val document: UserDocument,
    val undo: suspend () -> Unit,
  )

  /** 公开入口: 不关心 undo 时使用. */
  suspend fun createUser(uid: String, contactId: String): UserDocument =
    createUserTracking(uid, contactId).document

  /**
   * 顺序: 先插 Mongo UserDocument, 再写 SQL UserSchema.
   * SQL 段失败时直接删掉刚插的 Mongo, 再抛原错.
   * 成功返回的 [CreateUserOutcome.undo] 会按本次真实写入的形态精确回滚:
   * - SQL 新插行 → 删除
   * - SQL 已有行仅改 uid 指向 → 还原 previousUid
   * - SQL 未触达 → 不动 SQL
   */
  internal suspend fun createUserTracking(uid: String, contactId: String): CreateUserOutcome {
    val ud = UserDocument(
      nextBaseId(),
      uid = mutableListOf(uid),
      contacts = mutableListOf(contactId),
    )
    UserDocument.withCollection { insertOne(ud) }

    var sqlMutation: SqlMutation = SqlMutation.None
    try {
      sqlDbQueryReadUncommited {
        when (val saveUser = UserSchema.findById(uid)) {
          is UserSchema -> {
            val previousUid = saveUser.uid
            saveUser.uid = ud.id
            sqlMutation = SqlMutation.UpdatedExistingRow(uid, previousUid)
          }
          else -> {
            UserSchema.new(uid) {
              this@new.from = contactId
              this@new.uid = ud.id
            }.also { newUser ->
              if (newUser.id.value !in ud.uid) {
                ud.updateUserContact(contactId)
              }
            }
            sqlMutation = SqlMutation.InsertedNewRow(uid)
          }
        }
      }
    } catch (t: Throwable) {
      runCatching { UserDocument.withCollection<UserDocument, Unit> { deleteOne(idFilter(ud.id)) } }
        .onFailure { t.addSuppressed(it) }
      throw t
    }

    val undo: suspend () -> Unit = {
      UserDocument.withCollection<UserDocument, Unit> { deleteOne(idFilter(ud.id)) }
      sqlDbQueryReadUncommited {
        when (val m = sqlMutation) {
          is SqlMutation.InsertedNewRow -> UserSchema.findById(m.uid)?.delete()
          is SqlMutation.UpdatedExistingRow -> UserSchema.findById(m.uid)?.let { it.uid = m.previousUid }
          SqlMutation.None -> Unit
        }
      }
    }
    return CreateUserOutcome(ud, undo)
  }

  private sealed interface SqlMutation {
    data object None : SqlMutation
    data class InsertedNewRow(val uid: String) : SqlMutation
    data class UpdatedExistingRow(val uid: String, val previousUid: String) : SqlMutation
  }
}
