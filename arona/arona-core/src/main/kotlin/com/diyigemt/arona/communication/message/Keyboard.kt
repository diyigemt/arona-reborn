@file:OptIn(ExperimentalSerializationApi::class, ExperimentalSerializationApi::class)

package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.utils.uuid
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
sealed class TencentKeyboard

@Serializable
data class TencentTempleKeyboard(
  val id: String,
) : Message, TencentKeyboard() { // TODO custom keyboard
  override fun serialization(): String {
    TODO("Not yet implemented")
  }
}

@Serializable
data class TencentCustomKeyboard(
  val content: TencentCustomKeyboard0,
) : Message, TencentKeyboard() {
  infix fun append(other: TencentCustomKeyboard) {
    content.rows.addAll(other.content.rows)
  }
  infix fun insertTo(other: TencentCustomKeyboard) {
    other.content.rows.addAll(0, content.rows)
  }
  operator fun plus(other: TencentCustomKeyboard): TencentCustomKeyboard {
    content.rows.addAll(other.content.rows)
    return this
  }
  override fun toString(): String {
    return "\n" + content.rows.joinToString("\n") {
      it.buttons.joinToString("\t") { b -> b.toString() }
    }
  }
  override fun serialization(): String {
    TODO("Not yet implemented")
  }
}

@DslMarker
annotation class KeyboardDsl

@KeyboardDsl
@Serializable
data class TencentCustomKeyboard0(
  internal val rows: MutableList<TencentCustomKeyboardRow> = mutableListOf(),
  @SerialName("bot_appid")
  @EncodeDefault
  var botAppid: String? = null
)

fun TencentCustomKeyboard0.row(block: TencentCustomKeyboardRow.() -> Unit) {
  rows.add(
    TencentCustomKeyboardRow().also(block)
  )
}

@KeyboardDsl
@Serializable
data class TencentCustomKeyboardRow(
  internal val buttons: MutableList<TencentKeyboardButton> = mutableListOf(),
)

fun TencentCustomKeyboardRow.button(id: Int, block: TencentKeyboardButton.() -> Unit) {
  button(id.toString(), block)
}

fun TencentCustomKeyboardRow.button(block: TencentKeyboardButton.() -> Unit) {
  button(uuid(), block)
}

fun TencentCustomKeyboardRow.button(label: String, data: String = label, enter: Boolean = false) {
  button(uuid(), label, data, enter)
}

fun TencentCustomKeyboardRow.button(id: String, label: String, data: String = label, enter: Boolean = false) {
  button(id) {
    render {
      this@render.label = label
    }
    action {
      this@action.data = data
      this@action.enter = enter
    }
  }
}

fun TencentCustomKeyboardRow.button(id: String, block: TencentKeyboardButton.() -> Unit) {
  buttons.add(
    TencentKeyboardButton(id).also(block)
  )
}

@KeyboardDsl
@Serializable
data class TencentKeyboardButton(
  @EncodeDefault
  var id: String, // 按钮ID：在一个keyboard消息内设置唯一
  @SerialName("render_data")
  @EncodeDefault
  var renderData: TencentKeyboardButtonRenderData = TencentKeyboardButtonRenderData(),
  @EncodeDefault
  var action: TencentKeyboardButtonActionData = TencentKeyboardButtonActionData(),
) {
  override fun toString(): String {
    return renderData.label
  }
}

fun TencentKeyboardButton.render(block: TencentKeyboardButtonRenderData.() -> Unit) {
  renderData = TencentKeyboardButtonRenderData().also(block)
}

fun TencentKeyboardButton.action(block: TencentKeyboardButtonActionData.() -> Unit) {
  action = TencentKeyboardButtonActionData().also(block)
}

@KeyboardDsl
@Serializable
data class TencentKeyboardButtonActionData(
  @EncodeDefault
  var type: TencentKeyboardButtonActionType = TencentKeyboardButtonActionType.COMMAND,
  @EncodeDefault
  var data: String = "", // 操作相关的数据
  @EncodeDefault
  var reply: Boolean? = null, // 指令按钮可用，指令是否带引用回复本消息，默认 false。支持版本 8983
  @EncodeDefault
  var enter: Boolean? = null, // 指令按钮可用，点击按钮后直接自动发送 data，默认 false。支持版本 8983
  /**
   * 本字段仅在指令按钮下有效，设置后后会忽略 action.enter 配置。
   * 设置为 1 时 ，点击按钮自动唤起启手Q选图器，其他值暂无效果。
   * （仅支持手机端版本 8983+ 的单聊场景，桌面端不支持）
   */
  @EncodeDefault
  var anchor: Int? = null,
  @SerialName("click_limit")
  @EncodeDefault
  var clickLimit: Int? = null, // 【已弃用】可操作点击的次数，默认不限
  @SerialName("at_bot_show_channel_list")
  @EncodeDefault
  var atBotShowChannelList: Boolean? = null, // 【已弃用】指令按钮可用，弹出子频道选择器，默认 false
  @SerialName("unsupport_tips")
  @EncodeDefault
  var unsupportTips: String? = null, // 客户端不支持本action的时候，弹出的toast文案
  @EncodeDefault
  var permission: TencentKeyboardButtonActionPermissionData = TencentKeyboardButtonActionPermissionData(),
)

