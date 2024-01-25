package com.diyigemt.arona.arona.tools

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.gacha.GachaPoolSchema
import com.diyigemt.arona.arona.database.gacha.GachaPoolTable
import com.diyigemt.arona.arona.database.student.StudentRarity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.skia.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.max
import kotlin.math.min

data class GachaResultItem(
  val image: String, // 头像路径
  var isNew: Boolean = false, // 是否new
  val isPickup: Boolean = false, // 是否pickup
  val rarity: StudentRarity = StudentRarity.R, // 学生稀有度
)

data class GachaResult(
  val name: String, // 卡池名称
  val point: Int, // 累计点数
  val result: List<GachaResultItem>,
)

object GachaTool {
  private val bg = Image.fromGachaPath("Gacha_BG_R.png")
  private val star = Image.fromGachaPath("Common_Icon_Formation_Star.png")
  private val bgTriangle = Image.fromGachaPath("UITex_BGPoliLight_3.png")
  private val newIm = Image.fromGachaPath("Gacha_New.png")
  private val pickUpIm = Image.fromGachaPath("ImgFont_Pickup.png")
  private val kira = Image.fromGachaPath("star.png")
  private val iconPoint = Image.fromGachaPath("point_icon.png")
  private val srFilterRect = Rect(65f, 0f, 335f, 430f)
  private val srColorBgRect = Rect(80f, 75f, 320f, 355f)
  private val borderRect = RRect.makeXYWH(85f, 80f, 230f, 270f, 15f)
  private val starRect = RRect.makeComplexXYWH(85f, 295f, 230f, 270f, arrayOf(0f, 0f, 20f, 20f).toFloatArray())
  private val headRect = Rect(37.2f, 80f, 302.8f, 295f)
  private val starBgColor = Color4f.new(99, 112, 130)
  private val starBgPaint = Paint().apply {
    color4f = starBgColor
  }
  private val starBgBorderPaint = Paint().apply {
    strokeWidth = 3f
    color = Color.WHITE
    mode = PaintMode.STROKE
  }
  private val srColor = Color4f.new(255, 247, 120)
  private val ssrColor = Color4f.new(242, 194, 218)
  private val boldFont = Font(Typeface.makeFromFile(Arona.dataFolder("gacha", "font-bold.otf").toFile().path), 54f)
  private val normalFont = Font(Typeface.makeFromFile(Arona.dataFolder("gacha", "font-bold.otf").toFile().path), 32f)
  val NormalPool by lazy { // 常驻池
    dbQuery {
      GachaPoolSchema.find { GachaPoolTable.id eq 1 }.toList().first().toGachaPool()
    }
  }
  fun generateGachaImage(result: GachaResult): Surface {
    val surface = Surface.makeRasterN32Premul(2340, 1080)
    val canvas = surface.canvas
    // 底色
    canvas.drawImageRect(bg, Rect(0f, 0f, 2340f, 1080f))
    // 三角形
    canvas.drawImageRect(bgTriangle, Rect(-1168f, -1001f, 2260f, 2848f), Paint().apply {
      alpha = 100
    })

    // 确认按钮
    val confirmBtn = Surface.makeRasterN32Premul(424, 130).also {
      it.canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
      val btn = RRect.makeXYWH(18f, 0f, 388f, 120f, 20f)
      it.canvas.drawRRect(btn, Paint().apply {
        color4f = Color4f.new(94, 221, 254)
        imageFilter = ImageFilter.makeDropShadow(
          5f, 5f,
          5f, 5f,
          Color4f(0.5f, 0.5f, 0.5f, 0.5f).toColor(),
        )
      })
      it.canvas.drawPath(Path().apply {
        addRRect(btn)
      }, Paint().apply {
        strokeWidth = 0.5f
        color4f = Color4f(0.5f, 0.5f, 0.5f, 1f)
        mode = PaintMode.STROKE
      })
      it.canvas.clipRRect(btn, ClipMode.INTERSECT, true)
      it.canvas.drawImageRect(bgTriangle, Rect(-620f, -447.8f, 1021f, 1193f), Paint().apply {
        alpha = 120
      })
      it.canvas.resetMatrix()
      it.canvas.drawString("OK", (360 - boldFont.measureTextWidthEx("OK")) / 2 + 18, 80f, boldFont, Paint().apply {
        color = Color.makeARGB(255, 43, 70, 120)
      })
    }
    canvas.drawImage(confirmBtn.makeImageSnapshot(), 970f, 870f)

    // 点数
    val pointReact = Surface.makeRasterN32Premul(338 + 55, 120).also {
      it.canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
      val react = RRect.makeXYWH(16.5f + 55, 0f, 305f, 110f, 10f)
      it.canvas.drawRRect(react, Paint().apply {
        shader = Shader.makeLinearGradient(
          Point(107.5f, 0f),
          Point(107.5f, 110f),
          colors = arrayOf(
            Color4f.new(60, 113, 144),
            Color4f.new(60, 113, 144),
            Color4f.new(255, 255, 255),
            Color4f.new(255, 255, 255),
            Color4f.new(60, 113, 144),
            Color4f.new(60, 113, 144),
            Color4f.new(36, 90, 126),
            Color4f.new(36, 90, 126),
          ),
          cs = ColorSpace.sRGB,
          positions = arrayOf(0f, 0.02f, 0.02f, 0.48f, 0.48f, 0.52f, 0.52f, 1f).toFloatArray(),
          style = GradientStyle.DEFAULT
        )
      })
      it.canvas.drawPath(Path().apply {
        addRRect(react)
      }, Paint().apply {
        strokeWidth = 2f
        color4f = Color4f.new(60, 113, 144)
        mode = PaintMode.STROKE
      })
      it.canvas.resetMatrix()
      // 点数
      it.canvas.drawString("呼び出しポインと",
        55f + (338 - normalFont.measureTextWidthEx("呼び出しポインと")) / 2,
        (110 / 2f - 24f) / 2 + 22,
        normalFont,
        Paint().apply {
          color4f = Color4f.new(60, 113, 144)
        })
      it.canvas.drawString(
        result.point.toString(),
        55f + (338 - normalFont.measureTextWidthEx(result.point.toString())) / 2,
        (110 / 2f - 24f) / 2 + 110 / 2f + 22,
        normalFont,
        Paint().apply {
          color = Color.WHITE
        })
      it.canvas.setMatrix(Matrix33.makeScale(0.5f))
      // 点数图标
      it.canvas.drawImage(iconPoint, 0f, 5f)
    }
    canvas.drawImage(pointReact.makeImageSnapshot(), 1860f - 55, 870f)
    // 结果
    if (result.result.size == 1) {
      generateGachaStudentImage(result.result.first()).also {
        canvas.drawImage(it.makeImageSnapshot(), (2340f - 360) / 2 + 50, (1080f - 430) / 2 - 50)
      }
    } else {
      result.result.forEachIndexed { idx, it ->
        val x = (2340f - 1410) / 2 + (idx % 5) * (270 + 15) - 40
        val y = (idx / 5) * (300 + 45) + 50f
        generateGachaStudentImage(it).also { im ->
          canvas.drawImage(im.makeImageSnapshot(), x, y)
        }
      }
    }
    return surface
  }

