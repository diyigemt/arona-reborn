package com.diyigemt.kivotos.rank


import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.database.permission.SimplifiedUserDocument
import com.diyigemt.kivotos.schema.FavorLevelExcelTable
import org.jetbrains.skia.*
import java.nio.file.Files
import kotlin.io.path.readBytes


/**
 * 排行可视化工具
 */
object FavorRankTool {
        val RankResourcePath by lazy {
        Arona.dataFolder("gacha")
    }
    // 每个排名卡片的宽度
    private val rankWidth = 964

    // 每个排名卡片的高度
    private val rankHeight = 106

    //四周的margin
    private val cardMargin = 50f

    // 左侧个人信息的起始坐标
    private val selfStartX = 43f + cardMargin
    private val selfStartY = 73f + cardMargin
    private val selfWidth = 400f
    private val selfHeight = 800f  //完全体可以上950

    // 第一个排名卡片的起始X坐标
    private val rankStartX = 500f + cardMargin

    // 第一个排名卡片的起始Y坐标
    private val rankStartY = 23f + 50f  // [卡片] 23f | 50f |
    private val BgWidth = 1600
    private val BgHeight = 1200


    //默认MiSans

    private val rankFont = Font().apply {
        size = 40f
        setTypeface(MiSansMedium)

    }
    private val selfLargeRank = Font().apply {
        size = 62f
        setTypeface(MiSansBold)
        isEmboldened = true
    }
    private val selfNameFont = Font().apply {
        size = 36f
        setTypeface(MiSansBold)
        isEmboldened = true
    }
    private val userNameFont = Font().apply {
        size = 32f
        setTypeface(MiSansBold)
        isEmboldened = true
    }
    private val userNameFontsmall = Font().apply {
        size = 28f
        setTypeface(MiSansBold)
        isEmboldened = true
    }
    //通用paint  统一全局黑白
    private val whitePaint = Paint().apply {
        color = Color.makeRGB(250,250,250)
    }
    private val blackPaint = Paint().apply {
        color = Color.makeRGB(61,61,61)
    }
    private val MiSansDemibold: Typeface = Typeface
        .fromRankPath("MiSans-Demibold.ttf")
    private val MiSansBold: Typeface = Typeface
        .fromRankPath("MiSans-Bold.ttf")
    private var MiSansMedium: Typeface = Typeface
        .fromRankPath("MiSans-Medium.ttf")
    private val favorHeart = Image.fromRankPath("heart.png")
    private val bg = Image.fromRankPath("Rank_BG.jpg")
    private val stuNameFont = Font().apply {
        size = 20f
        setTypeface(MiSansDemibold)
    }
    private val NumberFont = Font().apply {
        size = 12f
        setTypeface(MiSansMedium)
    }
    // TODO 必须检测并测试的路径
    private fun Image.Companion.fromRankPath(path: String) = makeFromEncoded(
        Files.readAllBytes(RankResourcePath.resolve(path))

//        this::class.java.getResourceAsStream("/$path")?.readAllBytes()
    )

    private fun Typeface.Companion.fromRankPath(path: String) = makeFromData(
        Data.makeFromBytes(
            Arona.dataFolder("gacha", path).readBytes()
//            this::class.java.getResourceAsStream("/$path")!!.readAllBytes()
        )
    )

