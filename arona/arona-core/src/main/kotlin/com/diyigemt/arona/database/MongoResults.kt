package com.diyigemt.arona.database

import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult

fun UpdateResult.matchedOne(): Boolean = matchedCount == 1L
fun UpdateResult.modifiedOne(): Boolean = modifiedCount == 1L
fun UpdateResult.matched(): Boolean = matchedCount >= 1L
fun DeleteResult.deletedOne(): Boolean = deletedCount == 1L

sealed class MongoWriteOutcome {
  data object Success : MongoWriteOutcome()
  data object NotMatched : MongoWriteOutcome()
  data object NoChange : MongoWriteOutcome()
}

/**
 * 幂等更新友好的分类:
 * - matchedCount == 0        → [NotMatched]
 * - matched 但 modified == 0 → [NoChange] (值未变, 业务通常视为成功)
 * - 其余                      → [Success]
 */
fun UpdateResult.classify(): MongoWriteOutcome = when {
  matchedCount == 0L -> MongoWriteOutcome.NotMatched
  modifiedCount == 0L -> MongoWriteOutcome.NoChange
  else -> MongoWriteOutcome.Success
}
