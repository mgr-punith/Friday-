package com.example.assistant.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.example.assistant.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

class FridayBackendClient(
    context: Context,
    baseUrl: String = BuildConfig.BACKEND_URL,
) {
    private val appContext = context.applicationContext
    private val identity = InstallIdentity(appContext)
    private val baseUrl = BackendEndpoint.normalize(baseUrl)

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
                ensureToken(force = false)
            } catch (_: IOException) {
                Unit
            }
        }.apply {
            name = "Friday-register"
            isDaemon = true
            start()
        }
    }

    @Throws(IOException::class)
    fun sendCommand(wavBytes: ByteArray): CommandResult {
        require(VoicePayload.isValid(wavBytes)) { "invalid_wav" }
        if (!hasNetwork()) throw IOException("offline")
        var token = ensureToken(force = false)
        var result = sendAuthorized(wavBytes, token)
        if (result.response == VoiceCommandResponse.Error(VoiceCommandResponse.Reason.UNAUTHORIZED)) {
            identity.clearToken()
            token = ensureToken(force = true)
            result = sendAuthorized(wavBytes, token)
        }
        return result
    }

    private fun sendAuthorized(wavBytes: ByteArray, token: String): CommandResult {
        val started = SystemClock.elapsedRealtime()
        val connection = connection("/v1/command", "POST", CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).apply {
            setRequestProperty("content-type", "audio/wav")
            setRequestProperty("authorization", "Bearer $token")
            setRequestProperty("x-now", OffsetDateTime.now().toString())
            setFixedLengthStreamingMode(wavBytes.size)
            doOutput = true
        }
        return connection.useResponse { value ->
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
    }

    @Synchronized
    private fun ensureToken(force: Boolean): String {
        val now = System.currentTimeMillis() / 1_000L
        if (!force) identity.token(now)?.let { return it }
        val body = JSONObject().put("installId", identity.installId()).toString().toByteArray(Charsets.UTF_8)
        val connection = connection("/v1/register", "POST", CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).apply {
            setRequestProperty("content-type", "application/json")
            setFixedLengthStreamingMode(body.size)
            doOutput = true
        }
        return connection.useResponse { value ->
            value.outputStream.use { it.write(body) }
            if (value.responseCode != HttpURLConnection.HTTP_OK) throw IOException("registration_failed")
            val response = JSONObject(value.responseBody(value.responseCode))
            val token = response.optString("token")
            val expiresIn = response.optLong("expiresIn", 0L)
            if (token.length < 20 || expiresIn <= 0L) throw IOException("registration_invalid")
            identity.saveToken(token, expiresIn, now)
            token
        }
    }

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
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 12_000
    }
}
