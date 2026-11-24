package com.puneet.friday.debug.wake

import org.json.JSONArray
import org.json.JSONObject

/** Short, shell-started diagnostic. Keeps counts and score margins in memory only. */
internal class WakeScoreProbe(private val startedMs: Long) {
    private class Bucket {
        var windows = 0
        var scored = 0
        var bestMargin = Float.NEGATIVE_INFINITY
        var longestStreak = 0
    }

    private val buckets = Array(60) { Bucket() }

    @Synchronized
    fun record(nowMs: Long, margin: Float, streak: Int) {
        val bucket = buckets.getOrNull(((nowMs - startedMs) / 1_000).toInt()) ?: return
        bucket.windows++
        if (margin.isFinite()) {
            bucket.scored++
            bucket.bestMargin = maxOf(bucket.bestMargin, margin)
            bucket.longestStreak = maxOf(bucket.longestStreak, streak)
        }
    }

    @Synchronized
    fun snapshot(nowMs: Long): JSONObject? {
        val elapsedMs = nowMs - startedMs
        if (elapsedMs > 180_000) return null
        val result = JSONArray()
        for ((second, bucket) in buckets.withIndex()) {
            if (second * 1_000L > elapsedMs) break
            result.put(JSONObject()
                .put("second", second)
                .put("windows", bucket.windows)
                .put("scored", bucket.scored)
                .put("bestLogitMargin", if (bucket.scored > 0) bucket.bestMargin else JSONObject.NULL)
                .put("longestStreak", bucket.longestStreak))
        }
        return JSONObject().put("elapsedMs", elapsedMs).put("seconds", result)
    }
}
