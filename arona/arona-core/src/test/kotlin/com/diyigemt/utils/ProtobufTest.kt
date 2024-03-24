package com.diyigemt.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test

@Serializable
data class Foo(
  val a: Int,
  val b: String,
)

class ProtobufTest {
  @OptIn(ExperimentalSerializationApi::class)
  @Test
  fun testEncode() {
    val encoder = ProtoBuf { encodeDefaults = true }
    val encode = encoder.encodeToByteArray(Foo(1, ""))
    println(encoder.decodeFromByteArray<Foo>(encode))
  }
}