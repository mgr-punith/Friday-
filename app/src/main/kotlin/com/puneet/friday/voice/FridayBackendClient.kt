package com.puneet.friday.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.puneet.friday.BuildConfig
import com.puneet.friday.actions.PhoneCapabilityRegistry
import com.puneet.friday.auth.AccountClient
import com.puneet.friday.ui.AnswerConsent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import org.json.JSONObject
import org.json.JSONArray

class FridayBackendClient(
    context: Context,
    baseUrl: String = BuildConfig.BACKEND_URL,
) {
    private val appContext = context.applicationContext
    private val accounts = AccountClient(appContext, baseUrl)
    private val baseUrl = BackendEndpoint.normalize(baseUrl)
    @Volatile private var activeCommand: HttpURLConnection? = null

    fun cancelCommand() {
        activeCommand?.disconnect()
    }

    fun warmAsync() {
        Thread {
            try {
                connection("/v1/warm", "GET", 2_000, 3_000).useResponse { connection ->
                    connection.responseCode
                }
            } catch (_: IOException) {
                Unit
            }
        }.apply {
            name = "Friday-warm"
            isDaemon = true
            start()
        }
    }

    fun registerInBackground() {
        Thread {
            try {
                accounts.flushLogoutBestEffort()
                ensureToken(force = false)
            } catch (_: Exception) {
                Unit
            }
        }.apply {
            name = "Friday-register"
            isDaemon = true
            start()
        }
    }

    @Throws(IOException::class)
    fun sendCommand(wavBytes: ByteArray, answerContext: String? = null, cancelled: () -> Boolean = { false }): CommandResult {
        require(VoicePayload.isValid(wavBytes)) { "invalid_wav" }
        if (answerContext != null && !AnswerConsent.isAllowed(appContext)) throw IOException("answers_disabled")
        checkCancellation(cancelled)
        if (!hasNetwork()) throw IOException("offline")
        var token = ensureToken(force = false)
        checkCancellation(cancelled)
        var result = sendAuthorized(wavBytes, token, answerContext, cancelled)
        if (result.response == VoiceCommandResponse.Error(VoiceCommandResponse.Reason.UNAUTHORIZED)) {
            checkCancellation(cancelled)
            token = ensureToken(force = true)
            checkCancellation(cancelled)
            result = sendAuthorized(wavBytes, token, answerContext, cancelled)
        }
        return result
    }

    private fun sendAuthorized(wavBytes: ByteArray, token: String, answerContext: String?, cancelled: () -> Boolean): CommandResult {
        val started = SystemClock.elapsedRealtime()
        val connection = connection("/v1/command", "POST", CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).apply {
            setRequestProperty("content-type", "audio/wav")
            setRequestProperty("authorization", "Bearer $token")
            setRequestProperty("x-now", OffsetDateTime.now().toString())
            // Production servers that predate media controls still understand the v4 base contract.
            setRequestProperty("x-friday-actions", PhoneCapabilityRegistry.BASE_ACTION_PROTOCOL_VERSION.toString())
            setRequestProperty("x-friday-media-controls", "1")
            if (AnswerConsent.isAllowed(appContext)) setRequestProperty("x-friday-answers", "2")
            if (answerContext != null) setRequestProperty("x-friday-answer-context", answerContext)
            setFixedLengthStreamingMode(wavBytes.size)
            doOutput = true
        }
        activeCommand = connection
        return try {
            connection.useResponse { value ->
                checkCancellation(cancelled)
                value.outputStream.use { it.write(wavBytes) }
                val code = value.responseCode
                val body = value.responseBody(code)
                val serverTiming = value.getHeaderField("Server-Timing")
                val response = VoiceProtocol.parseCommand(body, serverTiming)
                CommandResult(
                    response,
                    CommandTimings(SystemClock.elapsedRealtime() - started, VoiceProtocol.parseServerDuration(serverTiming)),
                )
            }
        } finally {
            if (activeCommand === connection) activeCommand = null
        }
    }

    private fun checkCancellation(cancelled: () -> Boolean) {
        if (cancelled()) throw IOException("cancelled")
    }

    fun reportAnswer(answer: InformationAnswer, reason: String): Boolean {
        require(reason in setOf("incorrect", "outdated", "offensive", "other"))
        if (!hasNetwork()) throw IOException("offline")
        val content = JSONObject().put("type", "answer").put("kind", answer.kind).put("text", answer.text)
            .put("answerId", answer.answerId).put("issuedAt", answer.issuedAt).put("reportToken", answer.reportToken)
            .put("sources", JSONArray().apply { answer.sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } })
        val bytes = JSONObject().put("answer", content).put("reason", reason).toString().toByteArray(Charsets.UTF_8)
        fun send(token: String): Int = connection("/v1/answers/report", "POST", CONNECT_TIMEOUT_MS, 8_000).useResponse {
            it.setRequestProperty("authorization", "Bearer $token")
            it.setRequestProperty("content-type", "application/json")
            it.setFixedLengthStreamingMode(bytes.size)
            it.doOutput = true
            it.outputStream.use { output -> output.write(bytes) }
            val code = it.responseCode
            it.responseBody(code)
            code
        }
        val code = send(ensureToken(force = false))
        return (if (code == 401) send(ensureToken(force = true)) else code) == 204
    }

    private fun ensureToken(force: Boolean): String = accounts.accessToken(forceRefresh = force)

    private fun hasNetwork(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun connection(path: String, method: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            useCaches = false
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("accept", "application/json")
            setRequestProperty("cache-control", "no-store")
        }

    private fun HttpURLConnection.responseBody(code: Int): String {
        val stream = if (code in 200..299) inputStream else errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(16_385)
            var size = 0
            while (size < buffer.size) {
                val count = reader.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            if (size == buffer.size) throw IOException("response_too_large")
            String(buffer, 0, size)
        }.orEmpty()
    }

    private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 28_000
    }
}
