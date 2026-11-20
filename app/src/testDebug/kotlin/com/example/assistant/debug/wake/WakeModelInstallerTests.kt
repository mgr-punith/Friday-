package com.example.assistant.debug.wake

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** Exercises the real bounded download and atomic installer with fake HTTPS responses. */
internal object WakeModelInstallerTests {
    fun run(): Int {
        var checks = 0
        fun expect(value: Boolean, description: String) {
            check(value) { description }
            checks++
        }
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val root = Files.createTempDirectory("friday-model-test").toFile()
        val target = File(root, WakeTrialModel.RELATIVE_PATH)
        fun installer(response: Response) = WakeModelInstaller(root, bytes.size, hash) { response }
        fun noPartial() = target.parentFile?.listFiles()?.none { it.name.endsWith(".part") } != false
        try {
            expect(!installer(Response(bytes)).isInstalled(), "A new phone needs the optional download")
            val success = Response(bytes)
            val progress = mutableListOf<Int>()
            var installing = false
            val install = installer(success)
            install.install(onInstalling = {
                installing = true
                expect(!target.exists(), "Installing starts before activating the model")
                expect(progress.none { it == 100 }, "Download progress never claims success before verification")
            }) { progress += it }
            expect(installing && progress.last() == 100 && progress.zipWithNext().all { it.first < it.second },
                "Progress advances through download, installation and completion")
            expect(target.readBytes().contentEquals(bytes) && install.isInstalled(), "Activate exactly the verified download")
            expect(success.disconnected && success.streamClosed, "Success closes the response and connection")
            expect(!success.instanceFollowRedirects && !success.useCaches && success.connectTimeout == 10_000 &&
                success.readTimeout == 10_000, "Keep redirects disabled and network waits bounded")
            expect(success.getRequestProperty("Accept-Encoding") == "identity", "Verify the uncompressed pinned bytes")
            expect(noPartial(), "Successful installation leaves no temporary model")
            val offlineReopen = WakeModelInstaller(root, bytes.size, hash) {
                error("Reopening setup must not open a model download connection")
            }
            expect(offlineReopen.isInstalled(), "Reuse the installed model on a later setup screen without a network request")

            val invalidResponses = listOf(
                Response(bytes.copyOf(bytes.size - 1), length = -1),
                Response(bytes + byteArrayOf(1), length = -1),
                Response(bytes.copyOf().apply { this[100] = (this[100] + 1).toByte() }),
                Response(bytes, length = bytes.size + 1L),
                Response(bytes, length = bytes.size - 1L),
            )
            for ((index, response) in invalidResponses.withIndex()) {
                val result = runCatching { installer(response).install {} }
                expect(result.exceptionOrNull() is WakeModelInstaller.InvalidModel, "Reject invalid model case $index")
                expect(target.readBytes().contentEquals(bytes), "An invalid update preserves the installed good model")
                expect(response.disconnected && noPartial(), "Invalid downloads release resources and leave no partial model")
            }
            val unbounded = Response(ByteArray(1_000_000), length = -1)
            expect(runCatching { installer(unbounded).install {} }.isFailure && unbounded.readBytes == bytes.size + 1,
                "A response with no declared length cannot exceed the one-byte oversize check")
            for (code in listOf(301, 302, 404, 500)) {
                val response = Response(bytes, code = code)
                expect(runCatching { installer(response).install {} }.exceptionOrNull() is IOException,
                    "Reject redirects and unsuccessful HTTP status $code")
                expect(response.readBytes == 0 && response.disconnected, "HTTP failures never read or install a body")
            }
            val chunked = Response(bytes, length = -1)
            installer(chunked).install {}
            expect(target.readBytes().contentEquals(bytes), "An omitted Content-Length still needs exact size and hash")
            val broken = Response(bytes, failAfter = 8_192)
            expect(runCatching { installer(broken).install {} }.exceptionOrNull() is IOException,
                "Handle a connection lost partway through the download")
            expect(broken.disconnected && broken.streamClosed && noPartial() && target.readBytes().contentEquals(bytes),
                "A dropped connection preserves the old file and cleans up")
            val timedOut = Response(bytes, timeout = true)
            expect(runCatching { installer(timedOut).install {} }.exceptionOrNull() is SocketTimeoutException,
                "A connection timeout reaches the handled retry path")
            expect(timedOut.disconnected && noPartial() && target.readBytes().contentEquals(bytes),
                "A timed-out request leaves the installed model intact")

            val cancelled = Response(bytes)
            val cancelInstall = installer(cancelled)
            expect(runCatching { cancelInstall.install { cancelInstall.cancel() } }.exceptionOrNull() is InterruptedIOException,
                "Cancel during network transfer prevents installation")
            expect(cancelled.disconnected && cancelled.streamClosed && target.readBytes().contentEquals(bytes) && noPartial(),
                "Cancellation closes network resources and preserves the old model")
            val beforeCommit = installer(Response(bytes))
            expect(runCatching { beforeCommit.install(onInstalling = { beforeCommit.cancel() }) {} }
                .exceptionOrNull() is InterruptedIOException, "Cancel during verification prevents activation")
            var connected = false
            val beforeStart = WakeModelInstaller(root, bytes.size, hash) { connected = true; Response(bytes) }
            beforeStart.cancel()
            expect(runCatching { beforeStart.install {} }.exceptionOrNull() is InterruptedIOException && !connected,
                "Cancel before the worker starts opens no network connection")
            val interrupted = installer(Response(bytes))
            Thread.currentThread().interrupt()
            try {
                expect(runCatching { interrupted.install {} }.exceptionOrNull() is InterruptedIOException,
                    "Activity shutdown interrupts the installer")
            } finally { Thread.interrupted() }
            expect(target.readBytes().contentEquals(bytes), "No cancellation corrupts the installed version")

            target.writeBytes(bytes.copyOf().apply { this[0] = (this[0] + 1).toByte() })
            expect(!installer(Response(bytes)).isInstalled(), "Detect corruption at setup, before microphone consent")
            target.writeBytes(bytes.copyOf(bytes.size - 1))
            expect(!installer(Response(bytes)).isInstalled(), "A partial file is not ready")
            target.delete()
            val badFirst = Response(ByteArray(bytes.size))
            expect(runCatching { installer(badFirst).install {} }.isFailure && !target.exists() && noPartial(),
                "A failed first download leaves the new phone ready to retry")
            installer(Response(bytes)).install {}
            expect(installer(Response(bytes)).isInstalled(), "Retry after failure installs a verified model")

            target.delete()
            target.mkdir()
            File(target, "block").writeText("Keep this directory")
            expect(runCatching { installer(Response(bytes)).install {} }.exceptionOrNull() is IOException,
                "Handle an atomic replacement failure")
            expect(File(target, "block").exists() && noPartial(), "A failed commit leaves existing data and no temporary file")
        } finally { root.deleteRecursively() }
        return checks
    }

    private class Response(
        private val body: ByteArray,
        private val length: Long = body.size.toLong(),
        private val code: Int = HTTP_OK,
        private val failAfter: Int? = null,
        private val timeout: Boolean = false,
    ) : HttpURLConnection(URL("https://example.invalid/model")) {
        var disconnected = false
        var streamClosed = false
        var readBytes = 0
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode(): Int {
            if (timeout) throw SocketTimeoutException("Test connection timeout")
            return code
        }
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(body) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (failAfter != null && readBytes >= failAfter) throw IOException("Test connection lost")
                return super.read(buffer, offset, length).also { if (it > 0) readBytes += it }
            }
            override fun close() { streamClosed = true; super.close() }
        }
    }
}
