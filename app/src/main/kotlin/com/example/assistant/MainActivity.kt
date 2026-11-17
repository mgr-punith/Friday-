package com.example.assistant

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.assistant.ui.AssistantOrbView
import com.example.assistant.ui.MicButtonView
import kotlin.math.min

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
    }

    private fun createContentView(): View {
        val screen = ScrollView(this).apply {
            isFillViewport = true
            background = verticalGradient(R.color.screen_background_top, R.color.screen_background)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val frame = FrameLayout(this).apply {
            minimumHeight = resources.displayMetrics.heightPixels
            setPadding(dp(24), dp(20), dp(24), dp(28))
        }
        val maxContentWidth = dp(440)
        val availableWidth = resources.displayMetrics.widthPixels - dp(48)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        frame.addView(
            content,
            FrameLayout.LayoutParams(min(maxContentWidth, availableWidth), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            },
        )
        screen.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        content.addView(createHeader(), linearParams(bottom = 12))

        val orb = AssistantOrbView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val orbStage = FrameLayout(this).apply {
            addView(
                orb,
                FrameLayout.LayoutParams(dp(252), dp(252)).apply {
                    gravity = Gravity.CENTER
                },
            )
        }
        content.addView(
            orbStage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(284)).apply {
                bottomMargin = dp(8)
            },
        )

        content.addView(
            textView(R.string.home_greeting, 27f, R.color.text_primary, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            },
            linearParams(bottom = 8),
        )
        content.addView(
            textView(R.string.home_subtitle, 16f, R.color.text_secondary, Typeface.NORMAL).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
            },
            linearParams(bottom = 20),
        )

        val status = textView(R.string.status_ready, 15f, R.color.text_primary, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val detail = textView(R.string.status_detail, 13f, R.color.text_secondary, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        val micButton = MicButtonView(this).apply {
            contentDescription = getString(R.string.mic_preview_start_description)
        }
        val micLabel = textView(R.string.tap_to_preview, 13f, R.color.text_secondary, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
        }

        var previewActive = false
        micButton.setOnClickListener {
            previewActive = !previewActive
            micButton.setActive(previewActive)
            if (previewActive) {
                orb.setState(AssistantOrbView.State.PREVIEW)
                status.setText(R.string.status_preview)
                detail.setText(R.string.status_preview_detail)
                micLabel.setText(R.string.tap_to_stop)
                micButton.contentDescription = getString(R.string.mic_preview_stop_description)
            } else {
                orb.setState(AssistantOrbView.State.IDLE)
                status.setText(R.string.status_ready)
                detail.setText(R.string.status_detail)
                micLabel.setText(R.string.tap_to_preview)
                micButton.contentDescription = getString(R.string.mic_preview_start_description)
            }
        }

        content.addView(
            micButton,
            LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                bottomMargin = dp(10)
            },
        )
        content.addView(micLabel, linearParams(bottom = 20))
        content.addView(status, linearParams(bottom = 6))
        content.addView(detail, linearParams(bottom = 24))
        content.addView(createWakePhraseCard(), linearParams(bottom = 8))

        // Keep initial keyboard focus at the top so ScrollView does not auto-scroll to the mic button.
        screen.isFocusableInTouchMode = true
        screen.requestFocus()

        return screen
    }

    private fun createHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)

        addView(
            textView(R.string.screen_title, 26f, R.color.text_primary, Typeface.BOLD),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            textView(R.string.preview_badge, 11f, R.color.accent_soft, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = roundedBackground(R.color.surface_accent, 18f, R.color.border_accent, 1)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun createWakePhraseCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = roundedBackground(R.color.surface, 20f, R.color.border, 1)

        addView(
            View(context).apply {
                background = roundedBackground(R.color.accent, 5f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(14)
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    textView(R.string.wake_phrase_title, 15f, R.color.text_primary, Typeface.BOLD),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(3)
                    },
                )
                addView(
                    textView(R.string.wake_phrase_detail, 13f, R.color.text_secondary, Typeface.NORMAL).apply {
                        setLineSpacing(0f, 1.1f)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun textView(textRes: Int, textSizeSp: Float, colorRes: Int, style: Int): TextView =
        TextView(this).apply {
            setText(textRes)
            textSize = textSizeSp
            setTextColor(getColor(colorRes))
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = false
        }

    private fun linearParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun roundedBackground(
        fillColorRes: Int,
        radiusDp: Float,
        strokeColorRes: Int? = null,
        strokeWidthDp: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(getColor(fillColorRes))
        if (strokeColorRes != null && strokeWidthDp > 0) {
            setStroke(dp(strokeWidthDp), getColor(strokeColorRes))
        }
    }

    private fun verticalGradient(topColorRes: Int, bottomColorRes: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(getColor(topColorRes), getColor(bottomColorRes)),
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
