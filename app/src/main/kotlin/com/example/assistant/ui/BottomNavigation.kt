package com.example.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.assistant.R

/** Rounded navigation capsule with one selected icon pill and two destinations. */
@SuppressLint("ViewConstructor") // Created from Kotlin with navigation callbacks.
class BottomNavigation(context: Context, settingsSelected: Boolean, onHome: () -> Unit, onSettings: () -> Unit) :
    FrameLayout(context) {
    private data class Tab(val view: View, val pill: View, val icon: ImageView, val label: android.widget.TextView)
    private val tabs = ArrayList<Tab>(2)
    init {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val capsule = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = AppUi.surface(context, 32, R.color.navigation_surface, null)
        }
        fun addTab(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
            val tab = object : LinearLayout(context) {
                override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
            }.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                isSelected = selected
                contentDescription = label
                background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.control_pressed)),
                    null, AppUi.surface(context, 24, android.R.color.white, null))
                setOnClickListener { onClick() }
            }
            val iconPill = FrameLayout(context).apply {
                if (selected) background = AppUi.surface(context, 16, R.color.navigation_selected, null)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(context.getColor(
                        if (selected) R.color.text_primary else R.color.text_secondary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(19), dp(19)).apply { gravity = Gravity.CENTER })
            }
            tab.addView(iconPill, LinearLayout.LayoutParams(dp(56), dp(30)))
            val labelView = AppUi.text(context, label, 11f, secondary = !selected).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tab.addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            tabs.add(Tab(tab, iconPill, iconPill.getChildAt(0) as ImageView, labelView))
            capsule.addView(tab, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        addTab("Home", R.drawable.ic_home_line, !settingsSelected, onHome)
        addTab("Settings", R.drawable.ic_settings_01, settingsSelected, onSettings)
        addView(capsule, LayoutParams(minOf(dp(300), resources.displayMetrics.widthPixels - dp(48)),
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
    }

    fun selectSettings(selected: Boolean) {
        tabs.forEachIndexed { index, tab ->
            val active = if (index == 1) selected else !selected
            tab.view.isSelected = active
            tab.pill.background = if (active) AppUi.surface(context, 16, R.color.navigation_selected, null) else null
            val color = context.getColor(if (active) R.color.text_primary else R.color.text_secondary)
            tab.icon.imageTintList = ColorStateList.valueOf(color)
            tab.label.setTextColor(color)
        }
    }

    private fun dp(value: Int): Int = AppUi.dp(context, value)
}
