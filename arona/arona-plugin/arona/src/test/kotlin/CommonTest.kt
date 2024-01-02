import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.test.Test

@Serializable(with = AronaAIPlayerDeserialize::class)
data class AronaAIPlayer(
  val timestamp: Long? = null,
  val stage: List<Int>? = null
) {
  override fun toString(): String {
    return "timestamp = $timestamp, stage = $stage"
  }
}

object AronaAIPlayerDeserialize : KSerializer<AronaAIPlayer> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AronaAIPlayer", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): AronaAIPlayer {
    val value = kotlin.runCatching {
      decoder.decodeLong()
    }.getOrNull()
    return if (value != null) {
      AronaAIPlayer(timestamp = value)
    } else {
      AronaAIPlayer(stage = decoder.decodeSerializableValue(ListSerializer(Int.serializer())))
    }
  }

  override fun serialize(encoder: Encoder, value: AronaAIPlayer) {
    TODO("Not yet implemented")
  }
}

@Serializable
data class AronaAI(
  val l: List<List<AronaAIPlayer>>,
  val p: List<List<Long>>
)

class CommonTest {
  @Test
  fun testAronaAi() {
    runBlocking {
      val client = HttpClient(CIO) {
        install(ContentNegotiation) {
          Json { ignoreUnknownKeys = true }
        }
      }
      val resp = client.get("https://blue.triple-lab.com/raid/60")
      if (resp.status == HttpStatusCode.OK) {
        val data = Json { ignoreUnknownKeys = true }.decodeFromString<AronaAI>(resp.bodyAsText())
        data.run {
          println(l.first()[1])
        }
      }
    }
  }
}
