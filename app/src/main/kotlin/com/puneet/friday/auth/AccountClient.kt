package com.puneet.friday.auth

import android.content.Context
import android.util.Base64
import com.puneet.friday.BuildConfig
import com.puneet.friday.voice.BackendEndpoint
import com.puneet.friday.voice.InstallIdentity
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

class SignInRequired : IOException("sign_in_required")

class AccountClient(context: Context, baseUrl: String = BuildConfig.BACKEND_URL) {
    private val store = AccountStore(context)
    private val installId = InstallIdentity(context).installId()
    private val base = BackendEndpoint.normalize(baseUrl)

    fun begin(revision: Long): JSONObject {
        flushLogoutBestEffort()
        if (store.revision() != revision) throw IOException("login_cancelled")
        val verifier = ByteArray(32).also(SecureRandom()::nextBytes).encode()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).encode()
        val result = request("/v1/auth/challenge", JSONObject().put("installId", installId).put("codeChallenge", challenge))
        if (!AuthPolicy.isOpaque(result.optString("loginId")) || !AuthPolicy.isOpaque(result.optString("nonce")) || !AuthPolicy.isGoogleClientId(result.optString("googleWebClientId"))) throw IOException("login_unavailable")
        result.put("codeVerifier", verifier).put("expiresAt", System.currentTimeMillis() / 1000 + 300).put("revision", revision)
        if (!store.savePendingLogin(result, revision)) throw IOException("login_cancelled")
        return result
    }

    fun finish(login: JSONObject, googleIdToken: String? = null) {
        if (login.optLong("expiresAt") <= System.currentTimeMillis() / 1000) throw IOException("login_expired")
        val body = JSONObject().put("installId", installId).put("loginId", login.getString("loginId")).put("codeVerifier", login.getString("codeVerifier"))
        if (googleIdToken != null) body.put("idToken", googleIdToken)
        val result = request(if (googleIdToken == null) "/v1/auth/browser" else "/v1/auth/google", body)
        val session = parseSession(result)
        if (!store.completeLogin(session, login.getLong("revision"), login.getString("loginId"))) {
            try { request("/v1/auth/logout", logoutCredentials(session)) } catch (_: Exception) { Unit }
            throw IOException("login_cancelled")
        }
        // The previous install-only credential must never be used as an account fallback.
    }

    fun accessToken(forceRefresh: Boolean = false): String = synchronized(refreshLock) {
        val revision = store.revision()
        val session = store.session() ?: throw SignInRequired()
        if (!forceRefresh && AuthPolicy.accessUsable(session.accessExpiresAt, System.currentTimeMillis() / 1000)) return session.accessToken
        val result = try {
            request("/v1/auth/refresh", credentials(session))
        } catch (error: SignInRequired) {
            if (store.revision() == revision) store.clear()
            throw error
        }
        val next = parseSession(result)
        if (!store.save(next, revision)) throw SignInRequired()
        next.accessToken
    }

    fun signOutLocally() {
        // Do not wait for an in-flight network refresh before clearing this device.
        try { store.session()?.let { store.savePendingLogout(logoutCredentials(it)) } }
        finally { store.clear() }
    }

    fun deleteAccount(expectedSessionId: String) = synchronized(refreshLock) {
        val session = store.session()?.takeIf { it.sessionId == expectedSessionId } ?: throw SignInRequired()
        val result = request("/v1/auth/delete", credentials(session))
        if (result.opt("deleted") != true) throw IOException("invalid_account_response")
        store.clearDeletedSession(expectedSessionId)
    }

    fun flushLogoutBestEffort() { try { flushLogout() } catch (_: Exception) { Unit } }

    private fun flushLogout() = synchronized(refreshLock) {
        val pending = store.pendingLogout() ?: return
        request("/v1/auth/logout", pending)
        store.clearPendingLogout(pending)
    }

    private fun credentials(session: AccountSession): JSONObject = JSONObject().put("installId", installId).put("sessionId", session.sessionId).put("refreshToken", session.refreshToken)
    private fun logoutCredentials(session: AccountSession): JSONObject = JSONObject().put("installId", installId).put("sessionId", session.sessionId).put("logoutToken", session.logoutToken)

    private fun parseSession(value: JSONObject): AccountSession {
        val now = System.currentTimeMillis() / 1000
        val access = value.optString("accessToken")
        val session = value.optString("sessionId")
        val refresh = value.optString("refreshToken")
        val logout = value.optString("logoutToken")
        val expires = value.optLong("expiresIn")
        val refreshExpires = value.optLong("refreshExpiresIn")
        if (access.length !in 20..4096 || !AuthPolicy.isOpaque(session) || !AuthPolicy.isOpaque(refresh) || !AuthPolicy.isOpaque(logout) || expires !in 60..3600 || refreshExpires !in 3600..(31L * 86400)) throw IOException("invalid_account_response")
        return AccountSession(access, now + expires, session, refresh, now + refreshExpires, logout, value.optString("email").take(254), value.optString("displayName").take(100))
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; instanceFollowRedirects = false; useCaches = false
            connectTimeout = 5_000; readTimeout = 10_000
            setRequestProperty("content-type", "application/json"); setRequestProperty("accept", "application/json")
            doOutput = true
        }
        return try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status == 401) throw SignInRequired()
            if (status !in 200..299) throw IOException(if (status == 429) "login_busy" else "login_unavailable")
            if (status == 204) JSONObject() else {
                val text = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(24_577)
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count > 24_576) throw IOException("invalid_account_response")
                    String(buffer, 0, count)
                }
                try { JSONObject(text) } catch (_: RuntimeException) { throw IOException("invalid_account_response") }
            }
        } finally { connection.disconnect() }
    }

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    companion object { private val refreshLock = Any() }
}
