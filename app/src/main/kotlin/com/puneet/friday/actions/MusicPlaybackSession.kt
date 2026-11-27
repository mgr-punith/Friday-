package com.puneet.friday.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Connects to a browser service exported by the player selected on this phone. */
internal class MusicPlaybackSession(private val context: Context) {
    /** Kept bound through the foreground dispatch, even for players that stop on the last unbind. */
    class Connection(private val browser: MediaBrowser, private val controller: MediaController) {
        private var closed = false

        fun playTitle(query: String, extras: Bundle): Boolean {
            if (closed || !browser.isConnected || !acceptsTitle(controller)) return false
            controller.transportControls.playFromSearch(query, extras)
            return true
        }

        fun close() {
            if (closed) return
            closed = true
            try { browser.disconnect() } catch (_: RuntimeException) { /* Already disconnected. */ }
        }
    }

    fun find(packageName: String): Connection? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val services = context.packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0,
        ).map { it.serviceInfo }.filter {
            it.packageName == packageName && it.exported &&
                (it.permission == null || context.checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED)
        }.mapTo(mutableSetOf()) { ComponentName(it.packageName, it.name) }
        if (services.isEmpty()) return null

        val result = CompletableFuture<Connection?>()
        val browsers = mutableListOf<MediaBrowser>()
        val main = Handler(Looper.getMainLooper())
        var selected: MediaBrowser? = null
        // MediaBrowser delivers callbacks on its constructor's looper. Only Ready.run in
        // the visible UI sends a command; a cancelled worker must never start playback.
        if (!main.post {
            val pending = services.toMutableSet()
            fun settled(service: ComponentName) {
                pending.remove(service)
                if (pending.isEmpty()) result.complete(null)
            }
            for (service in services) {
                if (result.isDone) break
                try {
                    lateinit var browser: MediaBrowser
                    browser = MediaBrowser(context, service, object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            try {
                                if (result.isDone) return
                                val controller = MediaController(context, browser.sessionToken)
                                if (controller.packageName == packageName && acceptsTitle(controller)) {
                                    val connection = Connection(browser, controller)
                                    if (result.complete(connection)) {
                                        selected = browser
                                        // A cancelled voice request can discard Ready without running it.
                                        main.postDelayed({ connection.close() }, 2_000L)
                                    }
                                }
                            } catch (_: RuntimeException) {
                                // A service can reject Friday or withdraw its token during connection.
                            } finally {
                                settled(service)
                            }
                        }

                        override fun onConnectionFailed() = settled(service)
                        override fun onConnectionSuspended() = settled(service)
                    }, null)
                    browsers += browser
                    browser.connect()
                } catch (_: RuntimeException) {
                    settled(service)
                }
            }
        }) return null

        return try {
            result.get(900L, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            result.cancel(false)
            main.post {
                browsers.filterNot { it === selected }.forEach { browser ->
                    try { browser.disconnect() } catch (_: RuntimeException) { /* Already disconnected. */ }
                }
            }
        }
    }

    private companion object {
        fun acceptsTitle(controller: MediaController): Boolean {
            val actions = controller.playbackState?.actions ?: return false
            return actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
        }
    }
}
