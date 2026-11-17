package com.example.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.assistant.R
import kotlin.math.min

/** Shared, restrained framework controls for Friday's utility screens. */
object AppUi {
    enum class ButtonTone { PRIMARY, SECONDARY, QUIET }

    fun screen(activity: Activity, title: String, subtitle: String): LinearLayout {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 32))
        }
        val holder = FrameLayout(activity).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    min(activity.resources.displayMetrics.widthPixels, dp(activity, 560)),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL },
            )
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            setBackgroundColor(activity.getColor(R.color.screen_background))
            addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(scroll)
        content.addView(text(activity, title, 24f, bold = true), params(activity, 6))
        content.addView(text(activity, subtitle, 14f, secondary = true), params(activity, 24))
        return content
    }

    fun text(
        context: Context,
        value: String,
        size: Float = 16f,
        bold: Boolean = false,
        secondary: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = size
        typeface = geist(context, bold)
        setTextColor(context.getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    fun sectionLabel(context: Context, label: String): TextView =
        text(context, label, 14f, bold = true, secondary = true).apply { letterSpacing = 0.02f }

    fun button(
        context: Context,
        label: String,
        tone: ButtonTone = ButtonTone.SECONDARY,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = geist(context, bold = true)
        gravity = Gravity.CENTER
        minHeight = dp(context, 48)
        minWidth = dp(context, 88)
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        setTextColor(context.getColor(if (tone == ButtonTone.PRIMARY) R.color.control_primary_text else R.color.text_primary))
        background = controlBackground(context, tone, 12)
        setOnClickListener { onClick() }
    }

    fun navigationButton(
        context: Context,
        title: String,
        detail: String,
        onClick: () -> Unit,
    ): Button = button(context, "", ButtonTone.SECONDARY, onClick).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = dp(context, 66)
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        compoundDrawablePadding = dp(context, 12)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
        setNavigationText(this, title, detail)
    }

    fun setNavigationText(button: Button, title: String, detail: String) {
        val value = SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            append('\n')
            val start = length
            append(detail)
            setSpan(RelativeSizeSpan(0.86f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(button.context.getColor(R.color.text_secondary)),
                start,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        button.text = value
        button.contentDescription = "$title. $detail"
    }

    fun styleField(field: EditText) {
        field.apply {
            typeface = geist(context, bold = false)
            textSize = 16f
            minHeight = dp(context, 48)
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_tertiary))
            background = surface(context, 12, R.color.field_background, R.color.border)
        }
    }

    fun notice(context: Context, value: String): TextView = text(context, value, 14f).apply {
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        background = surface(context, 12, R.color.surface_muted, R.color.border)
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
            ButtonTone.SECONDARY -> R.color.surface
            ButtonTone.QUIET -> android.R.color.transparent
        }
        val stroke = when (tone) {
            ButtonTone.PRIMARY -> R.color.control_primary
            ButtonTone.SECONDARY -> R.color.border
            ButtonTone.QUIET -> null
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

    private fun geist(context: Context, bold: Boolean): Typeface = Typeface.create(
        context.resources.getFont(R.font.geist),
        if (bold) Typeface.BOLD else Typeface.NORMAL,
    )
}
