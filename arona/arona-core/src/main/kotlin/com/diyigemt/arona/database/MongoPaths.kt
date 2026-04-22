package com.diyigemt.arona.database

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.toMongodbKey
import kotlin.reflect.KProperty

/** "$name.${sub.name}" —— 拼接两级字段路径. */
infix fun KProperty<*>.dot(sub: KProperty<*>): String = "$name.${sub.name}"

/**
 * "$name.part1.part2..." —— 用于嵌套字段路径 (含动态 key 如 map/数组索引).
 * 动态段原样传入, 不做编码; 插件配置路径请使用 [pluginConfigPath].
 */
fun KProperty<*>.dot(vararg parts: String): String =
  (listOf(name) + parts).joinToString(".")

/**
 * 插件配置字段路径: `<root>.<encoded pluginId>.<key>`.
 * 自动对 pluginId 做 `.` → `·` 编码 (避免和 Mongo 点路径冲突).
 * 适用于 UserDocument.config / ContactDocument.config / ContactMember.config.
 */
fun pluginConfigPath(root: KProperty<*>, pluginId: String, key: String): String =
  "${root.name}.${pluginId.toMongodbKey()}.$key"

/** "members._id" —— ContactDocument.members 数组按 _id 过滤. */
internal fun membersIdPath(): String = "${ContactDocument::members.name}._id"

/** "members.\$.<field>" —— 对 ContactDocument.members 数组匹配元素的字段更新路径. */
internal fun memberPositional(field: KProperty<*>): String =
  "${ContactDocument::members.name}.\$.${field.name}"

/** "members.\$.<field>.<trailing...>" —— 多层嵌套更新路径. */
internal fun memberPositional(field: KProperty<*>, vararg trailing: String): String =
  listOf(ContactDocument::members.name, "\$", field.name, *trailing).joinToString(".")
