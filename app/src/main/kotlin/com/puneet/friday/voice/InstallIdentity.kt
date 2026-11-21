package com.puneet.friday.voice

import android.content.Context
import java.util.UUID

class InstallIdentity(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun installId(): String {
        preferences.getString(INSTALL_ID, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        preferences.edit().putString(INSTALL_ID, value).apply()
        return value
    }

    fun token(nowEpochSeconds: Long): String? {
        val token = preferences.getString(TOKEN, null) ?: return null
        val expiresAt = preferences.getLong(TOKEN_EXPIRES_AT, 0L)
        return token.takeIf { TokenPolicy.isUsable(expiresAt, nowEpochSeconds) }
    }

    fun saveToken(token: String, expiresInSeconds: Long, nowEpochSeconds: Long) {
        val expiresAt = TokenPolicy.expiresAt(nowEpochSeconds, expiresInSeconds)
        preferences.edit()
            .putString(TOKEN, token)
            .putLong(TOKEN_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun clearToken() {
        preferences.edit().remove(TOKEN).remove(TOKEN_EXPIRES_AT).apply()
    }

    private companion object {
        const val PREFERENCES = "friday_network"
        const val INSTALL_ID = "install_id"
        const val TOKEN = "token"
        const val TOKEN_EXPIRES_AT = "token_expires_at"
    }
}

object TokenPolicy {
    private const val MIN_LIFETIME_SECONDS = 60L
    private const val MAX_LIFETIME_SECONDS = 31L * 24L * 60L * 60L
    private const val REFRESH_MARGIN_SECONDS = 24L * 60L * 60L

    fun expiresAt(nowEpochSeconds: Long, expiresInSeconds: Long): Long =
        nowEpochSeconds + expiresInSeconds.coerceIn(MIN_LIFETIME_SECONDS, MAX_LIFETIME_SECONDS)

    fun isUsable(expiresAtEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds - nowEpochSeconds > REFRESH_MARGIN_SECONDS
}
