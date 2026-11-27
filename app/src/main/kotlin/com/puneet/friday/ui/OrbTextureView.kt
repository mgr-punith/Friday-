package com.puneet.friday.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/** Small alpha-capable EGL host for the procedural orb; it never decodes media. */
class OrbTextureView(
    context: Context,
    private val renderer: ProceduralOrbRenderer,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: OrbRenderThread? = null
    private var frameRate = 0
    private var paused = true

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setFrameRate(value: Int) {
        frameRate = value
        thread?.setFrameRate(value)
    }

    fun requestFrame() {
        thread?.requestFrame()
    }

    fun resumeRendering() {
        paused = false
        thread?.setPaused(false)
    }

    fun pauseRendering() {
        paused = true
        thread?.setPaused(true)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.stopAndJoin()
        thread = OrbRenderThread(surface, width, height, renderer, frameRate, paused).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.stopAndJoin()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private class OrbRenderThread(
        private val texture: SurfaceTexture,
        initialWidth: Int,
        initialHeight: Int,
        private val renderer: ProceduralOrbRenderer,
        initialFrameRate: Int,
        initialPaused: Boolean,
    ) : Thread("FridayOrbGL") {
        private val lock = Object()
        private var width = initialWidth
        private var height = initialHeight
        private var frameRate = initialFrameRate
        private var frameRequested = true
        private var paused = initialPaused
        private var stopping = false
        private var egl: EGL10? = null
        private var display: EGLDisplay? = null
        private var config: EGLConfig? = null
        private var context: EGLContext? = null
        private var surface: EGLSurface? = null

        fun setFrameRate(value: Int) {
            synchronized(lock) {
                if (frameRate == value) return
                frameRate = value
                frameRequested = true
                lock.notifyAll()
            }
        }

        fun requestFrame() {
            synchronized(lock) {
                frameRequested = true
                lock.notifyAll()
            }
        }

        fun setPaused(value: Boolean) {
            synchronized(lock) {
                paused = value
                frameRequested = true
                lock.notifyAll()
            }
        }

        fun resize(newWidth: Int, newHeight: Int) {
            synchronized(lock) {
                width = newWidth
                height = newHeight
                frameRequested = true
                lock.notifyAll()
            }
        }

        fun stopAndJoin() {
            synchronized(lock) {
                stopping = true
                lock.notifyAll()
            }
            if (Thread.currentThread() !== this) join(1_500)
        }

        override fun run() {
            try {
                initializeEgl()
                val currentConfig = config ?: error("Orb EGL config is unavailable")
                renderer.onSurfaceCreated(null, currentConfig)
                var renderedWidth = 0
                var renderedHeight = 0
                while (true) {
                    val frameWidth: Int
                    val frameHeight: Int
                    val fps: Int
                    synchronized(lock) {
                        while (!stopping && (paused || (frameRate == 0 && !frameRequested))) lock.wait()
                        if (stopping) break
                        frameRequested = false
                        frameWidth = width
                        frameHeight = height
                        fps = frameRate
                    }
                    val started = System.nanoTime()
                    if (frameWidth != renderedWidth || frameHeight != renderedHeight) {
                        renderer.onSurfaceChanged(null, frameWidth, frameHeight)
                        renderedWidth = frameWidth
                        renderedHeight = frameHeight
                    }
                    renderer.onDrawFrame(null)
                    val eglApi = egl ?: break
                    val eglDisplay = display ?: break
                    val eglSurface = surface ?: break
                    eglApi.eglSwapBuffers(eglDisplay, eglSurface)
                    if (fps > 0) {
                        val remaining = 1_000_000_000L / fps - (System.nanoTime() - started)
                        if (remaining > 0) sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    }
                }
            } catch (_: InterruptedException) {
                interrupt()
            } catch (error: RuntimeException) {
                renderer.release()
            } finally {
                releaseEgl()
            }
        }

        private fun initializeEgl() {
            val eglApi = EGLContext.getEGL() as EGL10
            val eglDisplay = eglApi.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL10.EGL_NO_DISPLAY) { "Orb EGL display unavailable" }
            check(eglApi.eglInitialize(eglDisplay, IntArray(2))) { "Orb EGL initialization failed" }
            val attributes = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(eglApi.eglChooseConfig(eglDisplay, attributes, configs, 1, count) && count[0] > 0) {
                "Orb EGL config unavailable"
            }
            val chosen = configs[0] ?: error("Orb EGL config unavailable")
            val contextAttributes = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
            val eglContext = eglApi.eglCreateContext(eglDisplay, chosen, EGL10.EGL_NO_CONTEXT, contextAttributes)
            check(eglContext != EGL10.EGL_NO_CONTEXT) { "Orb EGL context unavailable" }
            val eglSurface = eglApi.eglCreateWindowSurface(eglDisplay, chosen, texture, null)
            check(eglSurface != EGL10.EGL_NO_SURFACE) { "Orb EGL surface unavailable" }
            check(eglApi.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "Orb EGL context could not become current"
            }
            egl = eglApi
            display = eglDisplay
            config = chosen
            context = eglContext
            surface = eglSurface
        }

        private fun releaseEgl() {
            val eglApi = egl ?: return
            val eglDisplay = display ?: return
            val eglSurface = surface
            val eglContext = context
            if (eglSurface != null && eglContext != null) {
                eglApi.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                eglApi.eglDestroySurface(eglDisplay, eglSurface)
                eglApi.eglDestroyContext(eglDisplay, eglContext)
            }
            eglApi.eglTerminate(eglDisplay)
            surface = null
            context = null
            display = null
            egl = null
        }

        private companion object {
            const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        }
    }
}
