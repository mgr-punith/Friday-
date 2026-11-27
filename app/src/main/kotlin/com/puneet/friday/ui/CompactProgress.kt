package com.puneet.friday.ui

/** Holds a ready presentation until both compact progress labels have had time on screen. */
internal class CompactProgress(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var next: Runnable? = null
    private var ready: (() -> Unit)? = null

    fun start(expand: () -> Unit) {
        cancel()
        afterStage {
            expand()
            afterStage {
                val show = ready
                ready = null
                show?.invoke()
            }
        }
    }

    fun present(show: () -> Unit) {
        if (next == null) show() else ready = show
    }

    fun cancel() {
        next?.let(remove)
        next = null
        ready = null
    }

    private fun afterStage(action: () -> Unit) {
        val callback = Runnable {
            next = null
            action()
        }
        next = callback
        post(callback, 780L)
    }
}
