package com.azlegend.wear.data

import android.net.Network
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
    data object Synced : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * Single source of truth for what the watch can play: catalog albums (with their download state)
 * followed by pseudo-albums built from sideloaded files. [rebuild] rescans the file system, so call
 * it after downloads finish or files are deleted.
 */
class LibraryRepository(
    private val store: MusicStore,
    private val catalogRepository: CatalogRepository,
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
            _albums.value = remote + local
        }
    }

    /** Downloads a fresh catalog from the site in the background; progress and errors go to [syncStatus]. */
    fun refreshCatalog(network: Network? = null) {
        if (_syncStatus.value is SyncStatus.Syncing) return
        _syncStatus.value = SyncStatus.Syncing
        scope.launch {
            try {
                val fresh = catalogRepository.refresh(network)
                mutex.withLock { catalog = fresh }
                rebuild()
                _syncStatus.value = SyncStatus.Synced
            } catch (e: CancellationException) {
                _syncStatus.value = SyncStatus.Idle
                throw e
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun album(id: String): Album? = _albums.value.firstOrNull { it.id == id }

    fun track(id: String): Track? = _albums.value.asSequence().flatMap { it.tracks.asSequence() }.firstOrNull { it.id == id }

    suspend fun deleteAlbumDownloads(albumId: String) {
        withContext(Dispatchers.IO) { store.deleteAlbumDownloads(albumId) }
        rebuild()
    }

    fun totalDownloadedBytes(): Long = store.totalDownloadedBytes()
}
