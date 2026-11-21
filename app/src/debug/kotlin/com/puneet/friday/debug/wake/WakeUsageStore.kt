package com.puneet.friday.debug.wake

import android.content.Context

/** Debug-only preference. No receiver or application startup reads it to start a mic. */
internal object WakeUsageStore {
    private const val PREFERENCES = "friday_wake_usage"

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun mode(context: Context): WakeUsagePolicy.Mode =
        WakeUsagePolicy.Mode.from(preferences(context).getString("mode", null))

    fun revision(context: Context): Long = preferences(context).getLong("revision", 0)

    fun consented(context: Context): Boolean = preferences(context).getBoolean("consented", false)

    fun consent(context: Context) { preferences(context).edit().putBoolean("consented", true).apply() }

    fun select(context: Context, mode: WakeUsagePolicy.Mode): Boolean = preferences(context).edit()
        .putString("mode", mode.value).putLong("revision", nextRevision(context)).commit()

    /** Each notification action consumes its revision, so a second tap cannot replay it. */
    fun consume(context: Context, requestedRevision: Long): Boolean {
        if (!WakeUsagePolicy.accepts(mode(context), revision(context), requestedRevision)) return false
        return preferences(context).edit().putLong("revision", nextRevision(context)).commit()
    }

    private fun nextRevision(context: Context): Long = revision(context).let { if (it == Long.MAX_VALUE) 1 else it + 1 }
}
