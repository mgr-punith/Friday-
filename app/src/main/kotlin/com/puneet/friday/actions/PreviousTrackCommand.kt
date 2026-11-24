package com.puneet.friday.actions

internal object PreviousTrackCommand {
    fun send(seekToStart: () -> Unit, skipToPrevious: () -> Unit) {
        seekToStart()
        skipToPrevious()
    }
}
