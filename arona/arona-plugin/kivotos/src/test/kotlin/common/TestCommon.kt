package common

import com.diyigemt.kivotos.schema.FavorLevelExcelTable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.properties.ReadOnlyProperty

abstract class T {
  init {
    println("T init.")
  }
}

class F : T() {
  init {
    println("F init.")
  }
}

class TestCommon {
  @Test
  fun testInit() {
    val f = F()
    println(1)
  }

  open class A {
    val list: List<Any> = listOf()
  }

  inline fun <reified T : Any> A.required(): ReadOnlyProperty<A, T?> {
    return ReadOnlyProperty { thisRef, property ->
      println(property.name)
      list.firstOrNull { it is T } as? T
    }
  }

  @Test
  fun testDelegate() {
    val c = object : A() {
      val b by required<Int>()
      fun test() {
        println(b)
      }
    }
    c.test()
  }

  @Test
  fun testFavor() {
    runBlocking {
      println(FavorLevelExcelTable.findLevel(16))
    }
  }
}
