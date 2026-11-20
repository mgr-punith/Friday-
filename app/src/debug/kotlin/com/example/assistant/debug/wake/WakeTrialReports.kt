package com.example.assistant.debug.wake

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Bounded debug metadata only. No audio, transcripts, contact names or command text. */
internal object WakeTrialReports {
    private const val PATH = "wake-trial/run-history.json"

    fun json(state: WakeTrialSession.Snapshot): JSONObject = JSONObject()
        .put("createdAtMs", state.createdAtMs).put("generation", state.generation)
        .put("threshold", state.threshold.toDouble()).put("commandMode", state.commandMode)
        .put("phase", state.phase.name).put("detections", state.detections)
        .put("syntheticDetections", state.syntheticDetections)
        .put("openedCommands", state.openedCommands).put("detectionTimesMs", JSONArray(state.detectionTimesMs))
        .put("elapsedMs", state.elapsedMs).put("audioMs", state.audioMs).put("computeMs", state.computeMs)

    @Synchronized fun save(filesDir: File, state: WakeTrialSession.Snapshot) {
        if (state.createdAtMs == 0L) return
        val path = File(filesDir, PATH)
        path.parentFile?.mkdirs()
        val atomic = AtomicFile(path)
        var output: FileOutputStream? = null
        try {
            val previous = if (path.isFile && path.length() <= 131_072) {
                runCatching { JSONArray(atomic.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
            } else null
            val rows = JSONArray()
            if (previous != null) for (index in maxOf(0, previous.length() - 19) until previous.length()) {
                val row = previous.optJSONObject(index) ?: continue
                if (row.optLong("createdAtMs") != state.createdAtMs || row.optLong("generation") != state.generation) rows.put(row)
            }
            rows.put(json(state))
            output = atomic.startWrite()
            output.write(rows.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (_: Exception) {
            output?.let(atomic::failWrite)
        }
    }
}