  private fun generateGachaStudentImage(item: GachaResultItem): Surface {
    val generate = Surface.makeRasterN32Premul(360, 430)
    when (item.rarity) {
      StudentRarity.R -> drawR(generate, item)
      StudentRarity.SR -> drawSR(generate, item)
      StudentRarity.SSR -> drawSSR(generate, item)
    }
    val canvas = generate.canvas
    canvas.resetMatrix()
    if (item.isNew) {
      canvas.drawImageRect(newIm, Rect(60f, 55f, 137f, 88.6f))
      if (item.isPickup) {
        canvas.drawImageRect(pickUpIm, Rect(50f, 85f, 190.7f, 139.6f))
      }
    } else {
      if (item.isPickup) {
        canvas.drawImageRect(pickUpIm, Rect(60f, 55f, 200.7f, 109.6f))
      }
    }
    return generate
  }

  private fun drawR(surface: Surface, item: GachaResultItem) {
    val canvas = surface.canvas
    val head = Image.fromGachaPath(item.image)
    // 15°倾斜
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    // 三角形背景
    canvas.save()
    canvas.clipRRect(RRect.makeXYWH(85f, 80f, 230f, 220f, 15f), ClipMode.INTERSECT, true)
    canvas.drawImageRect(bgTriangle, Rect(38f, -98f, 774.5f, 612.5f), Paint().apply {
      alpha = 200
    })
    canvas.restore()
    canvas.save()
    // 星星背景
    canvas.clipRRect(borderRect, ClipMode.INTERSECT, true)
    canvas.drawRRect(starRect, starBgPaint)
    canvas.drawRRect(starRect, starBgBorderPaint)
    canvas.restore()
    // 学生头
    canvas.resetMatrix()
    canvas.drawImageRect(head, headRect)
    // 边框
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    canvas.drawPath(Path().apply {
      addRRect(borderRect)
    }, Paint().apply {
      strokeWidth = 5f
      color = Color.WHITE
      mode = PaintMode.STROKE
    })
    // 星星
    canvas.resetMatrix()
    canvas.restore()
    val left = (230f - 35) / 2 + 40
    val top = 215f + (55 - 33.5f) / 2 + 78
    canvas.drawImageRect(
      star, Rect(
        left, top, left + 35, top + 33.5f
      )
    )
  }

