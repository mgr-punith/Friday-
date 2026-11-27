package com.puneet.friday.ui

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Draws the Home orb's particle shell, spiral and orbital streaks in OpenGL ES 2. */
class ProceduralOrbRenderer(
    density: Float,
    private val particleCount: Int = 22_000,
    private val onError: (String) -> Unit = {},
) : GLSurfaceView.Renderer {
    private data class Program(
        val id: Int,
        val attribute: Int,
        val time: Int,
        val rotation: Int,
        val resolution: Int,
        val dpr: Int,
        val brightness: Int,
        val scale: Int,
        val audio: Int,
    )

    private data class AudioSignal(val rms: Float, val low: Float, val mid: Float, val high: Float)

    private val pointScale = if (density.isFinite()) density.coerceIn(1f, 2f) else 1f
    private val bufferIds = IntArray(3)
    private var particleProgram: Program? = null
    private var coreProgram: Program? = null
    private var arcProgram: Program? = null
    private var ready = false
    private var viewportSize = 1
    private var timeSeconds = 0f
    private var previousFrame = 0L
    private var rotationX = 0f
    private var rotationY = 0f
    private val envelope = VoiceMotion()
    private var arcVertexCount = 0

    @Volatile private var motionEnabled = true
    @Volatile private var resetClock = true
    @Volatile private var speed = 1f
    @Volatile private var brightness = 1f
    @Volatile private var geometryScale = .82f
    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    @Volatile private var audio = AudioSignal(0f, 0f, 0f, 0f)
    @Volatile private var resetEnvelope = false

    init {
        require(particleCount in 1_000..60_000) { "Particle count must be between 1,000 and 60,000." }
    }

    fun setMotionEnabled(enabled: Boolean) {
        if (motionEnabled != enabled) {
            motionEnabled = enabled
            resetClock = true
        }
    }

    fun resetFrameClock() {
        resetClock = true
    }

    fun setSpeed(value: Float) {
        if (value.isFinite()) speed = value.coerceIn(.25f, 2f)
    }

    fun setBrightness(value: Float) {
        if (value.isFinite()) brightness = value.coerceIn(.5f, 1.8f)
    }

    /** Nominal particle-sphere radius / shorter view dimension; outer arcs extend beyond it. */
    fun setRadiusRatio(value: Float) {
        if (value.isFinite()) geometryScale = value.coerceIn(.1f, .44f) * 2f / .875f
    }

    /** Optional rotation input. Preserve Friday's existing tap-to-speak action in the host. */
    fun setRotation(yaw: Float, pitch: Float) {
        if (yaw.isFinite()) targetX = yaw.coerceIn(-8f, 8f)
        if (pitch.isFinite()) targetY = pitch.coerceIn(-1.3f, 1.3f)
    }

    /** Inputs must already have passed the app's existing noise gate. */
    fun setAudioSignal(rms: Float, low: Float, mid: Float, high: Float) {
        audio = AudioSignal(unit(rms), unit(low), unit(mid), unit(high))
    }

    fun clearAudioSignal() {
        audio = AudioSignal(0f, 0f, 0f, 0f)
        resetEnvelope = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Previous handles belong to the lost context. Rebuild all GPU resources.
        ready = false
        bufferIds.fill(0)
        particleProgram = null
        coreProgram = null
        arcProgram = null
        try {
            particleProgram = createProgram(PARTICLE_VERTEX, PARTICLE_FRAGMENT, "a_particle")
            coreProgram = createProgram(QUAD_VERTEX, CORE_FRAGMENT, "a_position")
            arcProgram = createProgram(LINE_VERTEX, LINE_FRAGMENT, "a_position")
            GLES20.glGenBuffers(3, bufferIds, 0)
            check(bufferIds.all { it != 0 }) { "Could not allocate orb vertex buffers." }
            val random = SeededRandom()
            val particles = FloatArray(particleCount * 4)
            for (index in 0 until particleCount) {
                particles[index * 4] = random.next() * TWO_PI
                particles[index * 4 + 1] = acos(1f - 2f * random.next())
                particles[index * 4 + 2] = .955f + random.next() * .045f
                particles[index * 4 + 3] = random.next()
            }
            upload(bufferIds[0], particles)
            upload(bufferIds[1], floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f))

            // Independent line segments allow all twenty arcs to render in one draw call.
            val arcs = FloatArray(20 * 25 * 2 * 4)
            var offset = 0
            repeat(20) { index ->
                val angle = random.next() * TWO_PI
                val length = .057f + random.next() * .22f
                val radius = .94f + random.next() * .04f
                for (segment in 0 until 25) {
                    for (endpoint in 0..1) {
                        val fraction = (segment + endpoint) / 25f
                        val theta = angle + length * fraction
                        arcs[offset++] = cos(theta) * radius
                        arcs[offset++] = sin(theta) * radius
                        arcs[offset++] = sin(PI.toFloat() * fraction) * .55f
                        arcs[offset++] = (index % 5).toFloat()
                    }
                }
            }
            arcVertexCount = offset / 4
            upload(bufferIds[2], arcs)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            // Additive white light with matching premultiplied output alpha. A host with
            // an alpha EGL surface can composite the particles over a charcoal card.
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ONE)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            resetClock = true
            ready = true
        } catch (error: RuntimeException) {
            release()
            onError(error.message ?: "The orb renderer could not initialize.")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportSize = min(width, height).coerceAtLeast(1)
        // A square viewport preserves a sphere even when the parent view is rectangular.
        GLES20.glViewport((width - viewportSize) / 2, (height - viewportSize) / 2, viewportSize, viewportSize)
        resetClock = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!ready) return
        val now = SystemClock.elapsedRealtimeNanos()
        val delta = if (resetClock || previousFrame == 0L || !motionEnabled) 0f
        else ((now - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
        previousFrame = now
        resetClock = false
        val currentAudio = audio
        if (resetEnvelope) {
            envelope.reset()
            resetEnvelope = false
        }
        envelope.setTarget(currentAudio.rms, currentAudio.low, currentAudio.mid, currentAudio.high)
        envelope.advance(delta)
        timeSeconds += delta * speed
        if (motionEnabled) {
            val rotationSmoothing = 1f - exp(-delta * 3.4f)
            rotationX += (targetX - rotationX) * rotationSmoothing
            rotationY += (targetY - rotationY) * rotationSmoothing
        }

        val particles = particleProgram ?: return
        bind(particles, bufferIds[0], 4)
        GLES20.glUniform1f(particles.dpr, pointScale)
        GLES20.glUniform1f(particles.resolution, viewportSize.toFloat())
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particleCount)
        GLES20.glDisableVertexAttribArray(particles.attribute)

        val core = coreProgram ?: return
        bind(core, bufferIds[1], 2)
        GLES20.glUniform1f(core.resolution, viewportSize.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(core.attribute)

        val arcs = arcProgram ?: return
        bind(arcs, bufferIds[2], 4)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, arcVertexCount)
        GLES20.glDisableVertexAttribArray(arcs.attribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun bind(program: Program, buffer: Int, dimensions: Int) {
        GLES20.glUseProgram(program.id)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glEnableVertexAttribArray(program.attribute)
        GLES20.glVertexAttribPointer(program.attribute, dimensions, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glUniform1f(program.time, timeSeconds)
        GLES20.glUniform2f(program.rotation, rotationX, rotationY)
        GLES20.glUniform1f(program.brightness, brightness)
        GLES20.glUniform1f(program.scale, geometryScale)
        GLES20.glUniform4f(program.audio, envelope.energy, envelope.low, envelope.mid, envelope.high)
    }

    private fun upload(id: Int, values: FloatArray) {
        val data = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(values).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, values.size * 4, data, GLES20.GL_STATIC_DRAW)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Could not create an orb shader." }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Orb shader compilation failed: $message")
        }
        return shader
    }

    private fun createProgram(vertex: String, fragment: String, attribute: String): Program {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        var fragmentShader = 0
        var id = 0
        try {
            fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            id = GLES20.glCreateProgram()
            check(id != 0) { "Could not create an orb shader program." }
            GLES20.glAttachShader(id, vertexShader)
            GLES20.glAttachShader(id, fragmentShader)
            GLES20.glLinkProgram(id)
            val status = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Orb shader link failed: ${GLES20.glGetProgramInfoLog(id)}" }
            val location = GLES20.glGetAttribLocation(id, attribute)
            check(location >= 0) { "Orb vertex attribute is unavailable." }
            return Program(
                id, location,
                GLES20.glGetUniformLocation(id, "u_time"),
                GLES20.glGetUniformLocation(id, "u_rotation"),
                GLES20.glGetUniformLocation(id, "u_resolution"),
                GLES20.glGetUniformLocation(id, "u_dpr"),
                GLES20.glGetUniformLocation(id, "u_brightness"),
                GLES20.glGetUniformLocation(id, "u_scale"),
                GLES20.glGetUniformLocation(id, "u_audio"),
            )
        } catch (error: RuntimeException) {
            if (id != 0) GLES20.glDeleteProgram(id)
            throw error
        } finally {
            GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
        }
    }

    /** Must execute on the renderer's current GL thread before its EGL context is destroyed. */
    fun release() {
        ready = false
        particleProgram?.let { GLES20.glDeleteProgram(it.id) }
        coreProgram?.let { GLES20.glDeleteProgram(it.id) }
        arcProgram?.let { GLES20.glDeleteProgram(it.id) }
        particleProgram = null
        coreProgram = null
        arcProgram = null
        GLES20.glDeleteBuffers(3, bufferIds, 0)
        bufferIds.fill(0)
        previousFrame = 0L
    }

    private class SeededRandom {
        private var state = 72121
        fun next(): Float {
            state = state * 1664525 + 1013904223
            return ((state.toLong() and 0xFFFFFFFFL) / 4294967296.0).toFloat()
        }
    }

    private companion object {
        const val TWO_PI = (PI * 2).toFloat()
        fun unit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

        val PARTICLE_VERTEX = """
            attribute vec4 a_particle;
            uniform float u_time;
            uniform float u_resolution;
            uniform float u_dpr;
            uniform vec2 u_rotation;
            uniform float u_scale;
            uniform vec4 u_audio;
            varying float v_alpha;
            mat3 rotateY(float a) {
            return mat3(cos(a),0.,sin(a), 0.,1.,0., -sin(a),0.,cos(a));
            }
            mat3 rotateX(float a) {
            return mat3(1.,0.,0., 0.,cos(a),-sin(a), 0.,sin(a),cos(a));
            }
            void main() {
            float azimuth = a_particle.x;
            float elevation = a_particle.y;
            float seed = a_particle.w;
            float roll = u_time * .52;
            float y = cos(elevation);
            float ridgeShare = fract(seed * 13.);
            float ripple = .086 * sin(azimuth * 2. + roll) + .06 * sin(azimuth * 4. - roll);
            float wiggle = u_time * (6.2831853 * 2.4);
            ripple += (.12 * u_audio.x + .32 * u_audio.y) * sin(azimuth * 3. - wiggle);
            float band = (y > 0. ? .57 : -.51) + ripple;
            // Put a subset of points on narrow folds, with the rest spread across the shell.
            if (ridgeShare < .24) {
                float offset = 2. * abs(y) - 1.;
                y = clamp(band + offset * (.029 + .20 * offset * offset + .12 * u_audio.z), -1., 1.);
            }
            float horizontal = sqrt(max(0., 1. - y * y));
            vec3 direction = vec3(cos(azimuth) * horizontal, y, sin(azimuth) * horizontal);
            float ridgeDistance = min(abs(y - .57 - ripple), abs(y + .51 - ripple));
            float ridge = 1. - smoothstep(.008, .057 + .06 * u_audio.z, ridgeDistance);
            float wave = sin(azimuth * 3. + elevation * 5. - wiggle);
            float radius = .875 * (a_particle.z + .025 * ridge + .10 * u_audio.x * wave);
            vec3 p = direction * radius;
            p.z += u_audio.w * .048 * cos(p.y * 9. - wiggle);
            p = rotateX(.69 + .57 * sin(roll) + u_rotation.y) * rotateY(roll + u_rotation.x) * p;
            p.xy = mat2(.94, .34, -.34, .94) * p.xy;
            float perspective = 1. / (1. - p.z * .092);
            gl_Position = vec4(p.xy * u_scale * perspective, 0., 1.);
            float front = .41 + .59 * smoothstep(-.49, .56, p.z);
            float grain = mix(.21, .76, seed * seed);
            float ridgeLight = .45 + .55 * smoothstep(-.8, .4, cos(azimuth * 2. - roll * 2. + band));
            v_alpha = (grain + ridge * (.77 + u_audio.x * .57) * ridgeLight) * front;
            float radiusPixels = .4375 * u_scale * u_resolution;
            gl_PointSize = max(.83 * u_dpr, radiusPixels * .0056) * (.72 + .55 * seed + .19 * ridge);
            }
        """.trimIndent()

        val PARTICLE_FRAGMENT = """
            precision mediump float;
            varying float v_alpha;
            uniform float u_brightness;
            void main() {
            float d = length(gl_PointCoord - .5) * 2.;
            float alpha = (1. - smoothstep(.13, 1., d)) * v_alpha;
            gl_FragColor = vec4(vec3(.99), alpha * u_brightness);
            }
        """.trimIndent()

        val QUAD_VERTEX = """
            attribute vec2 a_position;
            varying vec2 v_position;
            void main() {
            v_position = a_position;
            gl_Position = vec4(a_position, 0., 1.);
            }
        """.trimIndent()

        val CORE_FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 v_position;
            uniform float u_time;
            uniform float u_resolution;
            uniform vec2 u_rotation;
            uniform float u_scale;
            uniform float u_brightness;
            uniform vec4 u_audio;
            void main() {
            vec2 p = v_position / (.875 * u_scale);
            p.x += u_rotation.x * .012;
            p.y += u_rotation.y * .012;
            float wiggle = u_time * (6.2831853 * 2.4);
            p.x += (.021 * u_audio.x + .021 * u_audio.y) * sin(p.y * 9. + wiggle);
            p.y += (.017 * u_audio.x + .028 * u_audio.z) * sin(p.x * 11. - wiggle);
            float r = length(p);
            if (r >= .755) discard;
            float turn = atan(p.y + .000001, p.x) / 6.2831853;
            float aa = 1. / (.875 * u_scale * u_resolution);

            // The white center has regular, close turns and thin dark gaps.
            float rotation = turn - .139 * u_time;
            float innerPhase = 49. * r + rotation + .946;
            float innerLine = abs(fract(innerPhase) - .5);
            float innerAa = min(.21, 49. * aa * .62);
            float spiral = 1. - smoothstep(.34 - innerAa, .34 + innerAa, innerLine);
            spiral *= 1. - smoothstep(.163, .248, r);

            // The outer turns start after a gap and draw closer together at the edge.
            float outerPhase = ((46.17 * r - 43.03) * r + 27.79) * r + .67 + rotation;
            float slope = (138.51 * r - 86.06) * r + 27.79;
            float lineDistance = abs(fract(outerPhase) - .5) / slope;
            float rings = 1. - smoothstep(max(.0005, .0062 - aa), .0062 + aa, lineDistance);
            rings *= smoothstep(.223, .251, r) * (1. - smoothstep(.385, .755, r));
            float glow = .15 * (1. - smoothstep(.145, .248, r));
            float sheen = .91 + .09 * sin(turn * 6.2831853 - u_time * .5);
            float alpha = (spiral * .84 + glow + rings * .56 * sheen) * (1. + u_audio.x * .18);
            gl_FragColor = vec4(vec3(1.), alpha * u_brightness);
            }
        """.trimIndent()

        val LINE_VERTEX = """
            attribute vec4 a_position;
            uniform float u_time;
            uniform vec2 u_rotation;
            uniform float u_scale;
            uniform vec4 u_audio;
            varying float v_alpha;
            mat2 rotate(float a) { return mat2(cos(a), sin(a), -sin(a), cos(a)); }
            void main() {
            float orbit = a_position.w;
            float direction = 1. - 2. * mod(orbit, 2.);
            vec2 p = rotate(direction * u_time * (.20 + .045 * orbit)) * a_position.xy;
            // Tilt each circle in 3D so some dashes cross the face of the shell.
            float tilt = orbit * .39 + .18 * sin(u_time * .45 + orbit) + .30 * u_audio.z;
            float depth = p.y * sin(tilt);
            p.y *= cos(tilt);
            p = rotate(orbit * 1.28 + u_time * .11 + u_rotation.x * .08) * p;
            p *= (1. + .04 * u_audio.x * sin(orbit + u_time * (6.2831853 * 2.4))) / (1. - .085 * depth);
            p.y += u_rotation.y * .014;
            gl_Position = vec4(p * u_scale, 0., 1.);
            v_alpha = a_position.z * (1.73 + .17 * smoothstep(-.4, .3, depth) + .28 * u_audio.w);
            v_alpha *= smoothstep(.13, .27, length(p));
            }
        """.trimIndent()

        val LINE_FRAGMENT = """
            precision mediump float;
            varying float v_alpha;
            uniform float u_brightness;
            void main() { gl_FragColor = vec4(vec3(.98), v_alpha * u_brightness); }
        """.trimIndent()
    }
}
