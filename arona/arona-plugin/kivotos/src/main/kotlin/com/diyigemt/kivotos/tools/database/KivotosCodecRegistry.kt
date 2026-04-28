package com.diyigemt.kivotos.tools.database

import com.diyigemt.arona.database.KotlinxJsonElementCodecProvider
import com.diyigemt.arona.database.UnsignedKotlinCodecProvider
import com.mongodb.MongoClientSettings
import org.bson.codecs.configuration.CodecRegistries.fromProviders
import org.bson.codecs.configuration.CodecRegistries.fromRegistries
import org.bson.codecs.kotlinx.KotlinSerializerCodecProvider

/**
 * kivotos 专用 Mongo codec 链。
 *
 * 链顺序:
 *  - [KotlinSerializerCodecProvider]: kotlinx.serialization 接管全部 `@Serializable` 文档,
 *    UInt / JsonElement 在 kotlinx 内部各自有原生支持, 不再走自定义 codec
 *  - [KotlinxJsonElementCodecProvider] / [UnsignedKotlinCodecProvider]: 兜底, 仅当文档漏标
 *    `@Serializable` 退化到反射路径时命中
 *  - 默认 registry: 兜底中的兜底
 *
 * 工厂定义在 kivotos 模块, 是为了让 [KotlinSerializerCodecProvider] 的类解析跟随
 * plugin classloader (kivotos shadowJar 自带 bson-kotlinx) 完成。
 * arona-core (host) 未引入 bson-kotlinx, 把工厂放在 host 下会在调用时由 host classloader
 * 解析此符号引用, 必抛 `NoClassDefFoundError`。
 */
fun MongoClientSettings.Builder.applyKivotosCodecs(): MongoClientSettings.Builder = apply {
  codecRegistry(
    fromRegistries(
      fromProviders(KotlinSerializerCodecProvider()),
      fromProviders(KotlinxJsonElementCodecProvider),
      fromProviders(UnsignedKotlinCodecProvider),
      MongoClientSettings.getDefaultCodecRegistry(),
    )
  )
}