    /**
     * 榜单可视化 Skia部分
     * @param ranks 排行榜
     * @param self 我的排名
     * @param showSelfPosition 是否【我的】
     * @return 可视化结果 Surface
     */
    suspend fun generateFavorRankImage(
        ranks: List<FavorRankData>,
        self: FavorRankData? = null,
        showSelfPosition: Boolean = true,
        //缓存用户id,name键值
        userMap: Map<String, SimplifiedUserDocument>,
        //缓存学生id,name键值
        studentNames: Map<Int?, StudentSchema?>,
    ): Surface {

        val surface = Surface.makeRasterN32Premul(BgWidth, BgHeight)
        val canvas = surface.canvas
        createBG().also {
            canvas.drawImage(it.makeImageSnapshot(), 0f, 0f)
        }

        val currentX = rankStartX
        var currentY = rankStartY

        ranks.forEachIndexed { index, rank ->
            val rankUname = userMap[rank.uid]?.username
            val rankSname = studentNames[rank.sid]?.name
            val rankSImage = studentNames[rank.sid]?.headFileName
            val rankPaint = Paint().apply {
                color = if (index % 2 == 1) Color.makeRGB(239, 239, 239)
                else Color.makeRGB(250, 250, 250)
            }
            //排名列表
            canvas.drawImage(
                createRank(
                    rankPaint,
                    rank,
                    rankUname,
                    rankSname,
                    rankSImage,
                ).makeImageSnapshot(), currentX,
                currentY
            )
            currentY += rankHeight
        }
        if(showSelfPosition){
            //渲染自己的资料卡
            // feat::todo基于showSelfPosition渲染不同样式的排行图
            self?.let {
                val selfUname = userMap[it.uid]?.username
                val selfSname = studentNames[it.sid]?.name
                val selfSImage = studentNames[it.sid]?.headFileName
                canvas.drawImage(
                    createSelfRank(
                        Paint().apply {
                            color = Color.makeRGB(250, 250, 250)
                        },
                        it,
                        selfUname,
                        selfSname,
                        selfSImage,
                    ).makeImageSnapshot(),
                    selfStartX,
                    selfStartY
                )
            }
        }
        return surface
    }

    /**
     * 渲染背景图
     */
    private fun createBG(): Surface {

        val surface = Surface.makeRasterN32Premul(BgWidth, BgHeight)
        val canvas = surface.canvas
        canvas.drawImageRect(
            bg,
            Rect(0f, 0f, (surface.width).toFloat(), (surface.height).toFloat())
        )
        canvas.drawRRect(
            RRect.makeXYWH(
                cardMargin,
                cardMargin,
                (surface.width).toFloat() - 100,
                (surface.height).toFloat() - 100,
                18f
            ),
            Paint().apply {
                color = Color.makeARGB(114, 255, 255, 255)
            }

        )

        return surface
    }

    /**
     * 渲染排名
     */
    private suspend fun createRank(
        rankPaint: Paint,
        rank: FavorRankData,
        rankUname: String?,
        rankSname: String?,
        rankSImage: String?,
    ): Surface {
        val generate = Surface.makeRasterN32Premul(rankWidth, rankHeight)
        val canvas = generate.canvas
        canvas.drawRect(
            Rect.makeXYWH(0f, 0f, rankWidth.toFloat(), rankHeight.toFloat()),
            rankPaint
        )
        val rankName = "第${rank.rank}名"
        // 名次
        canvas.drawTextLine(
            TextLine.make(rankName, rankFont),
            35f,
            20f + (rankHeight / 2).toFloat(),
            Paint().apply {
                //名次使用纯黑
                color= Color.BLACK
            }

        )

        //feat::todo获取不到就渲染一个默认头像
        rankSImage?.let {
            val img1 = Image.fromRankPath(it)
            val headImage = doScale(img1, 1f, 100, 2.22f)
            canvas.drawImageRect(
                headImage,
                // 图片缩放倍率为2.22倍  (252f,200f) -> (112f,90f)
                Rect.makeXYWH(184f, 7.5f, 112f, 90f)
            )

        }

        rankUname?.let {
            var textLine = TextLine.make(it, userNameFont)
            if (textLine.width > rankWidth - 310f - 200f) {
                textLine = TextLine.make(it, userNameFontsmall)
                canvas.drawTextLine(
                    textLine,
                    310f,
                    70f,
                    blackPaint
                )
            } else {
                canvas.drawTextLine(
                    textLine,
                    310f,
                    70f,
                    blackPaint
                )
            }

        }
        canvas.drawImage(
            createStuCard(
                rankSname,
                rank.favor
            ).makeImageSnapshot(),
            rankWidth - 250f,
            0f
        )
        //获取等级对
//        val favor = FavorLevelExcelTable.findLevel(current)


        return generate
    }


