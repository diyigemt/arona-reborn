@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.utils

import com.diyigemt.arona.communication.message.MediaUuid
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ProtobufTest {
  @OptIn(ExperimentalSerializationApi::class, ExperimentalEncodingApi::class)
  @Test
  fun testEncode() {
    // CgoyODU0MjA3NTc5EhT7g6DIM0o-V8RujBhvTmfnyhOeABiUCSD_Ciiiikej34yFA1CAvaMB
    val a = listOf(
      0x0a.toByte(),
      0x0a.toByte(),
      0x32.toByte(),
      0x38.toByte(),
      0x35.toByte(),
      0x34.toByte(),
      0x32.toByte(),
      0x30.toByte(),
      0x37.toByte(),
      0x35.toByte(),
      0x37.toByte(),
      0x39.toByte(),
      0x12.toByte(),
      0x14.toByte(),
      0xfb.toByte(),
      0x83.toByte(),
      0xa0.toByte(),
      0xc8.toByte(),
      0x33.toByte(),
      0x4a.toByte(),
      0x3e.toByte(),
      0x57.toByte(),
      0xc4.toByte(),
      0x6e.toByte(),
      0x8c.toByte(),
      0x18.toByte(),
      0x6f.toByte(),
      0x4e.toByte(),
      0x67.toByte(),
      0xe7.toByte(),
      0xca.toByte(),
      0x13.toByte(),
      0x9e.toByte(),
      0x00.toByte(),
      0x18.toByte(),
      0x94.toByte(),
      0x09.toByte(),
      0x20.toByte(),
      0xff.toByte(),
      0x0a.toByte(),
      0x28.toByte(),
      0xa2.toByte(),
      0x8a.toByte(),
      0x47.toByte(),
    ).toByteArray()
    println(Base64.encode(a))
    val encoder = ProtoBuf { encodeDefaults = true }
    println(encoder.decodeFromByteArray<MediaUuid>(a))
  }
}
