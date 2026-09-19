package com.azlegend.wear.data

import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val staleAlbums: List<String> = emptyList()) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** A resume refreshes the catalog at most this often; the Sync button remains the "right now" path. */
const val AUTO_REFRESH_INTERVAL_MS = 5 * 60_000L

/** After a silent attempt failed, wait this long before waking the radio again. */
const val AUTO_RETRY_INTERVAL_MS = 60_000L

/** Pure so it can be unit-tested without a Context. Nulls mean "has not happened yet". */
internal fun shouldAutoRefresh(
    nowMs: Long,
    lastSyncAtMs: Long?,
    lastAttemptAtMs: Long?,
    syncing: Boolean,
    online: Boolean,
): Boolean = when {
    syncing || !online -> false
    lastSyncAtMs != null && nowMs - lastSyncAtMs < AUTO_REFRESH_INTERVAL_MS -> false
    lastAttemptAtMs != null && nowMs - lastAttemptAtMs < AUTO_RETRY_INTERVAL_MS -> false
    else -> true
}

/**
 * Single source of truth for what the watch can play: catalog albums (with their download state)
 * followed by pseudo-albums built from sideloaded files. [rebuild] rescans the file system, so call
 * it after downloads finish or files are deleted.
 */
class LibraryRepository(
    private val store: MusicStore,
    private val catalogRepository: CatalogRepository,
    /** Injected so the repository keeps no Android dependency of its own in tests. */
    private val isOnline: () -> Boolean = { true },
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val mutex = Mutex()
    private val rebuildMutex = Mutex()
    private var catalog: CatalogRepository.Catalog? = null

    /** Refreshes outlive the screen that started them, so navigating away cannot strand [syncStatus]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    @Volatile private var lastSyncAtMs: Long? = null
    @Volatile private var lastAttemptAtMs: Long? = null

    /**
     * Serializes the two ways a catalog refresh can start. Both write the same cache files and
     * swap the same [catalog] field, so a silent resume-refresh and a tapped "Sync catalog" must
     * never run at once.
     */
    private val refreshMutex = Mutex()

    /** Loads the cached (or bundled) catalog and scans local files. Safe to call repeatedly. */
    suspend fun initialize() {
        mutex.withLock {
            if (catalog == null) {
                catalog = catalogRepository.loadCached() ?: catalogRepository.loadBundled()
            }
        }
        rebuild()
        _initialized.value = true
    }

    /** Re-reads download state and sideloaded files from disk. Serialized so an older scan never wins. */
    suspend fun rebuild() = withContext(Dispatchers.IO) {
        rebuildMutex.withLock {
            val snapshot = mutex.withLock { catalog }
            val remote = snapshot?.let { catalogRepository.toAlbums(it) }.orEmpty()
            val local = runCatching { store.listLocalAlbums() }.getOrDefault(emptyList())
            // The album list is keyed by id in Compose, and sideloaded folders share the id
            // namespace with catalog albums: one duplicate would crash the library screen.
            _albums.value = (remote + local).distinctBy { it.id }
        }
    }

    /** Downloads a fresh catalog from the site in the background; progress and errors go to [syncStatus]. */
    fun refreshCatalog(network: Network? = null) {
        if (_syncStatus.value is SyncStatus.Syncing) return
        _syncStatus.value = SyncStatus.Syncing
        lastAttemptAtMs = nowMs()
        scope.launch {
            try {
                // Waits rather than bails: the button must always report an outcome, even if a
                // silent refresh happened to start moments earlier.
                val stale = refreshMutex.withLock { applyRefresh(network) }
                _syncStatus.value = SyncStatus.Synced(stale)
            } catch (e: CancellationException) {
                _syncStatus.value = SyncStatus.Idle
                throw e
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Picks up songs added on the site since the last sync. Called on every resume, so it is
     * throttled and stays silent: a background failure must not paint an error over a library that
     * is working perfectly well from cache, and must not disturb [syncStatus], which belongs to the
     * Sync button.
     */
    fun autoRefreshCatalog() {
        if (!shouldAutoRefresh(nowMs(), lastSyncAtMs, lastAttemptAtMs, _syncStatus.value is SyncStatus.Syncing, isOnline())) return
        lastAttemptAtMs = nowMs()
        scope.launch {
            // Never queues: if a refresh is already running, it fetches the same catalog anyway.
            if (!refreshMutex.tryLock()) return@launch
            try {
                runCatching { applyRefresh(null) }
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /** Fetches, swaps in and rebuilds; returns the albums that had to be served from cache. */
    private suspend fun applyRefresh(network: Network?): List<String> {
        val fresh = catalogRepository.refresh(network)
        mutex.withLock { catalog = fresh }
        rebuild()
        lastSyncAtMs = nowMs()
        return fresh.staleAlbumIds
    }

    fun album(id: String): Album? = _albums.value.firstOrNull { it.id == id }

    fun track(id: String): Track? = _albums.value.asSequence().flatMap { it.tracks.asSequence() }.firstOrNull { it.id == id }

    suspend fun deleteAlbumDownloads(albumId: String) {
        withContext(Dispatchers.IO) { store.deleteAlbumDownloads(albumId) }
        rebuild()
    }

    fun totalDownloadedBytes(): Long = store.totalDownloadedBytes()
}
