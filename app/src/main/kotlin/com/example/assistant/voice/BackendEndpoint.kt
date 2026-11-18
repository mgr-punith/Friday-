package com.example.assistant.voice

import java.net.URI

object BackendEndpoint {
    fun normalize(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("invalid_backend_url")
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "invalid_backend_url" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "invalid_backend_url" }
        require(uri.path.isNullOrEmpty()) { "invalid_backend_url" }
        return trimmed
    }
}
