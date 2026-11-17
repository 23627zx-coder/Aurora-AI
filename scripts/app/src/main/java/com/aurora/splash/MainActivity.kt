package com.aurora.splash

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    private var isUiReady = false
    private val splashController = AuroraSplashController()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前挂载开屏控制器
        splashController.attach(
            activity = this,
            isMainContentReady = { isUiReady }
        )
        super.onCreate(savedInstanceState)

        setContentView(TextView(this).apply {
            text = "Aurora Main Screen"
            textSize = 22f
        })

        // 模拟主界面首帧数据就绪
        window.decorView.post { isUiReady = true }
    }
}
