package com.puneet.friday

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.puneet.friday.ui.AppUi

/** A visible Activity keeps phone actions legal while the previous app remains underneath. */
class AssistantActivity : MainActivity() {
    override val compactSurface = true

    override fun onNewIntent(intent: Intent) {
        DebugTools.configureLockedWindow(this)
        super.onNewIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DebugTools.configureLockedWindow(this)
        super.onCreate(savedInstanceState)
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            val metrics = resources.displayMetrics
            val fontExtra = ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 80).toInt()
            setLayout(minOf(metrics.widthPixels, AppUi.dp(this@AssistantActivity, 520)),
                minOf(AppUi.dp(this@AssistantActivity, 520 + fontExtra), (metrics.heightPixels * 0.9f).toInt()))
        }
        setFinishOnTouchOutside(true)
    }
}
