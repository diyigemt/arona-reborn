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
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and

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
