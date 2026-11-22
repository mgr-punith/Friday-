package com.puneet.friday.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountSession(
    val accessToken: String, val accessExpiresAt: Long,
    val sessionId: String, val refreshToken: String, val refreshExpiresAt: Long,
    val logoutToken: String,
    val email: String, val displayName: String,
)

/** No plaintext credentials or account details enter SharedPreferences. */
class AccountStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("friday_account", Context.MODE_PRIVATE)
    private val associatedData = (context.packageName + ":account:v1").toByteArray()

    fun revision(): Long = synchronized(lock) { preferences.getLong("revision", 0) }
    fun session(): AccountSession? = synchronized(lock) {
        read("session")?.let { json ->
            try {
                AccountSession(json.getString("accessToken"), json.getLong("accessExpiresAt"), json.getString("sessionId"), json.getString("refreshToken"), json.getLong("refreshExpiresAt"), json.getString("logoutToken"), json.optString("email"), json.optString("displayName"))
                    .takeIf { it.refreshExpiresAt > System.currentTimeMillis() / 1000 }
            } catch (_: RuntimeException) { null }
        }
    }

    fun save(session: AccountSession, expectedRevision: Long): Boolean = synchronized(lock) {
        if (revision() != expectedRevision) return false
        write("session", encode(session))
    }

    fun beginLogin(): Long = synchronized(lock) {
        val next = revision() + 1
        check(preferences.edit().remove("pending_login").putLong("revision", next).commit())
        next
    }

    fun cancelLogin(expectedRevision: Long) = synchronized(lock) {
        if (revision() == expectedRevision) {
            preferences.edit().remove("pending_login").putLong("revision", expectedRevision + 1).commit()
        }
    }

    fun completeLogin(session: AccountSession, expectedRevision: Long, loginId: String): Boolean = synchronized(lock) {
        if (revision() != expectedRevision || pendingLogin()?.optString("loginId") != loginId) return false
        preferences.edit().putString("session", encrypt(encode(session))).remove("pending_login")
            .putLong("revision", expectedRevision + 1).commit()
    }

    fun clear() = synchronized(lock) {
        preferences.edit().remove("session").remove("pending_login").putLong("revision", revision() + 1).commit()
        Unit
    }

    fun clearDeletedSession(sessionId: String) = synchronized(lock) {
        if (read("session")?.optString("sessionId") == sessionId) clear()
    }

    fun pendingLogin(): JSONObject? = synchronized(lock) { read("pending_login") }
    fun savePendingLogin(value: JSONObject, expectedRevision: Long) = synchronized(lock) {
        revision() == expectedRevision && write("pending_login", value)
    }
    fun pendingLogout(): JSONObject? = synchronized(lock) { read("pending_logout") }
    fun savePendingLogout(value: JSONObject) = synchronized(lock) { write("pending_logout", value) }
    fun clearPendingLogout(expected: JSONObject) = synchronized(lock) {
        if (pendingLogout()?.toString() == expected.toString()) preferences.edit().remove("pending_logout").commit()
        Unit
    }

    private fun read(name: String): JSONObject? = try {
        val stored = preferences.getString(name, null)
        if (stored == null) null else {
            val parts = stored.split(":")
            if (parts.size != 2) null else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                cipher.updateAAD(associatedData)
                JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            }
        }
    } catch (_: Exception) { null }

    private fun encode(session: AccountSession) = JSONObject().put("accessToken", session.accessToken).put("accessExpiresAt", session.accessExpiresAt)
        .put("sessionId", session.sessionId).put("refreshToken", session.refreshToken).put("refreshExpiresAt", session.refreshExpiresAt)
        .put("logoutToken", session.logoutToken).put("email", session.email).put("displayName", session.displayName)

    private fun write(name: String, value: JSONObject): Boolean = preferences.edit().putString(name, encrypt(value)).commit()

    private fun encrypt(value: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val data = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "friday.account.v1"
        private val lock = Any()
        fun isSignedIn(context: Context): Boolean = AccountStore(context).session() != null
    }
}
