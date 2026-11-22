package com.puneet.friday.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import com.puneet.friday.R

/** A setting is one accessible control, with its saved value beside its label. */
@SuppressLint("ViewConstructor")
class SettingsRow(
    context: Context,
    private val label: String,
    showChevron: Boolean = true,
    icon: Int? = null,
    onClick: (() -> Unit)? = null,
) : LinearLayout(context) {
    private val interactive = onClick != null
    private val value = AppUi.text(context, "", 13f, secondary = true).apply {
        gravity = Gravity.END
        maxWidth = minOf(AppUi.dp(context, 180), context.resources.displayMetrics.widthPixels * 2 / 5)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = AppUi.dp(context, 56)
        setPadding(AppUi.dp(context, 12), AppUi.dp(context, 12), AppUi.dp(context, 12), AppUi.dp(context, 12))
        if (icon != null) addView(ImageView(context).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(AppUi.dp(context, 20), AppUi.dp(context, 20)).apply { marginEnd = AppUi.dp(context, 12) })
        addView(AppUi.text(context, label, 14f).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = AppUi.dp(context, 12) })
        addView(value, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (showChevron) addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(AppUi.dp(context, 16), AppUi.dp(context, 16)).apply { marginStart = AppUi.dp(context, 4) })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (onClick != null) {
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.control_pressed)),
                ColorDrawable(android.graphics.Color.TRANSPARENT), null,
            )
            setOnClickListener { onClick() }
        }
        setValue("")
    }

    fun setValue(text: String) {
        if (value.text.toString() != text) value.text = text
        value.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        contentDescription = if (text.isBlank()) label else "$label. $text"
        if (Build.VERSION.SDK_INT >= 30) stateDescription = text.ifBlank { null }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = if (interactive) "android.widget.Button" else "android.widget.TextView"
    }
}

/** Shared rounded surface for navigation rows and the Start Friday choices. */
class SettingsGroup(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = AppUi.surface(context, 20, R.color.settings_surface, R.color.settings_divider)
        clipToOutline = true
    }

    fun addRow(row: View) {
        if (childCount > 0) addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.settings_divider))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, AppUi.dp(context, 1))))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
