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

        // 1. 绘制左丝带