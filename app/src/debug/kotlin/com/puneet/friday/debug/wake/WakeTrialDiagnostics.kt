package com.puneet.friday.debug.wake

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ADB regression hook: fixed app-private PCM input, no microphone, upload or phone action. */
internal object WakeTrialDiagnostics {
    const val EXTRA = "debug_wake_replay"
    const val RUN_ID = "debug_wake_replay_id"
    const val INPUT = "wake-trial/replay.pcm"
    const val OUTPUT = "wake-trial/replay-result.json"

    @Synchronized fun run(assets: AssetManager, filesDir: File, runId: String, cancelled: () -> Boolean): String {
        val result = JSONObject().put("runId", runId.take(64))
        try {
            check(!WakeTrialService.session.snapshot().active) { "Stop the microphone test before replay." }
            val model = FridayKeywordModel.load(assets)
            val input = File(filesDir, INPUT)
            check(input.isFile && input.length() in 2..3_200_000L && input.length() % 2 == 0L) { "Test PCM missing or invalid" }
            val events = JSONArray()
            val bytes = ByteArray(320)
            val frame = ShortArray(160)
            var samples = 0L
            val before = System.nanoTime()
            FridayKeywordDetector(model, WakeTrialSession.DEFAULT_THRESHOLD).use { detector ->
                input.inputStream().buffered().use { stream ->
                    while (true) {
                        check(!cancelled()) { "Replay cancelled" }
                        var length = 0
                        while (length < bytes.size) {
                            val read = stream.read(bytes, length, bytes.size - length)
                            if (read == -1) break
                            length += read
                        }
                        if (length == 0) break
                        val count = length / 2
                        for (index in 0 until count) {
                            frame[index] = ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
                        }
                        samples += count
                        if (detector.process(frame, count)) events.put(samples * 1_000 / 16_000)
                    }
                }
            }
            result.put("ok", true).put("detector", "friday_keyword").put("detectionsMs", events)
                .put("audioMs", samples * 1_000 / 16_000).put("computeMs", (System.nanoTime() - before) / 1_000_000)
        } catch (error: Exception) {
            result.put("ok", false).put("error", error.message ?: "Replay failed")
        }
        val output = File(filesDir, OUTPUT)
        output.parentFile?.mkdirs()
        val pending = File(output.parentFile, "replay-result.pending")
        pending.writeText(result.toString())
        check(pending.renameTo(output))
        return if (result.optBoolean("ok")) "Replay complete: ${result.getJSONArray("detectionsMs").length()} detections"
        else result.optString("error", "Replay failed")
    }
}