    private suspend fun createStuCard(
        stuName: String?,
        pair: Pair<Int, Int>
    ): Surface {
        val generate = Surface.makeRasterN32Premul(250, rankHeight + 2)
        val canvas = generate.canvas
        val linePaint = Paint().apply {
            mode = PaintMode.FILL
            color = Color.makeRGB(216, 229, 235)
        }
        val favCard = drawFavCard(pair)
        canvas.drawImage(
            favCard,
            120f,
            45f
        )
        // 绘制名字
        stuName?.let {
            val stuNameImage = buildRRect(
                font = stuNameFont,
                info = it,
                textPaint = blackPaint,
                backgroundPaint = linePaint
            )

            canvas.drawImage(
                stuNameImage,
                240f - stuNameImage.width ,
                5f,
            )
        }
        return generate
    }

    private suspend fun drawFavCard(
        pair: Pair<Int, Int>,
    ): Image {
        val generate = Surface.makeRasterN32Premul(135, 60)
        val canvas = generate.canvas
        val favRectY = 15f
        val favRectWidth = 100f
        //绘制好感度条
        canvas.drawRRect(
            RRect.makeXYWH(
                20f, favRectY, favRectWidth, 20f, 18f
            ),
            paint = Paint().apply {
                color = Color.makeRGB(247, 231, 241)
            }
        )
        val next = FavorLevelExcelTable.findLevel2Next(pair.first).next
        canvas.drawRect(
            Rect.makeXYWH(
                20f,
                favRectY,
                //使用min防止脏数据导致的渲染溢出
                (pair.second / next * favRectWidth).coerceAtMost(favRectWidth),
                20f
            ),
            paint = Paint().apply {
                color = Color.makeRGB(246, 200, 228)
            }
        )

        canvas.drawRRect(
            RRect.makeXYWH(
                20f, favRectY, favRectWidth, 20f, 18f
            ),
            paint = Paint().apply {
                color = Color.makeRGB(255, 162, 218)
                mode = PaintMode.STROKE
                isAntiAlias = true
                strokeWidth = 1f
            }
        )
        val pairSecond = "${pair.second}/${next.toInt()}"
        val favorPairLine = TextLine.make(pairSecond, NumberFont)
        canvas.drawTextLine(
            favorPairLine,
            30f+ favRectWidth/2 - favorPairLine.width / 2,
            favRectY+15f,
            paint = Paint()
        )
        // 爱心
        canvas.drawImageRect(
            favorHeart,
            Rect.makeXYWH(
                0f, 0f, 55f, 55f
            )
        )
        // 绘制好感度
        val stringPaint = Paint().apply {
            color = Color.makeRGB(50, 50, 50)
        }
        val favorTextLine = TextLine.make(pair.first.toString(), stuNameFont)
        canvas.drawTextLine(
            favorTextLine,
            27.5f - favorTextLine.width / 2,
            32f,
            paint = stringPaint
        )
        return generate.makeImageSnapshot()
    }


