package com.puneet.friday.voice

import java.net.URI

data class AnswerSource(val title: String, val url: String)

data class InformationAnswer(
    val kind: String,
    val text: String,
    val sources: List<AnswerSource>,
    val answerId: String,
    val issuedAt: Long,
    val reportToken: String,
    val continuation: String? = null,
    val speechToken: String? = null,
)

/** Validates the answer separately from executable phone actions. */
object AnswerPayload {
    fun parse(fields: Map<String, Any?>): InformationAnswer? {
        if (fields.keys.any { it !in FIELDS } || fields["type"] != "answer") return null
        val kind = fields["kind"] as? String ?: return null
        if (kind !in setOf("knowledge", "information", "clarification", "unavailable")) return null
        val text = fields["text"] as? String ?: return null
        if (text.isBlank() || text.length > 600 || text.any(Char::isISOControl) || text.trim().split(Regex("\\s+")).size > 80) return null
        val entries = fields["sources"] as? List<*> ?: return null
        if (entries.size > 3) return null
        val sources = entries.map { entry ->
            val source = entry as? Map<*, *> ?: return null
            if (source.keys.any { it !in setOf("title", "url") }) return null
            val title = source["title"] as? String ?: return null
            val url = source["url"] as? String ?: return null
            if (title.isBlank() || title.length > 120 || title.any(Char::isISOControl) || !isPublicSource(url)) return null
            AnswerSource(title, url)
        }.distinctBy { it.url }
        if (kind == "information" && sources.isEmpty() || kind != "information" && sources.isNotEmpty()) return null
        val id = fields["answerId"] as? String ?: return null
        val token = fields["reportToken"] as? String ?: return null
        val at = fields["issuedAt"] as? Number ?: return null
        if (!Regex("[0-9a-f-]{36}").matches(id) || !Regex("[A-Za-z0-9_-]{43}").matches(token) ||
            at.toDouble() != at.toLong().toDouble() || at.toLong() < 0) return null
        val continuation = if (fields.containsKey("continuation")) {
            val value = fields["continuation"] as? String ?: return null
            if (kind != "clarification" || value.length !in 64..4_096 || !Regex("[A-Za-z0-9_-]+").matches(value)) return null
            value
        } else null
        val speechToken = if (fields.containsKey("speechToken")) {
            val value = fields["speechToken"] as? String ?: return null
            if (!Regex("[A-Za-z0-9_-]{43}").matches(value)) return null
            value
        } else null
        return InformationAnswer(kind, text.trim(), sources, id, at.toLong(), token, continuation, speechToken)
    }

    fun isPublicSource(url: String): Boolean {
        if (url.length > 1_024 || url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
            uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
                '.' in host && ':' !in host && !Regex("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}").matches(host) &&
                !Regex("(?:^|.*\\.)(?:localhost|local|internal|test|invalid)").matches(host)
        } catch (_: Exception) { false }
    }

    private val FIELDS = setOf("type", "kind", "text", "sources", "answerId", "issuedAt", "reportToken", "continuation", "speechToken")
}
