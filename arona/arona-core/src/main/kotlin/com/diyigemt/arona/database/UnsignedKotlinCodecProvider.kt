package com.diyigemt.arona.database

import com.mongodb.MongoClientSettings
import org.bson.BsonReader
import org.bson.BsonType
import org.bson.BsonWriter
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecConfigurationException
import org.bson.codecs.configuration.CodecProvider
import org.bson.codecs.configuration.CodecRegistries.fromProviders
import org.bson.codecs.configuration.CodecRegistries.fromRegistries
import org.bson.codecs.configuration.CodecRegistry

/**
 * 为 Kotlin unsigned 类型补齐 BSON codec.
 *
 * Mongo 官方 bson-kotlin 的 DataClassCodec 走 Kotlin 反射逐字段查找 codec, 默认 registry 不
 * 包含 UInt / ULong / UShort / UByte, 读取持久化数据类时会直接抛 [CodecConfigurationException].
 * 所有 arona 模块的 MongoClient 都应在构造时通过 [applyAronaCodecs] 接入本 provider.
 *
 * 当前策略: bit-preserving 重解释.
 *  - UInt   -> BSON Int32 (`uint.toInt()` / `int.toUInt()`)
 *  - ULong  -> BSON Int64 (`ulong.toLong()` / `long.toULong()`)
 *  - UShort -> BSON Int32 (带 range 校验)
 *  - UByte  -> BSON Int32 (带 range 校验)
 *
 * 取舍: 字段仍存为数值类型而非字符串, 便于 driver 操作与索引利用. 但 BSON 的算术与排序
 * 是 signed 语义, 当 unsigned 值越过 Int.MAX_VALUE / Long.MAX_VALUE 后, Mongo 侧会以负数
 * 形式参与比较, `$inc` 也不具备 unsigned 进位语义; 调用方需自行约束取值范围, 或在那种场景
 * 出现前把策略统一升级为 Int64 / 字符串化方案.
 *
 * round-trip 精确性: 即使 UInt 越过 Int.MAX_VALUE, 通过 `.toInt().toUInt()` bit-preserving
 * 反向也能恢复原值; 越界损失只发生在 Mongo 侧的算术/排序语义, 不发生在编解码本身.
 */
object UnsignedKotlinCodecProvider : CodecProvider {
  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> get(clazz: Class<T>, registry: CodecRegistry): Codec<T>? = when (clazz) {
    UInt::class.java -> UIntCodec as Codec<T>
    ULong::class.java -> ULongCodec as Codec<T>
    UShort::class.java -> UShortCodec as Codec<T>
    UByte::class.java -> UByteCodec as Codec<T>
    else -> null
  }
}

/**
 * 统一给 [MongoClientSettings.Builder] 挂上 arona 项目级别的 codec 约定,
 * 防止各模块各自拼装 registry 出现漂移.
 */
fun MongoClientSettings.Builder.applyAronaCodecs(): MongoClientSettings.Builder = apply {
  codecRegistry(
    fromRegistries(
      fromProviders(KotlinxJsonElementCodecProvider),
      fromProviders(UnsignedKotlinCodecProvider),
      MongoClientSettings.getDefaultCodecRegistry(),
    )
  )
}

private object UIntCodec : Codec<UInt> {
  override fun encode(writer: BsonWriter, value: UInt, encoderContext: EncoderContext) {
    writer.writeInt32(value.toInt())
  }

  override fun decode(reader: BsonReader, decoderContext: DecoderContext): UInt =
    readIntLike(reader, "UInt").toInt().toUInt()

  override fun getEncoderClass(): Class<UInt> = UInt::class.java
}

private object ULongCodec : Codec<ULong> {
  override fun encode(writer: BsonWriter, value: ULong, encoderContext: EncoderContext) {
    writer.writeInt64(value.toLong())
  }

  override fun decode(reader: BsonReader, decoderContext: DecoderContext): ULong =
    readIntLike(reader, "ULong").toULong()

  override fun getEncoderClass(): Class<ULong> = ULong::class.java
}

private object UShortCodec : Codec<UShort> {
  override fun encode(writer: BsonWriter, value: UShort, encoderContext: EncoderContext) {
    writer.writeInt32(value.toInt())
  }

  override fun decode(reader: BsonReader, decoderContext: DecoderContext): UShort {
    val value = readIntLike(reader, "UShort")
    if (value !in 0..UShort.MAX_VALUE.toLong()) {
      throw CodecConfigurationException("UShort 超出范围: $value")
    }
    return value.toInt().toUShort()
  }

  override fun getEncoderClass(): Class<UShort> = UShort::class.java
}

private object UByteCodec : Codec<UByte> {
  override fun encode(writer: BsonWriter, value: UByte, encoderContext: EncoderContext) {
    writer.writeInt32(value.toInt())
  }

  override fun decode(reader: BsonReader, decoderContext: DecoderContext): UByte {
    val value = readIntLike(reader, "UByte")
    if (value !in 0..UByte.MAX_VALUE.toLong()) {
      throw CodecConfigurationException("UByte 超出范围: $value")
    }
    return value.toInt().toUByte()
  }

  override fun getEncoderClass(): Class<UByte> = UByte::class.java
}

/**
 * 容忍同字段在历史数据里同时以 Int32 / Int64 出现的情况, 例如不同模块的写路径混用,
 * 或后续把 UInt 升格为 Int64 的过渡期. 其它 BSON 类型一律拒绝, 避免悄悄解出脏值.
 */
private fun readIntLike(reader: BsonReader, typeName: String): Long = when (reader.currentBsonType) {
  BsonType.INT32 -> reader.readInt32().toLong()
  BsonType.INT64 -> reader.readInt64()
  else -> throw CodecConfigurationException(
    "无法把 BSON ${reader.currentBsonType} 解码为 $typeName, 仅支持 Int32 / Int64"
  )
}
