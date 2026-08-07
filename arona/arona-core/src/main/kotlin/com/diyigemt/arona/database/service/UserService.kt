package com.diyigemt.arona.database.service

import com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryWithIsolation
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.permission.MongoUserDocument
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.permission.UserSchema
import com.diyigemt.arona.database.permission.UserTable
import com.diyigemt.arona.database.permission.nextBaseId
import com.diyigemt.arona.database.permission.toDomain
import com.diyigemt.arona.database.permission.toMongo
import com.diyigemt.arona.database.withCollection
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import io.ktor.util.logging.KtorSimpleLogger
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.sql.Connection

/**
 * 跨 SQL + Mongo 的用户创建编排. 从 [UserDocument.Companion] 搬出, 让 schema 只负责数据/单点写.
 *
 * 核心不变量: SQL User 行的 uid 指针是用户身份的事实来源, 任何路径都不覆盖既有指针 —
 * Mongo 档缺失时按原指针重建骨架档 (清库后指针悬空的自愈), 注册竞争的输家复用赢家的指针.
 * 历史上覆盖指针曾把一次清库事故放大成全量指针悬空, 也是"并发注册双胞胎档"的成因.
 *
 * 身份一旦发布 (SQL 行提交) 即视为共享且不可回收: 指针相等证明不了没有并发请求正在复用
 * 该身份, 删除补偿会把一次局部业务失败放大成共享身份损坏. 因此本对象不提供 undo —
 * 上层 saga 失败时留下的"已注册但未入群"状态是幂等的, 下次交互经查找路径自然复用.
 * 唯一的清理是发布失败 (SQL 段抛错) 或输掉竞争时删除从未被指针引用过的本次候选档.
 *
 * 事务边界:
 * - SQL 段用 READ_COMMITTED 独立事务: 注册胜负由主键唯一约束原子裁决 (INSERT IGNORE),
 *   读指针不接受未提交数据 (脏读到随后回滚的指针会制造新的悬空路径).
 * - Mongo 段不在 SQL 事务内.
 */
internal object UserService {
  private val logger = KtorSimpleLogger("UserService")

  /** 并发重建同一骨架档时"读取-插入"的最大交替轮数, 超出说明库在异常抖动, 熔断. */
  private const val RESOLVE_ATTEMPTS = 3

  /**
   * User.id 是 CHAR(255); INSERT IGNORE 在 MariaDB 下会把超长/非法值静默矫正而非报错,
   * 因此入口先拒绝, 不让可疑 openid 走到 SQL.
   */
  private const val MAX_OPENID_LENGTH = 255

  /**
   * - SQL 已有该 openid 的行 → 复用其 uid 指针, 见 [resolvePublishedUser]; 绝不发新号改指针.
   * - 无行 → 发新号先插 Mongo 候选档, 再以 INSERT IGNORE 原子竞争 SQL 行;
   *   输家删除自己的候选档, 改为复用赢家指针.
   */
  suspend fun createUser(uid: String, contactId: String): UserDocument {
    require(uid.isNotBlank() && uid.length <= MAX_OPENID_LENGTH) {
      "非法 openid (空白或超长): ${uid.take(64)}"
    }
    userSqlQuery { UserSchema.findById(uid)?.uid }?.let { pointer ->
      return resolvePublishedUser(uid, pointer, contactId)
    }

    val candidate = UserDocument(
      nextBaseId(),
      uid = listOf(uid),
      contacts = listOf(contactId),
    )
    UserDocument.withCollection<MongoUserDocument, Unit> { insertOne(candidate.toMongo()) }

    val winnerPointer = try {
      userSqlQuery {
        // INSERT IGNORE: 主键冲突时整行不动, 从语义上杜绝覆盖既有 uid 指针; 同事务读回真实指向判定胜负.
        UserTable.insertIgnore {
          it[UserTable.id] = uid
          it[UserTable.from] = contactId
          it[UserTable.uid] = candidate.id
        }
        UserSchema.findById(uid)?.uid
          ?: error("openid=$uid 的注册写入后行仍缺失")
      }
    } catch (t: Throwable) {
      runCatching { deleteUserDocument(candidate.id) }.onFailure { t.addSuppressed(it) }
      throw t
    }

    if (winnerPointer != candidate.id) {
      // 输掉注册竞争: 候选档从未被任何指针引用, 清掉后改用赢家的档. 清理失败如实抛出, 不伪装成功.
      deleteUserDocument(candidate.id)
      logger.info("注册竞争失败, 复用赢家指针: openid=$uid candidate=${candidate.id} winner=$winnerPointer")
      return resolvePublishedUser(uid, winnerPointer, contactId)
    }
    return candidate
  }

  /**
   * 解析一个已发布到 SQL 的指针: 档存在则原子并入 openid/contact 后复用;
   * 档缺失 (清库/恢复期间的悬空指针) 则按原 id 重建骨架档, 使后续 Mongo 数据恢复能按 _id 对上号.
   */
  private suspend fun resolvePublishedUser(
    openid: String,
    pointer: String,
    contactId: String,
  ): UserDocument {
    check(pointer.isNotBlank()) { "openid=$openid 的 SQL uid 指针为空白, 数据损坏, 拒绝自动处理" }

    var lastDuplicate: MongoWriteException? = null
    repeat(RESOLVE_ATTEMPTS) { attempt ->
      val existing = UserDocument.withCollection<MongoUserDocument, MongoUserDocument?> {
        findOneAndUpdate(
          idFilter(pointer),
          Updates.combine(
            Updates.addToSet(UserDocument::uid.name, openid),
            Updates.addToSet(UserDocument::contacts.name, contactId),
          ),
          FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
      }
      if (existing != null) {
        return existing.toDomain()
      }

      // 指针悬空是 Mongo 数据丢失的强信号 (清库事故曾无声运行数小时), 必须大声记录.
      logger.warn("SQL 指针悬空, 按原 id 重建骨架档: openid=$openid uid=$pointer attempt=${attempt + 1}")
      val skeleton = UserDocument(pointer, uid = listOf(openid), contacts = listOf(contactId))
      try {
        UserDocument.withCollection<MongoUserDocument, Unit> { insertOne(skeleton.toMongo()) }
        return skeleton
      } catch (e: MongoWriteException) {
        // 并发方抢先重建了同 _id 的档 (或档短暂出现又被删): 回到读取路径重试.
        if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
        lastDuplicate = e
      }
    }
    throw IllegalStateException(
      "openid=$openid uid=$pointer 的用户档在 $RESOLVE_ATTEMPTS 轮重建内始终不稳定",
      lastDuplicate,
    )
  }

  private suspend fun deleteUserDocument(id: String) {
    UserDocument.withCollection<MongoUserDocument, Unit> { deleteOne(idFilter(id)) }
  }

  private suspend fun <T> userSqlQuery(block: suspend () -> T): T =
    sqlDbQueryWithIsolation(Connection.TRANSACTION_READ_COMMITTED, block)
}
