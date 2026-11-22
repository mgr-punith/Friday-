package com.puneet.friday.debug.wake

/** Settings navigation does not grant microphone access or start a listener. */
internal object WakeBackgroundPolicy {
    enum class Destination { APP_BATTERY, BATTERY_OPTIMIZATION, APP_INFO, SETTINGS }

    fun stopOnTaskRemoval(controlled: Boolean, mode: WakeUsagePolicy.Mode): Boolean =
        !controlled || mode != WakeUsagePolicy.Mode.HEY_FRIDAY

    fun isVivo(manufacturer: String): Boolean = manufacturer.trim().lowercase(java.util.Locale.ROOT) in setOf("vivo", "iqoo")

    fun firstAvailable(
        from: Destination,
        launch: (Destination) -> Unit,
    ): Destination? {
        for (destination in Destination.entries.drop(from.ordinal)) {
            try {
                launch(destination)
                return destination
            } catch (_: RuntimeException) {
                // Missing screens and manufacturer/device-policy restrictions must stay recoverable.
            }
        }
        return null
    }
}
