package com.puneet.friday.auth

object AuthPolicy {
    fun isOpaque(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{43}"))
    fun isGoogleClientId(value: String): Boolean = value.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))
    fun accessUsable(expiresAt: Long, now: Long): Boolean = expiresAt - now > 60
    fun acceptsCallback(scheme: String?, host: String?, loginId: String?, expectedId: String?): Boolean =
        scheme == "com.puneet.friday" && host == "signin" && loginId != null && isOpaque(loginId) && loginId == expectedId
}
