@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.diyigemt.arona.arona.database.image

import com.diyigemt.arona.arona.database.Database
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.command.isGroup
import com.diyigemt.arona.communication.command.isPrivate
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.diyigemt.arona.communication.message.getMediaUrlFromMediaInfo
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDateTime
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
// Exposed 1.x: SqlExpressionBuilder 对象 deprecated, eq/greater/and 等改为 v1.core 顶层函数.
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

enum class ImageCacheContactType {
  Private,
  Group,
  Guild
}

fun CommandSender.contactType() =
  when {
    isGroup() -> ImageCacheContactType.Group
    isPrivate() -> ImageCacheContactType.Private
    else -> ImageCacheContactType.Guild
  }

@Database
object ImageCacheTable : IntIdTable(name = "ImageCache") {
  val hash = text("hash")
  val resourceId = text("resource_id")
  val from = enumerationByName<ImageCacheContactType>("from", 10)
  val expired = text("expired") // yyyy-MM-dd HH:mm:ss
}

class ImageCacheSchema(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<ImageCacheSchema>(ImageCacheTable) {
    fun findImage(hash: String, from: ImageCacheContactType = ImageCacheContactType.Group) =
      when (from) {
        ImageCacheContactType.Guild -> null
        else -> ImageCacheSchema.find {
          (ImageCacheTable.hash eq hash) and
              (ImageCacheTable.expired greater currentDateTime()) and
              (ImageCacheTable.from eq from)
        }.firstOrNull()?.toTencentImage()
      }
  }

  var hash by ImageCacheTable.hash
  var resourceId by ImageCacheTable.resourceId
  var from by ImageCacheTable.from
  var expired by ImageCacheTable.expired

  fun toTencentImage(): TencentImage = TencentOfflineImage(
    resourceId,
    "",
    0,
    kotlin.runCatching { getMediaUrlFromMediaInfo(resourceId) }.getOrDefault("")
  )
}

fun TencentImage.update(hash: String, from: ImageCacheContactType = ImageCacheContactType.Group) {
  if (from == ImageCacheContactType.Guild) return
  val expired = now().let {
    if (this@update.ttl == 0L) {
      it.plus(DateTimePeriod(years = 1), TimeZone.currentSystemDefault())
    } else it.plus(this@update.ttl - 100, DateTimeUnit.SECOND)
  }.toDateTime()
  when (val im = ImageCacheSchema.find { ImageCacheTable.hash eq hash }.firstOrNull()) {
    is ImageCacheSchema -> {
      im.hash = hash
      im.resourceId = this@update.resourceId
      im.expired = expired
    }

    else -> {
      ImageCacheSchema.new {
        this@new.hash = hash
        this@new.from = from
        this@new.resourceId = this@update.resourceId
        this.expired = expired
      }
    }
  }
}
