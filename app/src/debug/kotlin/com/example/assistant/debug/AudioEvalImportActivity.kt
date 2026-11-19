package com.example.assistant.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.io.IOException

class AudioEvalImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.sharedAudioUri()
        Thread({
            val imported = source != null && importAudio(source)
            runOnUiThread {
                if (!isDestroyed) {
                    Toast.makeText(
                        this,
                        if (imported) "Recording imported" else "Couldn't import recording",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
            }
        }, "FridayAudioImport").start()
    }

    private fun importAudio(source: Uri): Boolean {
        if (source.scheme != "content") return false
        val root = getExternalFilesDir("audio-eval") ?: return false
        val pending = File(root, "pending.audio")
        return try {
            if (!root.exists() && !root.mkdirs()) return false
            val destination = File(root, "recording.audio")
            val input = contentResolver.openInputStream(source) ?: return false
            input.use { stream ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) throw IOException("import_too_large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            pending.length() > 0 && pending.renameTo(destination)
        } catch (_: Exception) {
            false
        } finally {
            pending.delete()
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 5_000_000L
    }
}

@Suppress("DEPRECATION")
private fun Intent.sharedAudioUri(): Uri? {
    if (action != Intent.ACTION_SEND) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
