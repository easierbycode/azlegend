package com.azlegend.wear.data

import android.net.Network
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Resumable HTTP downloader. Writes to `<dest>.part`, resumes with a Range header when a partial
 * file exists, verifies the length when the server reports one, then atomically renames into place.
 * When [network] is given (e.g. a Wi-Fi network obtained from ConnectivityManager.requestNetwork)
 * the connection is opened on that network instead of the default one.
 */
class Downloader(private val network: Network? = null) {

    fun interface ProgressListener {
        /** [total] is -1 when the server did not report a length. */
        fun onProgress(bytesDone: Long, total: Long)
    }

    suspend fun download(url: String, dest: File, part: File, listener: ProgressListener) {
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            var attempt = 0
            while (true) {
                attempt++
                try {
                    downloadOnce(url, dest, part, listener)
                    return@withContext
                } catch (e: RangeNotSatisfiable) {
                    // Stale .part (server file changed, or it is already complete): start over once.
                    part.delete()
                    if (attempt >= 2) throw IOException("Server rejected resume for $url", e)
                }
            }
        }
    }

    private class RangeNotSatisfiable : IOException("416 Range Not Satisfiable")

    private suspend fun downloadOnce(url: String, dest: File, part: File, listener: ProgressListener) = coroutineScope {
        var existing = if (part.isFile) part.length() else 0L
        val conn = open(url)
        // A cancelled coroutine cannot interrupt a blocking socket read, but closing the connection
        // can: this child is cancelled together with the download and closes it on the way out.
        val watchdog = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            conn.connect()
            val append = when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> { existing = 0L; false }
                416 -> throw RangeNotSatisfiable()
                else -> throw IOException("HTTP $code for $url")
            }
            val remaining = conn.contentLengthLong
            val total = if (remaining >= 0) existing + remaining else -1L
            var done = existing
            var lastReported = -1L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReported >= REPORT_EVERY_BYTES) {
                            lastReported = done
                            listener.onProgress(done, total)
                        }
                    }
                    out.fd.sync()
                }
            }
            if (total >= 0 && part.length() != total) {
                throw IOException("Incomplete download: ${part.length()} of $total bytes for $url")
            }
            if (dest.exists() && !dest.delete()) throw IOException("Could not replace ${dest.name}")
            if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} into place")
            listener.onProgress(dest.length(), dest.length())
        } finally {
            watchdog.cancel()
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val target = URL(url)
        val connection = network?.openConnection(target) ?: target.openConnection()
        return connection as? HttpURLConnection ?: throw IOException("Unsupported URL: $url")
    }

    private companion object {
        const val REPORT_EVERY_BYTES = 128L * 1024L
    }
}
