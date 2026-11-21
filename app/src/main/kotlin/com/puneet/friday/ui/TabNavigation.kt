package com.puneet.friday.ui

/** Main destinations share one Activity and retain their view state. */
enum class Destination { HOME, SETTINGS }

internal class TabNavigation {
    @Volatile var current = Destination.HOME
        private set

    fun select(destination: Destination): Boolean {
        if (current == destination) return false
        current = destination
        return true
    }
}
