package com.puneet.friday.actions

import android.content.Intent
import android.provider.Settings

/** Fixed public Android Settings routes. Model output never supplies an intent action or URI. */
object SettingsHandoff {
    enum class Route(val intentAction: String) {
        AIRPLANE_MODE(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        WIRELESS(Settings.ACTION_WIRELESS_SETTINGS),
        SETTINGS(Settings.ACTION_SETTINGS),
    }

    fun routes(setting: SettingsTarget): List<Route> = when (setting) {
        SettingsTarget.AIRPLANE_MODE -> Route.entries
    }

    fun open(setting: SettingsTarget, launch: (Intent) -> Unit): Route? =
        firstAvailable(routes(setting)) { route -> launch(Intent(route.intentAction)) }

    internal fun firstAvailable(
        routes: List<Route>,
        launch: (Route) -> Unit,
    ): Route? {
        for (route in routes) {
            try {
                launch(route)
                return route
            } catch (_: RuntimeException) {
                // Android and device policy can omit or block a public Settings screen.
            }
        }
        return null
    }

    fun instruction(action: PhoneAction.OpenSettings): String = when (action.target) {
        SettingsTarget.AIRPLANE_MODE -> when (action.state) {
            SettingState.ON -> "Turn airplane mode on here"
            SettingState.OFF -> "Turn airplane mode off here"
            null -> "Change airplane mode here"
        }
    }
}
