package com.puneet.friday.debug.wake

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow
import com.puneet.friday.ui.FridayDialog

/** Optional Android settings links. The user changes settings on the system screen. */
class WakeBackgroundActivity : Activity() {
    private lateinit var batteryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (page, content) = AppUi.groupedContent(this, "Background listening")
        content.addView(AppUi.text(this,
            "Hey Friday can keep listening when you leave Friday or remove it from Recents. Pause it from the notification.",
            13f), AppUi.params(this, 16))
        batteryStatus = AppUi.text(this, "", 12f, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(batteryStatus, AppUi.params(this, 16))
        content.addView(AppUi.text(this,
            "If your phone stops listening, open App battery settings and choose Unrestricted or Allow background activity, if available. This can use more battery.",
            12f, secondary = true), AppUi.params(this, 8))
        val battery = SettingsGroup(this)
        battery.addRow(SettingsRow(this, "App battery settings") {
            openSettings(WakeBackgroundPolicy.Destination.APP_BATTERY)
        })
        battery.addRow(SettingsRow(this, "Battery optimization") {
            openSettings(WakeBackgroundPolicy.Destination.BATTERY_OPTIMIZATION)
        })
        content.addView(battery, AppUi.params(this, 16))
        if (WakeBackgroundPolicy.isVivo(Build.MANUFACTURER)) {
            content.addView(AppUi.sectionLabel(this, "Vivo / iQOO"), AppUi.params(this, 24))
            content.addView(AppUi.text(this,
                "Some versions also have Auto-start in Settings or iManager. Allow Friday there if you see it. You can lock Friday's card in Recents if your phone offers that option.",
                12f, secondary = true), AppUi.params(this, 8))
            content.addView(AppUi.button(this, "Open phone settings") {
                openSettings(WakeBackgroundPolicy.Destination.SETTINGS)
            }, AppUi.params(this, 12))
        }
        content.addView(AppUi.text(this,
            "After a restart, force-stop or Android's Stop control, open Friday and tap Resume Hey Friday. Battery access cannot prevent Android from stopping the app.",
            12f, secondary = true), AppUi.params(this, 24))
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
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        val exempt = try {
            getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)
        } catch (_: RuntimeException) { null }
        batteryStatus.text = when (exempt) {
            true -> "Android battery optimization: off for Friday"
            false -> "Android battery optimization: on for Friday"
            null -> "Android battery optimization: unavailable"
        }
    }

    private fun openSettings(from: WakeBackgroundPolicy.Destination) {
        val opened = WakeBackgroundPolicy.firstAvailable(from) { destination ->
            val intent = when (destination) {
                // This exported settings action varies by device; documented routes follow on failure.
                WakeBackgroundPolicy.Destination.APP_BATTERY -> Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL")
                    .setData(Uri.fromParts("package", packageName, null))
                WakeBackgroundPolicy.Destination.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                WakeBackgroundPolicy.Destination.APP_INFO -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                WakeBackgroundPolicy.Destination.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            }
            startActivity(intent)
        }
        if (opened == null) FridayDialog.show(this, "Settings unavailable",
            "Open your phone's Settings, select Friday under Apps, then check its battery settings.")
    }
}
