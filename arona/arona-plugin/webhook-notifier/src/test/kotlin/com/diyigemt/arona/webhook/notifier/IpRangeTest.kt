package com.diyigemt.arona.webhook.notifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpRangeTest {
  private fun ip(text: String) = parseClientIp(text)!!
  private fun range(text: String) = IpRange.parse(text)!!

  @Test
  fun `cidr matching follows the prefix bits`() {
    assertTrue(ip("10.255.0.1") in range("10.0.0.0/8"))
    assertFalse(ip("11.0.0.1") in range("10.0.0.0/8"))
    assertTrue(ip("192.168.1.130") in range("192.168.1.128/25"))
    assertFalse(ip("192.168.1.127") in range("192.168.1.128/25"))
    assertTrue(ip("8.8.8.8") in range("0.0.0.0/0"))
    assertTrue(ip("1.2.3.4") in range("1.2.3.4"))
    assertFalse(ip("1.2.3.5") in range("1.2.3.4"))
    assertTrue(ip("2001:db8::1") in range("2001:db8::/32"))
    assertFalse(ip("2001:db9::1") in range("2001:db8::/32"))
  }

  @Test
  fun `ipv4 and ipv6 never match each other`() {
    assertFalse(ip("::1") in range("0.0.0.0/0"))
    assertFalse(ip("1.2.3.4") in range("::/0"))
  }

  @Test
  fun `ipv4 mapped addresses and ranges are treated as ipv4`() {
    assertTrue(ip("::ffff:10.0.0.1") in range("10.0.0.0/8"))
    assertTrue(ip("::ffff:a00:1") in range("10.0.0.0/8"))
    assertTrue(ip("10.0.0.1") in range("::ffff:10.0.0.0/104"))
    assertNull(IpRange.parse("::ffff:0:0/95"))
  }

  @Test
  fun `ipv6 compression and dotted tails parse to the same address`() {
    assertEquals(ip("0:0:0:0:0:0:0:1").toList(), ip("::1").toList())
    assertEquals(ip("64:ff9b::c000:221").toList(), ip("64:ff9b::192.0.2.33").toList())
    assertEquals(ip("1:2:3:4:5:6:7:0").toList(), ip("1:2:3:4:5:6:7::").toList())
  }

  @Test
  fun `anything but a strict ip literal is rejected`() {
    listOf(
      "localhost", "example.com", "1.2.3", "1.2.3.4.5", "01.2.3.4", "256.0.0.1", "1.2.3.4:80",
      "[::1]", "fe80::1%eth0", "1::2::3", "1:2:3:4:5:6:7:8:9", ":1:2:3:4:5:6:7", "12345::", "::1.2.3.4:1",
      "", " ", "1.2.3.4/33", "::/129", "1.2.3.4/", "1.2.3.4/-1", "1.2.3.4/8/8", "*",
    ).forEach { assertNull(IpRange.parse(it), it) }
  }

  private val proxies = listOf(range("127.0.0.1"), range("10.0.0.0/8"))

  private fun resolve(peer: String, vararg xff: String) = resolveClientIp(peer, xff.toList(), proxies)?.let(::formatIp)

  @Test
  fun `untrusted peers are taken as is and cannot spoof forwarded for`() {
    assertEquals("8.8.8.8", resolve("8.8.8.8", "1.1.1.1"))
  }

  @Test
  fun `trusted peers yield the rightmost untrusted hop`() {
    // 最左边的 6.6.6.6 是客户端自己填的, 不能采信.
    assertEquals("203.0.113.9", resolve("127.0.0.1", "6.6.6.6, 203.0.113.9"))
    assertEquals("203.0.113.9", resolve("127.0.0.1", "6.6.6.6, 203.0.113.9", "10.0.0.2"))
  }

  @Test
  fun `broken chains through a trusted peer are unknown`() {
    assertNull(resolve("127.0.0.1"))
    assertNull(resolve("127.0.0.1", "10.0.0.2"))
    assertNull(resolve("127.0.0.1", "unknown"))
    assertNull(resolve("127.0.0.1", "1.1.1.1, garbage, 10.0.0.2"))
    assertNull(resolve("127.0.0.1", "1.1.1.1, , 10.0.0.2"))
    assertNull(resolve("127.0.0.1", "1.1.1.1,".repeat(1000)))
    assertNull(resolve("not-an-ip", "1.1.1.1"))
  }
}
