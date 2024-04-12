package com.diyigemt.kivotos.rank

import com.diyigemt.kivotos.KivotosRedisKey
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis

private const val RankRedisKey = "$KivotosRedisKey.rank"
private const val FavorRankKey = "$RankRedisKey.favor"

object RankManager {

  suspend fun saveUserMaxFavor(student: Int, max: Int) {
    redis {
      zscore()
    }
  }
}
