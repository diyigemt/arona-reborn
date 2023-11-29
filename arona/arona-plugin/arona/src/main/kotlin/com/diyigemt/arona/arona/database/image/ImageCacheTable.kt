package com.diyigemt.arona.arona.database.image

import com.diyigemt.arona.arona.database.Database
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDateTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

@Database
object ImageCacheTable : IntIdTable(name = "ImageCache") {
  val hash = text("hash")
  val resourceId = text("resource_id")
  val expired = text("expired") // yyyy-MM-dd HH:mm:ss
}

class ImageCacheSchema(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<ImageCacheSchema>(ImageCacheTable) {
    fun findImage(hash: String) =
      ImageCacheSchema.find { ImageCacheTable.hash eq hash }.firstOrNull()?.toTencentImage()
  }

  var hash by ImageCacheTable.hash
  var resourceId by ImageCacheTable.resourceId
  var expired by ImageCacheTable.expired

  fun toTencentImage() : TencentImage = TencentOfflineImage(
    resourceId,
    "",
    0,
  )
}

fun TencentImage.update(hash: String) {
  when (val im = ImageCacheSchema.find { ImageCacheTable.hash eq hash }.firstOrNull()) {
    is ImageCacheSchema -> {
      im.hash = hash
      im.resourceId = this@update.resourceId
      im.expired = now().plus(this@update.ttl, DateTimeUnit.SECOND).toDateTime()
    }
    else -> {
      ImageCacheSchema.new {
        this@new.hash = hash
        this@new.resourceId = this@update.resourceId
        this.expired = now().plus(this@update.ttl, DateTimeUnit.SECOND).toDateTime()
      }
    }
  }
}
