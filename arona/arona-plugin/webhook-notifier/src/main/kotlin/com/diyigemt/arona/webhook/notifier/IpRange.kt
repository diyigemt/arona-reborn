package com.diyigemt.arona.webhook.notifier

import java.net.InetAddress

/**
 * 一段 IP 网段. 只接受 IP 字面量 (可带 /前缀), 不接受主机名, 也不做 DNS 解析:
 * 白名单要是能写域名, 校验结果就取决于解析那一刻的 DNS.
 */
internal class IpRange private constructor(private val network: ByteArray, private val prefix: Int) {
  operator fun contains(ip: ByteArray): Boolean = ip.size == network.size && mask(ip, prefix).contentEquals(network)

  override fun toString(): String = "${formatIp(network)}/$prefix"

  companion object {
    /** 解析失败返回 null. IPv4-mapped IPv6 网段 (::ffff:0:0/96 以内) 换算成 IPv4 网段, 与客户端地址的归一化一致. */
    fun parse(entry: String): IpRange? {
      val parts = entry.trim().split('/')
      if (parts.size > 2) return null
      var raw = parseIpLiteral(parts[0]) ?: return null
      var prefix = if (parts.size == 1) {
        raw.size * 8
      } else {
        parts[1].takeIf { it.length in 1..3 && it.all(::isDecimalDigit) }?.toInt() ?: return null
      }
      if (prefix > raw.size * 8) return null
      if (isMappedV4(raw)) {
        if (prefix < 96) return null
        raw = raw.copyOfRange(12, 16)
        prefix -= 96
      }
      return IpRange(mask(raw, prefix), prefix)
    }
  }
}

/** 解析单个地址并把 IPv4-mapped IPv6 归一化成 IPv4, 用于和白名单比对. 无法解析返回 null. */
internal fun parseClientIp(text: String): ByteArray? =
  parseIpLiteral(text)?.let { if (isMappedV4(it)) it.copyOfRange(12, 16) else it }

/** getByAddress 只做格式化, 不反查. */
internal fun formatIp(raw: ByteArray): String = InetAddress.getByAddress(raw).hostAddress

/** X-Forwarded-For 总长上限, 超出视为来源未知: 正常链路不会有这么多跳. */
private const val MAX_FORWARDED_CHARS = 2048

/**
 * 求真实来源. socket 对端不在 [trusted] 内时就是来源本身; 在内时说明经过反向代理,
 * 从 X-Forwarded-For 右端往左跳过受信代理, 取第一个不受信的地址.
 * 只取右端是因为左边的条目由客户端自己填写, 可以伪造; 右端是受信代理追加的.
 * 链路残缺 (缺头、条目非法、全部受信) 返回 null 即来源未知, 由调用方按拒绝处理.
 */
internal fun resolveClientIp(peer: String, forwardedFor: List<String>, trusted: List<IpRange>): ByteArray? {
  val socket = parseClientIp(peer) ?: return null
  if (trusted.none { socket in it }) return socket
  if (forwardedFor.isEmpty() || forwardedFor.sumOf { it.length } > MAX_FORWARDED_CHARS) return null
  for (token in forwardedFor.flatMap { it.split(',') }.asReversed()) {
    val hop = parseClientIp(token.trim()) ?: return null
    if (trusted.none { hop in it }) return hop
  }
  return null
}

private fun parseIpLiteral(text: String): ByteArray? = if (':' in text) parseIpv6(text) else parseIpv4(text)

/** 只认四段十进制, 拒绝前导零 (部分实现会按八进制解释) 和 `1.2.3` 这类缩写. */
private fun parseIpv4(text: String): ByteArray? {
  val parts = text.split('.')
  if (parts.size != 4) return null
  val out = ByteArray(4)
  parts.forEachIndexed { i, part ->
    if (part.length !in 1..3 || !part.all(::isDecimalDigit)) return null
    if (part.length > 1 && part[0] == '0') return null
    val value = part.toInt()
    if (value > 255) return null
    out[i] = value.toByte()
  }
  return out
}

/** 支持 :: 压缩和末尾点分 IPv4; 拒绝方括号、zone ID (%eth0) 和端口. */
private fun parseIpv6(text: String): ByteArray? {
  if (text.length > 45) return null
  val gap = text.indexOf("::")
  if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
  val head = ipv6Groups(if (gap >= 0) text.substring(0, gap) else text, allowIpv4Tail = gap < 0) ?: return null
  val tail = if (gap >= 0) ipv6Groups(text.substring(gap + 2), allowIpv4Tail = true) ?: return null else emptyList()
  val count = head.size + tail.size
  if (if (gap >= 0) count > 7 else count != 8) return null
  val groups = head + List(8 - count) { 0 } + tail
  return ByteArray(16) { i -> (groups[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
}

private fun ipv6Groups(part: String, allowIpv4Tail: Boolean): List<Int>? {
  if (part.isEmpty()) return emptyList()
  val items = part.split(':')
  val out = ArrayList<Int>(8)
  items.forEachIndexed { i, item ->
    if (allowIpv4Tail && i == items.lastIndex && '.' in item) {
      val v4 = parseIpv4(item) ?: return null
      out += ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
      out += ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
    } else {
      if (item.length !in 1..4 || !item.all(::isHexDigit)) return null
      out += item.toInt(16)
    }
  }
  return out
}

private fun isDecimalDigit(c: Char) = c in '0'..'9'

private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun isMappedV4(raw: ByteArray): Boolean =
  raw.size == 16 && (0 until 10).all { raw[it] == 0.toByte() } && raw[10] == 0xff.toByte() && raw[11] == 0xff.toByte()

private fun mask(raw: ByteArray, prefix: Int): ByteArray = ByteArray(raw.size) { i ->
  val bits = (prefix - i * 8).coerceIn(0, 8)
  (raw[i].toInt() and (0xff shl (8 - bits))).toByte()
}
