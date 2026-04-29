package com.diyigemt.kivotos

import com.github.ajalt.clikt.core.Context

/**
 * 本地 Clikt 补丁: fork 时代提供的 `Context.setObject(key, value)` 在 Maven Clikt 5 中没有等价公开 API
 * (Clikt 5 仅有 `Context.findOrSetObject(key, factory)`, 语义是"读不到才设", 不能像 fork 那样"总是替换").
 *
 * Clikt 5 的 `Context.data: Map<String, Any>` 实际由内部 `MutableMap` 支撑, 强转为可变后写键即等价于
 * fork 的替换式 setObject. 仅 `kivotos` 模块在父子命令间用大量 named obj 传参时使用.
 */
@Suppress("UNCHECKED_CAST")
internal fun Context.setObject(key: String, value: Any) {
  (data as MutableMap<String, Any>)[key] = value
}