    /**
     * 渲染自己的排名
     */
    private suspend fun createSelfRank(
        rankPaint: Paint,
        rank: FavorRankData,
        rankUname: String?,
        rankSname: String?,
        rankSImage: String?,
    ): Surface {
        val generate = Surface.makeRasterN32Premul(selfWidth.toInt(), selfHeight.toInt())
        val canvas = generate.canvas
        canvas.drawRRect(
            RRect.makeXYWH(0f, 0f, selfWidth, selfHeight, 18f),
            rankPaint
        )

        //我的信息
        val selfPaint = Paint().apply {
            color = Color.makeRGB(66,76,112)
        }
        val selfButton = buildDetailRRect(
            userNameFont,
            "我的信息",
            selfPaint,
            whitePaint
        )
        //——————h:25[我的信息]——————
        canvas.drawImage(selfButton,
            40f,
            25f)
        val selfName = TextLine.make(rankUname, selfNameFont)
        //——————h:165[名字]——————
        canvas.drawTextLine(
            selfName,
            selfWidth /2-selfName.width/2,
            165f,
            blackPaint
        )
        //——————h:230[目前名次]——————
        val rankPaint = Paint().apply {
            color = Color.makeRGB(109,121,141)
        }
        val rankButton = buildDetailRRect(
            userNameFont,
            "目前名次",
            rankPaint,
            whitePaint
        )
        canvas.drawImage(rankButton,40f,230f)
        //——————h:290[学生头像]——————
        rankSImage?.let {
            val img1 = Image.fromRankPath(it)
            canvas.drawImageRect(
                img1,
                Rect.makeXYWH(selfWidth /2-126f, 320f, 252f, 204f)
            )
        }
        //——————h:500[学生名称]——————

        val selfStuName = TextLine.make(rankSname, selfNameFont)
        canvas.drawTextLine(
            selfStuName,
            selfWidth /2-selfStuName.width/2,
            580f,
            //复用一下最上面Button的颜色
            selfPaint
        )

        //——————[我的好感度条]——————
        val fav = drawFavCard(rank.favor)
        canvas.drawImage(
            fav,
            selfWidth /2-fav.width/2,
            600f
        )
        val selfRank = TextLine.make(
            "第${rank.rank}名",
            selfLargeRank
        )
        canvas.drawTextLine(
            selfRank,
            selfWidth /2-selfRank.width/2,
            730f,
            Paint().apply {
                //名次使用纯黑
                color= Color.BLACK
            }
        )
        // TODO 历史最高名次


        return generate
    }
    /**
     * 构建圆角矩形包裹的,SelfDetail （文字）
     *
     * @param font 渲染的字体
     * @param info 内容
     * @param backgroundPaint 背景Paint
     * @param textPaint 文字Paint
     */
    private fun buildDetailRRect(font: Font, info: String, backgroundPaint: Paint, textPaint: Paint): Image {
        val surfaceBitmap2 = Surface.makeRasterN32Premul(335, 65)

        val canvas3 = surfaceBitmap2.canvas
        canvas3.drawRRect(
            RRect.makeXYWH(7f, 7f, 320f, 57f, 18f),
            backgroundPaint
        )
        val text = TextLine.make(info,font)
        val textX = (320f+7f)/2-text.width/2
        val textY = (54)/2f+text.height/2
        canvas3.drawTextLine(
            text,
            textX,
            textY,
            textPaint
        )
        return surfaceBitmap2.makeImageSnapshot()
    }

    /**
     * 构建圆角矩形包裹的 学生（文字）
     * @param font 渲染的字体
     * @param info 内容
     * @param backgroundPaint 背景Paint
     * @param textPaint 文字Paint
     */
    private fun buildRRect(font: Font, info: String, backgroundPaint: Paint, textPaint: Paint): Image {
        val textLine = TextLine.make(info,font)
        val fontWidth = textLine.width
        val fontHeight = textLine.height
        val surfaceBitmap2 = Surface.makeRasterN32Premul(fontWidth.toInt() + 15, fontHeight.toInt()+15 )

        val canvas3 = surfaceBitmap2.canvas
        val rect = RRect.makeXYWH(2f, 2f, fontWidth + 10f, fontHeight + 10, 10f)
        canvas3.drawRRect(rect, backgroundPaint)
        canvas3.drawTextLine(
            textLine,
            surfaceBitmap2.width/2-textLine.width/2+2, 26.5f, textPaint)


        return surfaceBitmap2.makeImageSnapshot()

    }

    /**
     * 循环放缩
     * @param quality 质量  介于1-2 之间。数字越小循环次数越多质量越好
     *
     * （对90x90的图来说其实没什么区别）
     */
    private fun doScale(image: Image, quality: Float, width: Int, rate: Float): Image {
        var resultImage = image

        while (resultImage.width.coerceAtMost(resultImage.height) > width * quality) {
            resultImage = scaleR(resultImage, resultImage.width / rate, resultImage.height / rate)
        }
        return resultImage
    }

    /**
     * 缩放
     */
    private fun scaleR(image: Image, width: Float, height: Float): Image {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width.toInt() + 1, height.toInt() + 1)
        image.scalePixels(bitmap.peekPixels()!!, SamplingMode.MITCHELL, false)
        return Image.makeFromBitmap(bitmap)
    }

}