  private fun drawSR(surface: Surface, item: GachaResultItem) {
    val head = Image.fromGachaPath(item.image)
    val canvas = surface.canvas
    // 15°倾斜
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    // 半透明背景
    canvas.drawRect(srFilterRect, Paint().apply {
      shader = Shader.makeLinearGradient(
        Point(130f, 430f),
        Point(190f, 0f),
        colors = arrayOf(
          Color4f.new(255, 255, 255, 0),
          Color4f.new(255, 255, 255, 76),
          Color4f.new(255, 255, 255, 178),
          Color4f.new(255, 255, 255, 230),
          Color4f.new(255, 255, 255, 178),
          Color4f.new(255, 255, 255, 76),
          Color4f.new(255, 255, 255, 0),
        ),
        cs = ColorSpace.sRGB,
        positions = arrayOf(0.05f, 0.2f, 0.40f, 0.50f, 0.60f, 0.8f, 0.95f).toFloatArray(),
        style = GradientStyle.DEFAULT
      )
    })
    // 明黄色背景
    canvas.drawRect(srColorBgRect, Paint().apply {
      mode = PaintMode.FILL
      color4f = srColor
      maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 5f)
    })
    canvas.save()
    // 星星背景
    canvas.clipRRect(borderRect, ClipMode.INTERSECT, true)
    canvas.drawRRect(
      starRect,
      starBgPaint
    )
    canvas.drawRRect(
      starRect,
      starBgBorderPaint
    )
    // 头
    canvas.resetMatrix()
    canvas.drawImageRect(head, headRect)
    // 边框
    canvas.drawPath(Path().apply {
      addRRect(borderRect)
    }, Paint().apply {
      strokeWidth = 5f
      color4f = Color4f.new(255, 203, 13)
      mode = PaintMode.STROKE
      strokeCap = PaintStrokeCap.ROUND
      imageFilter = ImageFilter.makeBlur(10f, 10f, FilterTileMode.REPEAT)
    })
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    canvas.drawPath(Path().apply {
      addRRect(borderRect)
    }, Paint().apply {
      strokeWidth = 7f
      color = Color.WHITE
      mode = PaintMode.STROKE
    })
    // 星星
    canvas.resetMatrix()
    (0..1).forEach { index ->
      val left = (230f - 75) / 2 + (35 + 5) * index + 40
      val top = 215f + (55 - 33.5f) / 2 + 78
      canvas.drawImageRect(
        star, Rect(
          left, top, left + 35f, top + 33.5f
        )
      )
    }
    // 彩色三角形
    val trList = genTriangle(scaleRange = (120..180), color = srColor)
    trList.forEachIndexed { idx, tr ->
      val x = (315 - 85) / trList.size * idx
      val y = (335 - tr.height..430 - tr.height).random()
      canvas.drawImage(tr, x.toFloat(), y.toFloat(), Paint().apply {
        alpha = 120
      })
    }
    // 随机飘动的三角形
    canvas.restore()
    genTriangle(numRange = (5..10)).forEach { tr ->
      val x = (25..360 - tr.width).random()
      val y = if ((0..1).random() > 0 && tr.height < 80) {
        (20..100 - tr.height).random()
      } else if (270 < 400 - tr.height) {
        (270..400 - tr.height).random()
      } else {
        return@forEach
      }
      canvas.drawImage(tr, x.toFloat(), y.toFloat(), Paint().apply {
        alpha = 240
      })
    }
  }

  private fun drawSSR(surface: Surface, item: GachaResultItem) {
    val head = Image.fromGachaPath(item.image)
    val canvas = surface.canvas
    // 15°倾斜
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    // 半透明背景
    canvas.drawRect(srFilterRect, Paint().apply {
      shader = Shader.makeLinearGradient(
        Point(130f, 430f),
        Point(190f, 0f),
        colors = arrayOf(
          Color4f.new(211, 225, 250, 51),
          Color4f.new(245, 228, 252, 166),
          Color4f.new(250, 250, 250, 230),
          Color4f.new(255, 255, 255, 255),
          Color4f.new(250, 250, 250, 230),
          Color4f.new(245, 228, 252, 166),
          Color4f.new(211, 225, 250, 51),
        ),
        cs = ColorSpace.sRGB,
        positions = arrayOf(0.05f, 0.2f, 0.4f, 0.5f, 0.6f, 0.8f, 0.95f).toFloatArray(),
        style = GradientStyle.DEFAULT
      )
    })
    // 紫色背景
    canvas.drawRect(srColorBgRect, Paint().apply {
      mode = PaintMode.FILL
      color4f = ssrColor
      maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 5f)
      imageFilter = ImageFilter.makeDropShadow(0f, 5f, 0f, 5f, Color4f.new(210, 174, 241).toColor())
    })
    canvas.save()
    // 星星背景
    canvas.clipRRect(borderRect, ClipMode.INTERSECT, true)
    canvas.drawRRect(
      starRect,
      starBgPaint
    )
    canvas.drawRRect(
      starRect,
      starBgBorderPaint
    )
    // 头
    canvas.resetMatrix()
    canvas.drawImageRect(head, headRect)
    // 边框
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    canvas.drawPath(Path().apply {
      addRRect(borderRect)
    }, Paint().apply {
      strokeWidth = 7f
      color = Color.WHITE
      mode = PaintMode.STROKE
    })
    canvas.drawPath(Path().apply {
      addRRect(borderRect)
    }, Paint().apply {
      strokeWidth = 5f
      color4f = ssrColor
      mode = PaintMode.STROKE
      strokeCap = PaintStrokeCap.ROUND
      imageFilter = ImageFilter.makeBlur(10f, 10f, FilterTileMode.REPEAT)
    })
    // 星星
    canvas.resetMatrix()
    (0..2).forEach { index ->
      val left = (230f - 115) / 2 + (35 + 5) * index + 40
      val top = 215f + (55 - 33.5f) / 2 + 78
      canvas.drawImageRect(
        star, Rect(
          left, top, left + 35f, top + 33.5f
        )
      )
    }
    // 彩色三角形
    val trList = genTriangle(scaleRange = (120..180), color = ssrColor)
    trList.forEachIndexed { idx, tr ->
      val x = (315 - 85) / trList.size * idx
      val y = (335 - tr.height..430 - tr.height).random()
      canvas.drawImage(tr, x.toFloat(), y.toFloat(), Paint().apply {
        alpha = 120
      })
    }
    // 随机飘动的三角形
    canvas.restore()
    genTriangle(numRange = (5..10)).forEach { tr ->
      val x = (25..360 - tr.width).random()
      val y = if ((0..1).random() > 0 && tr.height < 80) {
        (20..100 - tr.height).random()
      } else if (270 < 400 - tr.height) {
        (270..400 - tr.height).random()
      } else {
        return@forEach
      }
      canvas.drawImage(tr, x.toFloat(), y.toFloat(), Paint().apply {
        alpha = 240
      })
    }
    // 星星
    canvas.setMatrix(Matrix33.makeSkew(-0.15f, 0f))
    val starPoint = randomReactPoint(Rect(80f, 55f, 310f, 325f).toIRect())
    canvas.drawImageRect(kira, Rect.makeXYWH(starPoint.x.toFloat(), starPoint.y.toFloat(), 20f, 45f), Paint().apply {
      maskFilter = MaskFilter.makeBlur(FilterBlurMode.SOLID, 3f)
    })
    // new pickup
    canvas.resetMatrix()
  }

  private fun Image.Companion.fromGachaPath(path: String) = makeFromEncoded(
    Files.readAllBytes(Arona.dataFolderPath.resolve("gacha").resolve(path))
  )

  private fun Color4f.Companion.new(r: Int, g: Int, b: Int, a: Int = 255): Color4f = Color4f(
    r / 255f, g / 255f, b / 255f, a /
        255f
  )

  /**
   * 生成装饰三角形
   */
  private fun genTriangle(
    numRange: IntRange = (7..10),
    scaleRange: IntRange = (20..80),
    color: Color4f = Color4f.new(255, 255, 255),
    hl: Boolean = true,
  ): List<Image> {
    val triangle = Surface.makeRasterN32Premul(42, 42).apply {
      canvas.drawPath(Path().apply {
        addPoly(arrayOf(Point(0f, 0f), Point(0f, 42f), Point(42f, 42f)), true)
      }, Paint().apply {
        color4f = color
        mode = PaintMode.FILL
      })
    }
    return numRange.map {
      val scale = scaleRange.random() / 100f
      val rotation = (0..360).random()
      val w = ((60.83f * scale) * 2) + 10
      val surface = Surface.makeRasterN32Premul(w.toInt(), w.toInt())
      val canvas = surface.canvas
      val matrix = Matrix33.makeScale(scale) * Matrix33.makeRotate(rotation.toFloat(), w / 2f, w / 2f)
      canvas.setMatrix(matrix)
      canvas.drawImage(triangle.makeImageSnapshot(), w / 2f, w / 2f, Paint().apply {
        if (hl) {
          maskFilter = MaskFilter.makeBlur(FilterBlurMode.SOLID, 5f)
        }
      })
      surface.makeImageSnapshot(getNotTransparentBounds(surface))!!
    }
  }

  operator fun Matrix33.times(other: Matrix33) = this.makeConcat(other)

  private fun getNotTransparentBounds(im: Surface): IRect {
    val bitmap = Bitmap.makeFromImage(im.makeImageSnapshot())
    var left = bitmap.width
    var top = bitmap.height
    var right = 0
    var bottom = 0
    (0 until bitmap.width).forEach { x ->
      (0 until bitmap.height).forEach { y ->
        if (bitmap.getAlphaf(x, y) > 0) {
          left = min(left, x)
          top = min(top, y)
          right = max(right, x)
          bottom = max(bottom, y)
        }
      }
    }
    return IRect.makeLTRB(
      max(0, left - 15), max(0, top - 15), min(bitmap.width, right + 15), min(
        bitmap.height, bottom
            + 15
      )
    )
  }

  private fun randomReactPoint(react: IRect): IPoint {
    return when ((0..3).random()) {
      0 -> IPoint(react.left + (0..react.width).random(), react.top)
      1 -> IPoint(react.left + (0..react.width).random(), react.bottom)
      2 -> IPoint(react.left, react.top + (0..react.height).random())
      else -> IPoint(react.right, react.top + (0..react.height).random())
    }
  }

  private fun Font.measureTextWidthEx(text: String, paint: Paint? = null): Float {
    val react = measureText(text, paint)
    return react.width
  }
}
