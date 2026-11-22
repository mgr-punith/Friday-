package com.puneet.friday

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
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
            setDimAmount(0.14f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            val gutter = AppUi.dp(this@AssistantActivity, 12)
            val width = minOf(resources.displayMetrics.widthPixels - 2 * gutter, AppUi.dp(this@AssistantActivity, 440))
            attributes = attributes.apply { y = gutter }
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setFinishOnTouchOutside(true)
    }
}
