package com.diyigemt.arona.command.parser

import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.diyigemt.arona.communication.message.TencentOfflineImage.Companion.toTencentImage
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.clikt.parameters.arguments.ProcessedArgument
import com.github.ajalt.clikt.parameters.arguments.RawArgument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.transform.TransformContext

private val conversion: TransformContext.(String) -> TencentOfflineImage = {
  it.toTencentImage() ?: fail(context.localization.imageConversionError(it))
}

/** Convert the argument values to an `TencentImage` */
fun RawArgument.image(): ProcessedArgument<TencentOfflineImage, TencentOfflineImage> = convert(conversion = conversion)

/**
 * Convert the option values to an `TencentImage`
 */
fun RawOption.image(): NullableOption<TencentOfflineImage, TencentOfflineImage> {
  return convert({ localization.imageMetavar() }, conversion = conversion)
}

private fun Localization.imageMetavar() = "image"

private fun Localization.imageConversionError(value: String) = "$value is not a valid image struct"
