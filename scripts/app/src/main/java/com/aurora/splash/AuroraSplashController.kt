package com.aurora.splash

import android.app.Activity
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Aurora 冷启动开屏独立控制器：
 * 封装 Core SplashScreen 保活等待（最长 1.5s）与退出动效平滑接管
 */
class AuroraSplashController(
    private val config: AuroraSplashConfig = AuroraSplashConfig()
) {
    /**
     * 在 Activity.onCreate 中于 super.onCreate 之前调用
     * @param isMainContentReady 返回主界面首帧数据是否就绪，超时 1.5s 自动放行
     * @param onSplashFinished   开屏退出擦除完成回调
     */
    fun attach(
        activity: Activity,
        isMainContentReady: () -> Boolean,
        onSplashFinished: (() -> Unit)? = null
    ) {
        val splashScreen = activity.installSplashScreen()
        val attachUptimeMs = SystemClock.uptimeMillis()

        // 1. 等待主界面就绪，最长阻塞 1.5 秒
        splashScreen.setKeepOnScreenCondition {
            val elapsed = SystemClock.uptimeMillis() - attachUptimeMs
            !isMainContentReady() && elapsed < config.maxKeepOnScreenMs
        }

        // 2. 系统启动图退出瞬间无缝接管，在同一位置播放完整 Aurora 动效
        splashScreen.setOnExitAnimationListener { splashProvider ->
            val decorView = activity.window.decorView as ViewGroup
            val splashView = AuroraSplashView(activity, config).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // 挂载自定义动效层并立即移除系统静态 Splash 视图（首帧 1:1 重合无闪烁）
            decorView.addView(splashView)
            splashProvider.remove()

            splashView.startTimeline {
                decorView.removeView(splashView)
                onSplashFinished?.invoke()
            }
        }
    }
}