@Serializable
data class TencentKeyboardButtonActionPermissionData(
  @EncodeDefault
  var type: TencentKeyboardButtonActionDataType = TencentKeyboardButtonActionDataType.ANY_ONE,
  @SerialName("specify_user_ids") // 有权限的用户 id 的列表
  @EncodeDefault
  var specifyUserIds: List<String>? = null,
  @SerialName("specify_role_ids") // 有权限的身份组 id 的列表（仅频道可用）
  @EncodeDefault
  val specifyRoleIds: List<String>? = null,
)

@Serializable(with = TencentKeyboardButtonActionType.Companion::class)
enum class TencentKeyboardButtonActionType(val id: Int) {
  JUMP(0), // 跳转按钮：http 或 小程序 客户端识别 scheme
  CALLBACK(1), // 回调按钮：回调后台接口, data 传给后台
  COMMAND(2); // 指令按钮：自动在输入框插入 @bot data

  companion object : KSerializer<TencentKeyboardButtonActionType> {
    private val map = entries.associateBy { it.id }
    override fun deserialize(decoder: Decoder) = map.getOrDefault(decoder.decodeInt(), COMMAND)
    override val descriptor = PrimitiveSerialDescriptor("TencentKeyboardButtonActionType", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: TencentKeyboardButtonActionType) = encoder.encodeInt(value.id)
  }
}

@Serializable(with = TencentKeyboardButtonActionDataType.Companion::class)
enum class TencentKeyboardButtonActionDataType(val id: Int) {
  SPECIFY(0), // 指定用户可操作
  MANAGER(1), // 仅管理者可操作
  ANY_ONE(2), // 所有人可操作
  SPECIFIC_ROLE(3); // 指定身份组可操作（仅频道可用）

  companion object : KSerializer<TencentKeyboardButtonActionDataType> {
    private val map = entries.associateBy { it.id }
    override fun deserialize(decoder: Decoder) = map.getOrDefault(decoder.decodeInt(), ANY_ONE)
    override val descriptor = PrimitiveSerialDescriptor("TencentKeyboardButtonActionDataType", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: TencentKeyboardButtonActionDataType) = encoder.encodeInt(value.id)
  }
}
@KeyboardDsl
@Serializable
data class TencentKeyboardButtonRenderData(
  @EncodeDefault
  var label: String = "", // 按钮上的文字
  @SerialName("visited_label")
  var visitedLabel: String? = null, // 点击后按钮的上文字
  @EncodeDefault
  var style: TencentKeyboardButtonRenderDataStyle = TencentKeyboardButtonRenderDataStyle.Blue, // 按钮样式
)

@Serializable(with = TencentKeyboardButtonRenderDataStyle.Companion::class)
enum class TencentKeyboardButtonRenderDataStyle(val id: Int) {
  Gray(0), // 灰色线框
  Blue(1); // 蓝色线框

  companion object : KSerializer<TencentKeyboardButtonRenderDataStyle> {
    private val map = entries.associateBy { it.id }
    override fun deserialize(decoder: Decoder) = map.getOrDefault(decoder.decodeInt(), Blue)
    override val descriptor = PrimitiveSerialDescriptor("TencentKeyboardButtonRenderDataStyle", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: TencentKeyboardButtonRenderDataStyle) = encoder.encodeInt(value.id)
  }
}

/**
 * {
 *   "rows": [
 *     {
 *       "buttons": [
 *         {
 *           "id": "1",
 *           "render_data": {
 *             "label": "⬅️上一页",
 *             "visited_label": "⬅️上一页"
 *           },
 *           "action": {
 *             "type": 1,
 *             "permission": {
 *               "type": 2
 *             },
 *             "data": "data"
 *           }
 *         },
 *         {
 *           "id": "2",
 *           "render_data": {
 *             "label": "➡️下一页",
 *             "visited_label": "➡️下一页"
 *           },
 *           "action": {
 *             "type": 1,
 *             "permission": {
 *               "type": 2
 *             },
 *             "data": "data"
 *           }
 *         }
 *       ]
 *     },
 *     {
 *       "buttons": [
 *         {
 *           "id": "3",
 *           "render_data": {
 *             "label": "📅 打卡（5）",
 *             "visited_label": "📅 打卡（5）"
 *           },
 *           "action": {
 *             "type": 1,
 *             "permission": {
 *               "type": 2
 *             },
 *             "data": "data"
 *           }
 *         }
 *       ]
 *     }
 *   ]
 * }
 * val button = tencentCustomKeyboard {
 *   row {
 *     button("1") {
 *       render {
 *         label = "⬅\uFE0F上一页"
 *         visitedLabel = "⬅\uFE0F上一页"
 *       }
 *       action {
 *         type = TencentKeyboardButtonActionType.CALLBACK
 *         data = ""
 *       }
 *     }
 *     button("2") {
 *       render {
 *         label = "➡\uFE0F下一页"
 *         visitedLabel = "➡\uFE0F下一页"
 *       }
 *       action {
 *         type = TencentKeyboardButtonActionType.CALLBACK
 *         data = ""
 *       }
 *     }
 *   }
 *   row {
 *     button("3") {
 *       render {
 *         label = "\uD83D\uDCC5 打卡（5）"
 *         visitedLabel = "\uD83D\uDCC5 打卡（5）"
 *       }
 *       action {
 *         type = TencentKeyboardButtonActionType.CALLBACK
 *         data = ""
 *       }
 *     }
 *   }
 * }
 *
 */
fun tencentCustomKeyboard(botAppId: String, init: TencentCustomKeyboard0.() -> Unit): TencentCustomKeyboard {
  // TODO id去重 按钮上限
  return TencentCustomKeyboard(TencentCustomKeyboard0(botAppid = botAppId).also(init))
}
