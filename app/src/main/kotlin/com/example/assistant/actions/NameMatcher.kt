package com.example.assistant.actions

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

sealed interface MatchResult {
    data class Matched(val index: Int) : MatchResult
    data class Ambiguous(val indices: List<Int>) : MatchResult
    data object NotFound : MatchResult
}

/** Conservative matching shared by contacts and launcher labels. Data stays local. */
object NameMatcher {
    private const val STRONG_MATCH = 0.86
    private const val AMBIGUITY_MARGIN = 0.06
    private const val COMPETING_MATCH = 0.80
    private const val MAX_NAME_LENGTH = 120
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return separators.replace(
            marks.replace(decomposed, "").lowercase(Locale.ROOT)
                .replace("'", "").replace("’", ""),
            " ",
        ).trim()
    }

    fun match(query: String, candidates: List<String>): MatchResult {
        if (query.length > MAX_NAME_LENGTH) return MatchResult.NotFound
        val name = normalize(query)
        if (name.isEmpty()) return MatchResult.NotFound
        val normalizedCandidates = candidates.map {
            if (it.length <= MAX_NAME_LENGTH) normalize(it) else ""
        }
        val exact = normalizedCandidates.indices.filter { normalizedCandidates[it] == name }
        if (exact.isNotEmpty()) return resultFor(exact)

        // A short name must match exactly. One-letter speech errors should not dial.
        if (name.count(Char::isLetterOrDigit) < 3) return MatchResult.NotFound
        val scores = normalizedCandidates.map { score(name, it) }
        val best = scores.maxOrNull() ?: return MatchResult.NotFound
        if (best < STRONG_MATCH) return MatchResult.NotFound

        val close = scores.indices.filter {
            scores[it] >= COMPETING_MATCH && best - scores[it] <= AMBIGUITY_MARGIN
        }
        return resultFor(close)
    }

    private fun resultFor(indices: List<Int>): MatchResult = if (indices.size == 1) {
        MatchResult.Matched(indices.single())
    } else {
        MatchResult.Ambiguous(indices)
    }

    private fun score(query: String, candidate: String): Double {
        if (candidate.isEmpty() || candidate.length > MAX_NAME_LENGTH) return 0.0
        // Whole tokens allow "John" to find "John Smith", but not "Johnny".
        if (" $candidate ".contains(" $query ")) return 0.95
        if (query.length >= 6 && candidate.startsWith(query) &&
            query.length.toDouble() / candidate.length >= 0.75
        ) return 0.93

        var best = similarity(query, candidate)
        if (' ' !in query && query.length >= 6) {
            for (token in candidate.split(' ')) {
                // A typo in a long first/last name is allowed only at a strong score.
                best = max(best, similarity(query, token) - 0.015)
            }
        }
        return best
    }

    private fun similarity(left: String, right: String): Double {
        val longest = max(left.length, right.length)
        if (longest < 6 || min(left.length, right.length) < 5) return 0.0
        if (kotlin.math.abs(left.length - right.length) > 2) return 0.0
        return 1.0 - editDistance(left, right).toDouble() / longest
    }

    /** Optimal-string-alignment distance also handles a single adjacent transposition. */
    private fun editDistance(left: String, right: String): Int {
        var previousPrevious = IntArray(right.length + 1)
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitution = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitution,
                )
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    current[j] = min(current[j], previousPrevious[j - 2] + 1)
                }
            }
            val recycled = previousPrevious
            previousPrevious = previous
            previous = current
            current = recycled
        }
        return previous[right.length]
    }
}
