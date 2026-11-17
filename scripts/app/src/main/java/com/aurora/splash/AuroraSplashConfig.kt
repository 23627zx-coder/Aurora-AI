package com.aurora.splash

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * Aurora 冷启动开屏动效统一配置对象（所有时长、缩放、角度、颜色集中管理）
 */
data class AuroraSplashConfig(
    // ===== 资源 ID =====
    @DrawableRes val ribbonLeftRes: Int = R.drawable.aurora_ribbon_left,
    @DrawableRes val ribbonRightRes: Int = R.drawable.aurora_ribbon_right,
    @DrawableRes val wordmarkRes: Int = R.drawable.ic_aurora_wordmark,

    // ===== 颜色配置 =====
    @ColorInt val backgroundColor: Int = 0xFF000000.toInt(),
    @ColorInt val nodeCoreColor: Int = 0xFFFEFFFE.toInt(),
    @ColorInt val nodeGlowColor: Int = 0xFF26F27C.toInt(),

    // ===== 时间线配置 (ms) =====
    val maxKeepOnScreenMs: Long = 1_500L,      // 系统开屏最长等待主界面就绪时间
    val leftRibbonStartMs: Long = 0L,          // 左丝带开始
    val leftRibbonDurationMs: Long = 400L,     // 0–400ms：左丝带沿路径左下->中心揭示
    val rightRibbonStartMs: Long = 300L,       // 右丝带开始
    val rightRibbonDurationMs: Long = 400L,    // 300–700ms：右丝带中心->右上揭示
    val nodeStartMs: Long = 650L,              // 光点开始
    val nodeDurationMs: Long = 250L,           // 650–900ms：光点淡入 + 光晕脉冲一次
    val scaleWordmarkStartMs: Long = 800L,     // 整体缩放与文字入场开始
    val scaleWordmarkDurationMs: Long = 300L,  // 800–1100ms：缩放 0.95->1 + 文字上移淡入
    val exitWipeDurationMs: Long = 450L,       // 退出：沿 -35° 斜向擦除露出主界面

    // ===== 缩放、角度与几何比例（严格对齐 aurora-mark.svg 900x900 坐标系） =====
    val initialScale: Float = 0.95f,
    val targetScale: Float = 1.0f,
    val nodeGlowMinScale: Float = 1.0f,
    val nodeGlowPeakScale: Float = 2.2f,
    val nodeGlowPeakFraction: Float = 0.45f,   // 光晕脉冲达到 2.2x 的归一化时刻
    val nodeRotationDeg: Float = -31f,
    val exitWipeAngleDeg: Float = -35f,
    val exitWipeFeatherDp: Float = 14f,        // 斜向擦除边缘羽化宽度
    val wordmarkOffsetYDp: Float = 12f,        // 文字入场初始向下偏移
    val markBaseSizeDp: Float = 186.37f        // markBaseSizeDp * 0.95 = 177.05dp，与系统启动图 1:1 像素重合
) {
    /** 入场主阶段总时长（1100ms） */
    val introTotalDurationMs: Long
        get() = scaleWordmarkStartMs + scaleWordmarkDurationMs
}
