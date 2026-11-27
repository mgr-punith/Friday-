package com.puneet.friday.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import com.puneet.friday.R
import kotlin.math.min

/** Shared, restrained framework controls for Friday's utility screens. */
object AppUi {
    enum class ButtonTone { PRIMARY, SECONDARY, QUIET, DESTRUCTIVE }

    /** Utility-page header stays visible while grouped settings scroll beneath it. */
    fun groupedContent(
        activity: Activity,
        title: String,
        onBack: () -> Unit = { activity.finish() },
        trailing: View? = null,
    ): Pair<LinearLayout, LinearLayout> {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 24))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pageHeader(activity, title, onBack, trailing), params(activity, 0))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val root = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(activity.getColor(R.color.screen_background))
            addView(column, LinearLayout.LayoutParams(
                min(activity.resources.displayMetrics.widthPixels, dp(activity, 560)), ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        return root to content
    }

    fun pageHeader(
        activity: Activity,
        title: String,
        onBack: (() -> Unit)? = null,
        trailing: View? = null,
    ): View = FrameLayout(activity).apply {
        setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 16))
        addView(text(activity, title, 17f, bold = true).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)).apply {
            marginStart = dp(activity, 56)
            marginEnd = dp(activity, 56)
        })
        if (onBack != null) addView(roundIconButton(activity, R.drawable.ic_arrow_back, "Back", onBack),
            FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply { gravity = Gravity.START })
        if (trailing != null) addView(trailing,
            FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply { gravity = Gravity.END })
    }

    fun roundIconButton(context: Context, icon: Int, label: String, onClick: () -> Unit): ImageButton =
        iconButton(context, icon, label, onClick).apply {
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_primary))
            background = RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.control_pressed)),
                surface(context, 24, R.color.navigation_surface, R.color.settings_divider), null,
            )
        }

    fun screen(activity: Activity, title: String, subtitle: String = "", onBack: () -> Unit = { activity.finish() }): LinearLayout {
        val (page, content) = groupedContent(activity, title, onBack)
        if (subtitle.isNotBlank()) content.addView(text(activity, subtitle, 13f, secondary = true), params(activity, 16))
        applyPageInsets(page)
        activity.setContentView(page)
        return content
    }

    fun applyPageInsets(page: View) {
        page.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    fun text(
        context: Context,
        value: String,
        size: Float = 13f,
        bold: Boolean = false,
        secondary: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = size
        typeface = geistTypeface(context, bold)
        setTextColor(context.getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    fun sectionLabel(context: Context, label: String): TextView =
        text(context, label, 11f, bold = true, secondary = true).apply { letterSpacing = 0.02f }

    internal fun compactVoiceText(context: Context, value: String): TextView = text(context, value, 16f).apply {
        setLineSpacing(0f, 1.16f)
    }

    fun button(
        context: Context,
        label: String,
        tone: ButtonTone = ButtonTone.SECONDARY,
        radius: Int = 12,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = geistTypeface(context, bold = false)
        gravity = Gravity.CENTER
        minHeight = dp(context, 48)
        minWidth = dp(context, 88)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        val filled = tone == ButtonTone.PRIMARY || tone == ButtonTone.DESTRUCTIVE
        setTextColor(context.getColor(if (filled) R.color.control_primary_text else R.color.text_primary))
        background = controlBackground(context, tone, radius)
        setOnClickListener { onClick() }
    }

    fun iconButton(context: Context, icon: Int, label: String, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
            contentDescription = label
            minimumWidth = dp(context, 48)
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = controlBackground(context, ButtonTone.QUIET, 24)
            setOnClickListener { onClick() }
        }

    fun styleField(field: EditText) {
        field.apply {
            typeface = geistTypeface(context, bold = false)
            textSize = 13f
            minHeight = dp(context, 48)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_tertiary))
            background = surface(context, 12, R.color.field_background, R.color.border)
        }
    }

    fun notice(context: Context, value: String): TextView = text(context, value, 12f).apply {
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        background = surface(context, 20, R.color.settings_surface, R.color.settings_divider)
    }

    fun surface(
        context: Context,
        radiusDp: Int,
        fillColor: Int = R.color.surface,
        strokeColor: Int? = R.color.border,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(context.getColor(fillColor))
        if (strokeColor != null) setStroke(dp(context, 1), context.getColor(strokeColor))
    }

    private fun controlBackground(context: Context, tone: ButtonTone, radius: Int): RippleDrawable {
        val fill = when (tone) {
            ButtonTone.PRIMARY -> R.color.control_primary
            ButtonTone.SECONDARY -> R.color.settings_surface
            ButtonTone.QUIET -> android.R.color.transparent
            ButtonTone.DESTRUCTIVE -> R.color.error
        }
        val stroke = when (tone) {
            ButtonTone.PRIMARY -> R.color.control_primary
            ButtonTone.SECONDARY -> R.color.settings_divider
            ButtonTone.QUIET -> null
            ButtonTone.DESTRUCTIVE -> R.color.error
        }
        return RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.control_pressed)),
            surface(context, radius, fill, stroke),
            null,
        )
    }

    fun params(context: Context, bottom: Int = 12): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, bottom)
        }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun geistTypeface(context: Context, bold: Boolean): Typeface = Typeface.create(
        context.resources.getFont(R.font.geist),
        if (bold) Typeface.BOLD else Typeface.NORMAL,
    )
}
