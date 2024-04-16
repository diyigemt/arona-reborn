package com.diyigemt.kivotos.rank

import com.diyigemt.kivotos.KivotosRedisKey
import io.github.crackthecodeabhi.kreds.args.ZAddGTOrLT
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis

private const val RankRedisKey = "$KivotosRedisKey.rank"
private const val FavorRankKey = "$RankRedisKey.favor"
private const val FavorRankSidKey = "$FavorRankKey.sid"
private const val FavorRankUidKey = "$FavorRankKey.uid"
private fun uFavorKey(uid: String) = "$FavorRankUidKey.$uid"
private fun sFavorKey(sid: Int) = "$FavorRankSidKey.$sid"

/**
 * 好感等级背倍增10w以支持累计值排行
 *
 * 累计值当前最高7365
 *
 * 如等级5累计55好感 -> 5 * 10000000 + 55 = 50000055
 *
 * 反序列化 500055 / 10000000 = 5.0000055  好感等级5累计55好感
 */
private const val FavorBase = 10000000

object RankManager {

  private fun packFavorScore(rank: Int, current: Int) = rank * FavorBase + current
  private fun unpackFavorScore(score: Int) = (score / FavorBase) to (score - (score / FavorBase) * FavorBase)

  /**
   * kivotos.rank.favor -> sorted_set(favor, uid-sid) // 总榜 好感最高的学生及老师
   * kivotos.rank.favor.sid.sid -> sorted_set(favor, uid) // 分榜 学生好感最高的老师
   * kivotos.rank.favor.uid.uid -> sorted_set(favor, sid) // 分榜 老师好感最高的学生
   * @param uid 用户id
   * @param sid 学生id
   * @param favor 好感等级 to 好感累计值
   */
  suspend fun updateUserMaxFavor(uid: String, sid: Int, favor: Pair<Int, Int>) {
    val sKey = sFavorKey(sid)
    val uKey = uFavorKey(uid)
    val score = packFavorScore(favor.first, favor.second)
    redis {
      zadd(uKey, gtOrLt = ZAddGTOrLT.GT, scoreMember = score to sid.toString())
      zadd(sKey, gtOrLt = ZAddGTOrLT.GT, scoreMember = score to uid)
      // 获取老师好感最高的学生计入总榜
      val (id, rank) = zrange(uKey, 0, 0, by = null, rev = true, limit = null, withScores = true).let {
        it[0] to it[1].toInt()
      }
      val suKey = "$uid-$id"
      zadd(FavorRankKey, gtOrLt = ZAddGTOrLT.GT, scoreMember = rank to suKey)
    }
  }

  /**
   * 获取用户好感最高的学生id
   */
  suspend fun getUserFavorStudent(uid: String): Int? {
    return redis {
      zrange(uFavorKey(uid), 0, 0, by = null, rev = true, limit = null, withScores = false).firstOrNull()?.toInt()
    }
  }

  /**
   * 获取用户在好感总榜上的排名
   */
  suspend fun getUserFavorRank(uid: String): FavorRankData? {
    val sid = getUserFavorStudent(uid) ?: return null
    return redis {
      zscore(FavorRankKey, "$uid-$sid") to zrevrank(FavorRankKey, "$uid-$sid")
    }.let {
      it.first ?: return null
      FavorRankData(
        it.second ?: return null,
        uid,
        sid,
        unpackFavorScore(it.first!!.toInt())
      )
    }
  }

  /**
   * 获取用户在学生好感榜上的排名
   */
  suspend fun getUserFavorRank(uid: String, sid: Int): FavorRankData? {
    return redis {
      zscore(sFavorKey(sid), uid) to zrevrank(sFavorKey(sid), uid)
    }.let {
      it.first ?: return null
      FavorRankData(
        it.second ?: return null,
        uid,
        sid,
        unpackFavorScore(it.first!!.toInt())
      )
    }
  }

  /**
   * 获取好感总榜/学生排行榜数据
   *
   * [[uid, sid, rank to current]]
   */
  suspend fun getFavorRank(sid: Int? = null, offset: Int = 0, limit: Int = 9): List<FavorRankData> {
    return redis {
      zrange(
        if (sid == null) FavorRankKey else sFavorKey(sid),
        offset.toLong(),
        offset.toLong() + limit.toLong(),
        by = null,
        rev = true,
        limit = null,
        withScores = true
      )
    }.let {
      var rank = 0L
      it.windowed(2, 2, true).map { window ->
        val tmp = window[0].split("-")
        FavorRankData(
          ++rank,
          tmp[0],
          sid ?: tmp[1].toInt(),
          unpackFavorScore(window[1].toInt())
        )
      }
    }
  }
}
