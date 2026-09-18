package com.azlegend.wear.data

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DownloadState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** Progress of one album download as observed from the UI and the notification. */
data class DownloadProgress(
    val albumId: String,
    val albumTitle: String,
    val state: DownloadState,
    val trackIndex: Int = 0,
    val trackCount: Int = 0,
    val trackPercent: Int = 0,
    val trackTitle: String = "",
    /** "Wi-Fi", "LTE" or "phone link" once the transfer has started. */
    val network: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING

    /** Whole-album progress in 0..100. */
    val percent: Int
        get() = if (trackCount <= 0) 0 else ((trackIndex * 100 + trackPercent) / trackCount).coerceIn(0, 100)
}

/**
 * Downloads albums one at a time. The transfer itself runs here (app scope); [DownloadService] is
 * the dataSync foreground service that keeps the process and the radio alive while [progress]
 * reports something active. Starting the service directly, rather than through WorkManager,
 * avoids the job runtime quota that Wear OS 6/7 apply to long-running workers.
 *
 * [onAlbumFilesChanged] fires (on a background thread) after each track lands, so the player can
 * swap queued streaming items for the local files.
 */
class Downloads(
    context: Context,
    private val library: LibraryRepository,
    private val store: MusicStore,
    private val onAlbumFilesChanged: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var runner: Job? = null
    private var currentAlbumId: String? = null
    private var currentJob: Job? = null

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    fun enqueueAlbum(albumId: String) {
        val title = titleOf(albumId)
        synchronized(lock) {
            if (_progress.value[albumId]?.isActive == true) return
            queue.addLast(albumId)
            publish(DownloadProgress(albumId, title, DownloadState.QUEUED))
            if (runner == null) runner = scope.launch { drain() }
        }
        DownloadService.start(appContext)
    }

    fun cancelAlbum(albumId: String) {
        synchronized(lock) {
            if (queue.remove(albumId)) {
                publish(DownloadProgress(albumId, titleOf(albumId), DownloadState.CANCELLED))
                return
            }
            if (currentAlbumId == albumId) {
                // Publish here too: a lazily started job that is cancelled before it runs never
                // reaches downloadAlbum's own CANCELLED publish.
                publish(DownloadProgress(albumId, titleOf(albumId), DownloadState.CANCELLED))
                currentJob?.cancel()
            }
        }
    }

    fun cancelAll() {
        val ids = synchronized(lock) { queue.toList() + listOfNotNull(currentAlbumId) }
        ids.forEach(::cancelAlbum)
    }

    private suspend fun drain() {
        while (true) {
            // Dequeue and register the job under one lock so a cancel can never fall between them.
            val job = synchronized(lock) {
                val albumId = queue.removeFirstOrNull()
                if (albumId == null) {
                    runner = null
                    return
                }
                scope.launch(start = CoroutineStart.LAZY) { downloadAlbum(albumId) }.also {
                    currentAlbumId = albumId
                    currentJob = it
                }
            }
            job.start()
            job.join()
            synchronized(lock) {
                currentAlbumId = null
                currentJob = null
            }
        }
    }

    private suspend fun downloadAlbum(albumId: String) {
        val title = titleOf(albumId)
        val highBandwidth = HighBandwidthNetwork(appContext)
        try {
            library.initialize()
            val album = library.album(albumId) ?: throw IOException("Unknown album $albumId")
            val pending = album.tracks.filter { !it.isDownloaded && it.remoteUrl != null }
            if (pending.isEmpty()) {
                publish(DownloadProgress(albumId, title, DownloadState.SUCCEEDED, album.tracks.size, album.tracks.size, 100))
                return
            }
            publish(DownloadProgress(albumId, title, DownloadState.RUNNING, 0, pending.size, 0, pending.first().title))

            var transport = highBandwidth.acquire()
            var label = highBandwidth.describe(transport)
            pending.forEachIndexed { index, track ->
                val dest = store.trackFile(albumId, track.fileName)
                val part = store.partFile(albumId, track.fileName)
                var lastPercent = -1
                publish(DownloadProgress(albumId, title, DownloadState.RUNNING, index, pending.size, 0, track.title, label))
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        Downloader(transport).download(requireNotNull(track.remoteUrl), dest, part) { done, total ->
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (pct != lastPercent) {
                                lastPercent = pct
                                publish(DownloadProgress(albumId, title, DownloadState.RUNNING, index, pending.size, pct, track.title, label))
                            }
                        }
                        break
                    } catch (e: IOException) {
                        // The site ignores Range requests, so a failed attempt starts the file over.
                        part.delete()
                        if (attempt >= MAX_ATTEMPTS || isDiskFull(e)) throw e
                        delay(RETRY_DELAY_MS * attempt)
                        // The network we were pinned to may be gone (Wi-Fi drop); ask for one again.
                        transport = highBandwidth.acquire()
                        label = highBandwidth.describe(transport)
                    }
                }
                library.rebuild()
                onAlbumFilesChanged(albumId)
            }
            publish(DownloadProgress(albumId, title, DownloadState.SUCCEEDED, pending.size, pending.size, 100, network = label))
        } catch (e: CancellationException) {
            cleanupParts(albumId)
            publish(DownloadProgress(albumId, title, DownloadState.CANCELLED))
            throw e
        } catch (e: Exception) {
            cleanupParts(albumId)
            publish(DownloadProgress(albumId, title, DownloadState.FAILED, error = e.message ?: e.javaClass.simpleName))
        } finally {
            highBandwidth.release()
        }
    }

    private fun isDiskFull(e: IOException): Boolean {
        val message = e.message ?: return false
        return message.contains("ENOSPC", ignoreCase = true) || message.contains("No space left", ignoreCase = true)
    }

    private fun cleanupParts(albumId: String) {
        runCatching {
            store.albumDir(albumId).listFiles { f: File -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    private fun titleOf(albumId: String): String = library.album(albumId)?.title ?: albumId

    private fun publish(progress: DownloadProgress) {
        _progress.update { it + (progress.albumId to progress) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}
