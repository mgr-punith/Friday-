package com.example.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.assistant.ui.AppUi
import com.example.assistant.ui.AssistantOrbView
import com.example.assistant.ui.MicButtonView
import kotlin.math.min

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        if (!getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private fun createContentView(): View {
        val screen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.screen_background))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val frame = FrameLayout(this)
        val contentWidth = min(dp(440), resources.displayMetrics.widthPixels)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        frame.addView(
            content,
            FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            },
        )
        screen.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(AppUi.text(this, "Friday", 24f, bold = true), linearParams(bottom = 4))
        content.addView(
            AppUi.text(this, "Phone commands", 14f, secondary = true),
            linearParams(bottom = 20),
        )

        val orb = AssistantOrbView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val orbStage = FrameLayout(this).apply {
            addView(
                orb,
                FrameLayout.LayoutParams(dp(176), dp(176)).apply { gravity = Gravity.CENTER },
            )
        }
        content.addView(
            orbStage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply {
                bottomMargin = dp(8)
            },
        )

        val detail = AppUi.text(this, "Voice input is not connected.", 14f, secondary = true).apply {
            gravity = Gravity.CENTER
        }
        val micButton = MicButtonView(this).apply {
            contentDescription = "Start voice animation preview"
        }
        val micLabel = AppUi.text(this, "Preview orb", 15f, bold = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        var previewActive = false
        micButton.setOnClickListener {
            previewActive = !previewActive
            micButton.setActive(previewActive)
            orb.setState(if (previewActive) AssistantOrbView.State.PREVIEW else AssistantOrbView.State.IDLE)
            detail.text = if (previewActive) "No audio is recorded." else "Voice input is not connected."
            micLabel.text = if (previewActive) "Stop preview" else "Preview orb"
            micButton.contentDescription = if (previewActive) "Stop voice animation preview" else
                "Start voice animation preview"
        }

        content.addView(
            micButton,
            LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            },
        )
        content.addView(micLabel, linearParams(bottom = 4))
        content.addView(detail, linearParams(bottom = 24))

        content.addView(AppUi.sectionLabel(this, "Phone actions"), linearParams(bottom = 8))
        content.addView(
            AppUi.navigationButton(
                this,
                "Try a phone action",
                "Calls, alarms, apps and sound mode",
            ) { startActivity(Intent(this, ActionDebugActivity::class.java)) },
            linearParams(bottom = 8),
        )
        content.addView(
            AppUi.navigationButton(
                this,
                "Permissions",
                "Microphone, contacts, calls and sound",
            ) { startActivity(Intent(this, PermissionsActivity::class.java)) },
            linearParams(bottom = 20),
        )

        screen.isFocusableInTouchMode = true
        screen.requestFocus()
        return screen
    }

    private fun linearParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
