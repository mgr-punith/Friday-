package com.puneet.friday.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.media.MediaBrowserService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds a single current player that supports both seek and previous-track controls. */
internal class PreviousTrackMediaSessionResolver(private val context: Context) {
    fun findController(): MediaController? {
        val services = context.packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.serviceInfo }
            .filter { it.exported }
            .distinctBy { it.packageName to it.name }
        if (services.isEmpty()) return null

        val completed = CountDownLatch(services.size)
        val sessions = mutableListOf<Session>()
        val browsers = mutableListOf<MediaBrowser>()

        try {
            services.forEach { service ->
                val component = ComponentName(service.packageName, service.name)
                lateinit var browser: MediaBrowser
                browser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            val controller = MediaController(context, browser.sessionToken)
                            val state = controller.playbackState ?: return
                            val required = PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
                            if (state.state in PLAYABLE_STATES && state.actions and required == required) {
                                synchronized(sessions) {
                                    sessions += Session(controller, state.state)
                                }
                            }
                        } catch (_: RuntimeException) {
                            // A player can reject a browser connection or revoke its session.
                        } finally {
                            completed.countDown()
                        }
                    }

                    override fun onConnectionSuspended() {
                        completed.countDown()
                    }

                    override fun onConnectionFailed() {
                        completed.countDown()
                    }
                }, null)
                browsers += browser
                try {
                    browser.connect()
                } catch (_: RuntimeException) {
                    completed.countDown()
                }
            }

            if (!completed.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            val connected = synchronized(sessions) { sessions.toList() }
            val playing = connected.filter { it.state == PlaybackState.STATE_PLAYING }
            if (playing.size == 1) return playing.single().controller
            if (playing.isNotEmpty()) return null
            return connected.singleOrNull { it.state == PlaybackState.STATE_PAUSED }?.controller
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            browsers.forEach { browser ->
                try {
                    browser.disconnect()
                } catch (_: RuntimeException) {
                    // Disconnection is best effort after a command target was resolved.
                }
            }
        }
    }

    private data class Session(val controller: MediaController, val state: Int)

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 900L
        val PLAYABLE_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)
    }
}
