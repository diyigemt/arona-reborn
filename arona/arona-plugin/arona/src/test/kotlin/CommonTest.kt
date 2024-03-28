import org.junit.jupiter.api.Test
import java.io.File

class CommonTest {
  @Test
  fun test() {
    println(File("data/com.diyigemt.arona/image/some/国际服未来视.png").parentFile.mkdirs())
  }
}
