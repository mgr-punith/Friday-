package com.example.assistant.debug.wake

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Downloads data only. The native detector still comes from the installed debug APK. */
internal class WakeModelInstaller(
    filesDir: File,
    private val expectedBytes: Int = WakeTrialModel.BYTES,
    private val expectedHash: String = WakeTrialModel.SHA256,
    private val trace: (String) -> Unit = {},
    private val connect: () -> HttpURLConnection = {
        URL(WakeTrialModel.DOWNLOAD_URL).openConnection() as HttpsURLConnection
    },
) {
    private val target = File(filesDir, WakeTrialModel.RELATIVE_PATH)
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    class InvalidModel : IOException()

    fun isInstalled(): Boolean = target.isFile && target.length() == expectedBytes.toLong() &&
        target.inputStream().use { input ->
            val bytes = ByteArray(expectedBytes + 1)
            var count = 0
            while (count < bytes.size) {
                checkCancelled()
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) throw IOException()
                count += read
            }
            count == expectedBytes && accepts(bytes.copyOf(count))
        }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    fun install(onInstalling: () -> Unit = {}, onProgress: (Int) -> Unit) {
        checkCancelled()
        trace("Opening connection")
        val download = connect()
        connection = download
        var temporary: File? = null
        try {
            checkCancelled()
            download.connectTimeout = 10_000
            download.readTimeout = 10_000
            download.instanceFollowRedirects = false
            download.useCaches = false
            download.setRequestProperty("Accept-Encoding", "identity")
            trace("Connecting")
            if (download.responseCode != HttpURLConnection.HTTP_OK) throw IOException()
            trace("Reading headers")
            val length = download.contentLengthLong
            if (length != -1L && length != expectedBytes.toLong()) throw InvalidModel()
            // Read at most one extra byte, even if the server omits Content-Length.
            val bytes = ByteArray(expectedBytes + 1)
            var count = 0
            var progress = -1
            trace("Reading model")
            download.inputStream.use { input ->
                while (count < bytes.size) {
                    checkCancelled()
                    val read = input.read(bytes, count, minOf(8_192, bytes.size - count))
                    if (read < 0) break
                    if (read == 0) throw IOException()
                    count += read
                    val percent = minOf(99, count * 100 / expectedBytes)
                    if (percent != progress) { progress = percent; onProgress(percent) }
                }
            }
            checkCancelled()
            onInstalling()
            trace("Verifying model")
            checkCancelled()
            if (count != expectedBytes || !accepts(bytes.copyOf(count))) throw InvalidModel()
            val directory = checkNotNull(target.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            temporary = File.createTempFile(".hey-friday-", ".part", directory)
            FileOutputStream(temporary).use { output ->
                output.write(bytes, 0, count)
                output.fd.sync()
            }
            checkCancelled()
            // Same-directory rename keeps an existing good model intact on failure.
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            trace("Model installed")
            onProgress(100)
        } finally {
            temporary?.delete()
            connection = null
            download.disconnect()
        }
    }

    private fun accepts(bytes: ByteArray): Boolean = bytes.size == expectedBytes &&
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expectedHash

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }
}
