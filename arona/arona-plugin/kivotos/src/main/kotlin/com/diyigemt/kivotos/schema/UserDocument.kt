package com.diyigemt.kivotos.schema

import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.kivotos.coffee.CoffeeDocument
import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.mongodb.client.model.Updates
import com.mongodb.client.result.DeleteResult
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
data class UserDocument(
  @BsonId
  val id: String,
  val student: Map<String, Student> = mapOf(),
) {
  // 删除记录
  suspend fun deleteAccount(): Boolean {
    FavorLevelExcelTable.deleteRecord(id)
    CoffeeDocument.withCollection<CoffeeDocument, DeleteResult> {
      deleteOne(
        filter = idFilter(id)
      )
    }
    return withCollection<UserDocument, DeleteResult> {
      deleteOne(
        filter = idFilter(id)
      )
    }.deletedCount == 1L
  }
  /**
   * 更新学生好感等级, 返回 是否升级, 当前等级, 累计好感值
   */
  suspend fun updateStudentFavor(id: Int, delta: Int): Triple<Boolean, Int, String> {
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
    return Triple(
      st.favor.getOrElse(0) { 0 } != favor.level,
      favor.level,
      "${current - favor.sum}/${favor.next}"
    )
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
