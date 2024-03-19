package com.diyigemt.kivotos.schema

import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class Student(
  val level: List<Int> = listOf(), // [5, 115] 等级, 经验
  val favor: List<Int> = listOf(), // [5, 115] 等级, 经验
)

@Serializable
data class FavorLevel(
  val sum: Int, // 累积值
  val next: Int, // 下一级值
)

@Serializable
data class UserDocument(
  @BsonId
  val id: String,
  val student: Map<String, Student> = mapOf(),
) {
  /**
   * 更新学生好感等级, 如果升级, 返回新等级和下一级数据
   */
  suspend fun updateStudentFavor(id: Int, delta: Int): Pair<Int, String>? {
    val st = student[id.toString()] ?: Student().also {
      withCollection<UserDocument, UpdateResult> {
        updateOne(
          filter = idFilter(this@UserDocument.id),
          update = Updates.set("${UserDocument::student.name}.$id", it)
        )
      }
    }
    val current = (st.favor.getOrNull(1) ?: 0) + delta
    val favor = FavorLevelExcelTable.findLevel(current)
    withCollection<UserDocument, UpdateResult> {
      updateOne(
        filter = idFilter(this@UserDocument.id),
        update = Updates.set("${UserDocument::student.name}.$id.${Student::favor.name}", listOf(favor.level, current))
      )
    }
    return if (st.favor.getOrElse(0) { 0 } != favor.level) {
      favor.level to "${current - favor.sum}/${favor.next}"
    } else {
      null
    }
  }

  companion object : DocumentCompanionObject {
    override val documentName = "User"
    suspend fun findUserOrCreate(uid: String): UserDocument {
      return withCollection<UserDocument, UserDocument?> {
        find(
          filter = idFilter(uid)
        ).limit(1).firstOrNull()
      } ?: withCollection<UserDocument, UserDocument> {
        UserDocument(uid).also {
          insertOne(it)
        }
      }
    }
  }
}

suspend fun UserCommandSender.kivotosUser(): UserDocument = UserDocument.findUserOrCreate(userDocument().id)
