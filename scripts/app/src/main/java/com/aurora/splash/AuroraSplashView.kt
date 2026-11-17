package com.aurora.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.FastOutSlowInInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.*

/**
 * Aurora 冷启动开屏自定义渲染视图：
 * 1. 高分辨率 WebP 丝带 + PathMeasure 沿路径矢量遮罩揭示
 * 2. 中心绿色光点淡入与光晕 1 -> 2.2 -> 1 脉冲
 * 3. 整体 0.95 -> 1.0 呼吸缩放 + 文字 12dp 淡入上移
 * 4. -35° 斜向擦除揭示底层主界面
 */
class AuroraSplashView(
    context: Context,
    private val config: AuroraSplashConfig = AuroraSplashConfig()
) : View(context) {

    private val density = resources.displayMetrics.density
    private val easeOutIn = FastOutSlowInInterpolator()
    private val wipeInterpolator = AccelerateDecelerateInterpolator()

    // 丝带高分辨率透明 WebP 位图
    private val ribbonLeftBitmap: Bitmap = ContextCompat.getDrawable(context, config.ribbonLeftRes)!!
        .toBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
    private val ribbonRightBitmap: Bitmap = ContextCompat.getDrawable(context, config.ribbonRightRes)!!
        .toBitmap(1080, 1080, Bitmap.Config.ARGB_8888)

    // 文字 VectorDrawable
    private val wordmarkDrawable = ContextCompat.getDrawable(context, config.wordmarkRes)!!

    // 路径揭示遮罩（基于 aurora-mark.svg 900x900 空间中心线采样）
    private val leftSpineMeasure = PathMeasure(buildLeftSpinePath(), false)
    private val rightSpineMeasure = PathMeasure(buildRightSpinePath(), false)
    private val leftRevealSegment = Path()
    private val rightRevealSegment = Path()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val nodeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = config.nodeCoreColor
    }
    private val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wipeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    // 实时动效状态
    private var leftProgress = 0f
    private var rightProgress = 0f
    private var nodeAlpha = 0f
    private var nodeGlowScale = config.nodeGlowMinScale
    private var groupScale = config.initialScale
    private var wordmarkAlpha = 0f
    private var wordmarkOffsetY = config.wordmarkOffsetYDp * density
    private var exitWipeProgress = 0f

    private val markRect = RectF()
    private val nodeCoreRect = RectF()
    private var cachedGlowShaderRadius = -1f

    private var introAnimator: ValueAnimator? = null
    private var exitAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** 启动入场时间线（0–1100ms），完成后自动触发 -35° 斜向擦除退出（450ms） */
    fun startTimeline(onComplete: () -> Unit) {
        introAnimator?.cancel()
        exitAnimator?.cancel()

        val introMs = config.introTotalDurationMs
        introAnimator = ValueAnimator.ofFloat(0f, introMs.toFloat()).apply {
            duration = introMs
            interpolator = null // 线性驱动主时钟，各轨道内部独立插值
            addUpdateListener { anim ->
                updateTimelineState(anim.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startExitWipe(onComplete)
                }
            })
            start()
        }
    }

    private fun startExitWipe(onComplete: () -> Unit) {
        exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = config.exitWipeDurationMs
            interpolator = wipeInterpolator
            addUpdateListener { anim ->
                exitWipeProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })
            start()
        }
    }

    private fun updateTimelineState(tMs: Float) {
        // 1. 0–400ms：左丝带沿路径从左下向中心揭示
        val leftRaw = ((tMs - config.leftRibbonStartMs) / config.leftRibbonDurationMs).coerceIn(0f, 1f)
        leftProgress = easeOutIn.getInterpolation(leftRaw)

        // 2. 300–700ms：右丝带从中心向右上揭示
        val rightRaw = ((tMs - config.rightRibbonStartMs) / config.rightRibbonDurationMs).coerceIn(0f, 1f)
        rightProgress = easeOutIn.getInterpolation(rightRaw)

        // 3. 650–900ms：光点淡入，光晕脉冲一次（1 -> 2.2 -> 1）
        val nodeRaw = ((tMs - config.nodeStartMs) / config.nodeDurationMs).coerceIn(0f, 1f)
        nodeAlpha = easeOutIn.getInterpolation((nodeRaw / 0.55f).coerceIn(0f, 1f))
        val peak = config.nodeGlowPeakFraction
        nodeGlowScale = when {
            nodeRaw <= 0f || nodeRaw >= 1f -> config.nodeGlowMinScale
            nodeRaw < peak -> {
                val p = easeOutIn.getInterpolation(nodeRaw / peak)
                lerp(config.nodeGlowMinScale, config.nodeGlowPeakScale, p)
            }
            else -> {
                val p = easeOutIn.getInterpolation((nodeRaw - peak) / (1f - peak))
                lerp(config.nodeGlowPeakScale, config.nodeGlowMinScale, p)
            }
        }

        // 4. 800–1100ms：整体缩放 0.95 -> 1.0，文字从下方 12dp 淡入上移
        val swRaw = ((tMs - config.scaleWordmarkStartMs) / config.scaleWordmarkDurationMs).coerceIn(0f, 1f)
        val swEase = easeOutIn.getInterpolation(swRaw)
        groupScale = lerp(config.initialScale, config.targetScale, swEase)
        wordmarkAlpha = swEase
        wordmarkOffsetY = (1f - swEase) * config.wordmarkOffsetYDp * density
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 退出阶段使用离屏图层执行 -35° 斜向擦除，直接露出下方 MainActivity 视图
        val wipeLayer = if (exitWipeProgress > 0f) canvas.saveLayer(0f, 0f, w, h, null) else -1

        // 黑色背景
        canvas.drawColor(config.backgroundColor)

        // 计算 900x900 丝带基准区域（屏幕居中，与系统 SplashScreen 静态图中心完全一致）
        val cx = w * 0.5f
        val cy = h * 0.5f
        val markSize = config.markBaseSizeDp * density
        val unit = markSize / 900f // SVG 1 单位对应的屏幕像素

        markRect.set(
            cx - markSize * 0.5f,
            cy - markSize * 0.5f,
            cx + markSize * 0.5f,
            cy + markSize * 0.5f
        )

        // 整体缩放变换（围绕屏幕中心 0.95 -> 1.0）
        canvas.save()
        canvas.scale(groupScale, groupScale, cx, cy)

        // 1. 绘制左丝带 + 路径揭示遮罩
        if (leftProgress > 0f) {
            drawMaskedRibbon(canvas, ribbonLeftBitmap, leftSpineMeasure, leftRevealSegment, leftProgress, unit)
        }

        // 2. 绘制右丝带 + 路径揭示遮罩
        if (rightProgress > 0f) {
            drawMaskedRibbon(canvas, ribbonRightBitmap, rightSpineMeasure, rightRevealSegment, rightProgress, unit)
        }

        // 3. 绘制中心绿色光点与脉冲光晕（SVG 坐标 cx=850, cy=400 -> 900画布内 (518, 446)）
        if (nodeAlpha > 0f) {
            val nodeCx = markRect.left + 518f * unit
            val nodeCy = markRect.top + 446f * unit
            drawCenterNode(canvas, nodeCx, nodeCy, unit)
        }

        // 4. 绘制文字（SVG 坐标 x=301..1235, y=689..766 -> 相对 900 画布精准对齐）
        if (wordmarkAlpha > 0f) {
            val wmLeft = markRect.left + (301f - 332f) * unit
            val wmTop = markRect.top + (689f - (-46f)) * unit + wordmarkOffsetY
            val wmRight = wmLeft + 934f * unit
            val wmBottom = wmTop + 77f * unit
            wordmarkDrawable.setBounds(
                wmLeft.roundToInt(),
                wmTop.roundToInt(),
                wmRight.roundToInt(),
                wmBottom.roundToInt()
            )
            wordmarkDrawable.alpha = (wordmarkAlpha * 255f).roundToInt().coerceIn(0, 255)
            wordmarkDrawable.draw(canvas)
        }

        canvas.restore()

        // 应用 -35° 斜向擦除遮罩
        if (wipeLayer != -1) {
            drawDiagonalExitWipe(canvas, w, h, exitWipeProgress)
            canvas.restoreToCount(wipeLayer)
        }
    }

    private fun drawMaskedRibbon(
        canvas: Canvas,
        bitmap: Bitmap,
        measure: PathMeasure,
        dstPath: Path,
        progress: Float,
        unit: Float
    ) {
        val layer = canvas.saveLayer(markRect, null)
        canvas.drawBitmap(bitmap, null, markRect, bitmapPaint)

        dstPath.reset()
        measure.getSegment(0f, measure.length * progress, dstPath, true)

        canvas.save()
        canvas.translate(markRect.left, markRect.top)
        canvas.scale(unit, unit)
        maskPaint.strokeWidth = 165f // 覆盖丝带最大宽度，保留 WebP 自身抗锯齿边缘
        canvas.drawPath(dstPath, maskPaint)
        canvas.restore()

        canvas.restoreToCount(layer)
    }

    private fun drawCenterNode(canvas: Canvas, nodeCx: Float, nodeCy: Float, unit: Float) {
        val alphaInt = (nodeAlpha * 255f).roundToInt().coerceIn(0, 255)
        val rx = 88f * unit
        val ry = 40f * unit

        if (abs(cachedGlowShaderRadius - rx) > 0.5f) {
            cachedGlowShaderRadius = rx
            val baseRgb = config.nodeGlowColor and 0x00FFFFFF
            nodeGlowPaint.shader = RadialGradient(
                0f, 0f, rx,
                intArrayOf(
                    (0xA8 shl 24) or baseRgb,
                    (0x94 shl 24) or baseRgb,
                    (0x52 shl 24) or baseRgb,
                    (0x36 shl 24) or baseRgb,
                    (0x1C shl 24) or baseRgb,
                    (0x0A shl 24) or baseRgb,
                    0x00000000
                ),
                floatArrayOf(0f, 0.20f, 0.42f, 0.53f, 0.74f, 0.95f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }

        // 光晕椭圆（脉冲缩放 1 -> 2.2 -> 1）
        canvas.save()
        canvas.translate(nodeCx, nodeCy)
        canvas.rotate(config.nodeRotationDeg)
        canvas.scale(nodeGlowScale, nodeGlowScale)
        canvas.scale(1f, ry / rx) // 将正圆径向渐变压缩为 rx:ry=88:40 椭圆
        nodeGlowPaint.alpha = alphaInt
        canvas.drawCircle(0f, 0f, rx, nodeGlowPaint)
        canvas.restore()

        // 白色高亮核心胶囊（62x12, rx=6）
        canvas.save()
        canvas.translate(nodeCx, nodeCy)
        canvas.rotate(config.nodeRotationDeg)
        val halfW = 31f * unit
        val halfH = 6f * unit
        nodeCoreRect.set(-halfW, -halfH, halfW, halfH)
        nodeCorePaint.alpha = alphaInt
        canvas.drawRoundRect(nodeCoreRect, halfH, halfH, nodeCorePaint)
        canvas.restore()
    }

    private fun drawDiagonalExitWipe(canvas: Canvas, w: Float, h: Float, progress: Float) {
        // 擦除前沿沿 -35° 倾斜，法向量指向扫掠推进方向
        val rad = Math.toRadians(config.exitWipeAngleDeg.toDouble())
        val nx = (-sin(rad)).toFloat() // sin(35°)
        val ny = cos(rad).toFloat()    // cos(35°)

        val p0 = 0f
        val p1 = w * nx
        val p2 = h * ny
        val p3 = w * nx + h * ny
        val minProj = min(min(p0, p1), min(p2, p3))
        val maxProj = max(max(p0, p1), max(p2, p3))

        val feather = config.exitWipeFeatherDp * density
        val totalSpan = (maxProj - minProj) + feather * 2f
        val edgePos = (minProj - feather) + totalSpan * progress

        // 已擦除区域 alpha=0（露出主界面），未擦除区域 alpha=255（保留开屏画面）
        val startX = nx * (edgePos - feather * 0.5f)
        val startY = ny * (edgePos - feather * 0.5f)
        val endX = nx * (edgePos + feather * 0.5f)
        val endY = ny * (edgePos + feather * 0.5f)

        wipeMaskPaint.shader = LinearGradient(
            startX, startY, endX, endY,
            intArrayOf(Color.TRANSPARENT, Color.WHITE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, wipeMaskPaint)
    }

    /** 左丝带中心脊线（900x900 相对坐标 = SVG 坐标 - (332, -46)） */
    private fun buildLeftSpinePath(): Path = Path().apply {
        moveTo(395f - 332f, 625f + 46f)
        cubicTo(420f - 332f, 540f + 46f, 495f - 332f, 415f + 46f, 595f - 332f, 340f + 46f)
        cubicTo(665f - 332f, 288f + 46f, 720f - 332f, 300f + 46f, 812f - 332f, 412f + 46f)
    }

    /** 右丝带中心脊线（900x900 相对坐标 = SVG 坐标 - (332, -46)） */
    private fun buildRightSpinePath(): Path = Path().apply {
        moveTo(795f - 332f, 462f + 46f)
        cubicTo(860f - 332f, 535f + 46f, 925f - 332f, 555f + 46f, 995f - 332f, 465f + 46f)
        cubicTo(1060f - 332f, 375f + 46f, 1125f - 332f, 265f + 46f, 1180f - 332f, 172f + 46f)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    override fun onDetachedFromWindow() {
        introAnimator?.cancel()
        exitAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
