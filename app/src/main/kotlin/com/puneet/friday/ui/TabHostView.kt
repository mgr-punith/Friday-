package com.puneet.friday.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Creates each main page once. Future destinations should follow the same pattern. */
@SuppressLint("ViewConstructor")
class TabHostView(
    context: Context,
    home: View,
    private val createSettings: () -> View,
) : LinearLayout(context) {
    private val navigation = TabNavigation()
    private val pages = FrameLayout(context)
    private val cachedPages = mutableMapOf(Destination.HOME to home)
    val destination: Destination get() = navigation.current

    init {
        orientation = VERTICAL
        pages.addView(home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(pages, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setOnApplyWindowInsetsListener { view, insets ->
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

    fun select(destination: Destination) {
        if (!navigation.select(destination)) return
        val page = cachedPages.getOrPut(destination) {
            createSettings().also {
                pages.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        cachedPages.values.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
    }
}
