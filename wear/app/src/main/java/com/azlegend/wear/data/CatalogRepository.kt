package com.azlegend.wear.data

import android.content.Context
import android.net.Network
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * Loads the album catalog. Order of preference: cached copy on the watch, then the copy bundled in
 * the APK's assets, and [refresh] replaces the cache from the site when a network is available.
 */
class CatalogRepository(
    private val context: Context,
    private val store: MusicStore,
    private val baseUrl: String,
) {
    private val json = CatalogJson.json
    private val albumListSerializer = ListSerializer(AlbumRef.serializer())
    private val trackListSerializer = ListSerializer(TrackRef.serializer())

    data class Catalog(
        val albums: List<AlbumRef>,
        val tracks: Map<String, List<TrackRef>>,
        /** Albums whose own list could not be fetched and were kept from the cache instead. */
        val staleAlbumIds: List<String> = emptyList(),
    )

    suspend fun loadCached(): Catalog? = withContext(Dispatchers.IO) {
        val albumsFile = File(store.catalogDir, ALBUMS_FILE)
        if (!albumsFile.isFile) return@withContext null
        runCatching {
            val albums = json.decodeFromString(albumListSerializer, albumsFile.readText())
            val tracks = albums.associate { album ->
                val f = File(store.catalogDir, trackFileName(album.id))
                album.id to (if (f.isFile) json.decodeFromString(trackListSerializer, f.readText()) else emptyList())
            }
            Catalog(albums, tracks)
        }.getOrNull()
    }

    suspend fun loadBundled(): Catalog? = withContext(Dispatchers.IO) {
        runCatching {
            val assets = context.assets
            val albums = assets.open("$ASSETS_DIR/$ALBUMS_FILE").bufferedReader().use { it.readText() }
                .let { json.decodeFromString(albumListSerializer, it) }
            val tracks = albums.associate { album ->
                val text = runCatching {
                    assets.open("$ASSETS_DIR/${trackFileName(album.id)}").bufferedReader().use { it.readText() }
                }.getOrNull()
                album.id to (text?.let { json.decodeFromString(trackListSerializer, it) } ?: emptyList())
            }
            Catalog(albums, tracks)
        }.getOrNull()
    }

    /**
     * Fetches albums.json and every track list from the site and caches them.
     *
     * albums.json itself is all-or-nothing, but one album whose own list fails keeps the list it
     * had cached: the catalog now contains an album served from a live endpoint, and a single 500
     * there must not cost the user the rest of their library. A sync where nothing at all could be
     * fetched is a dead network, not a partial success, so that still throws.
     */
    suspend fun refresh(network: Network? = null): Catalog = withContext(Dispatchers.IO) {
        val albumsText = fetch(resolveUrl(baseUrl, ALBUMS_PATH), network)
        val albums = json.decodeFromString(albumListSerializer, albumsText)
        val tracks = LinkedHashMap<String, List<TrackRef>>()
        val trackTexts = LinkedHashMap<String, String>()
        val stale = mutableListOf<String>()
        var fetched = 0
        for (album in albums) {
            val fresh = runCatching {
                val text = fetch(resolveUrl(baseUrl, album.tracks), network)
                json.decodeFromString(trackListSerializer, text) to text
            }.getOrNull()
            if (fresh != null) {
                fetched++
                tracks[album.id] = fresh.first
                trackTexts[album.id] = fresh.second
                continue
            }
            stale += album.id
            val cachedText = runCatching { File(store.catalogDir, trackFileName(album.id)).readText() }.getOrNull()
            val cached = cachedText?.let { runCatching { json.decodeFromString(trackListSerializer, it) }.getOrNull() }
            if (cached != null && cachedText != null) {
                tracks[album.id] = cached
                trackTexts[album.id] = cachedText
            } else {
                tracks[album.id] = emptyList()
            }
        }
        if (fetched == 0 && albums.isNotEmpty()) throw IOException("Could not fetch any track list")
        // Only persist once everything resolved, so a half-fetched catalog never replaces a good cache.
        File(store.catalogDir, ALBUMS_FILE).writeText(albumsText)
        trackTexts.forEach { (id, text) -> File(store.catalogDir, trackFileName(id)).writeText(text) }
        Catalog(albums, tracks, stale)
    }

    /** Converts catalog JSON into domain albums, marking which tracks already exist on the watch. */
    fun toAlbums(catalog: Catalog): List<Album> = catalog.albums.map { ref ->
        val trackRefs = catalog.tracks[ref.id].orEmpty()
        val fileNames = trackFileNames(trackRefs)
        Album(
            id = ref.id,
            title = ref.title,
            tracks = trackRefs.mapIndexed { index, t ->
                val fileName = fileNames[index]
                Track(
                    id = "${ref.id}/$fileName",
                    title = t.title?.takeIf { it.isNotBlank() } ?: titleFromFileName(fileName),
                    albumId = ref.id,
                    albumTitle = ref.title,
                    fileName = fileName,
                    remoteUrl = resolveUrl(baseUrl, t.url),
                    localFile = store.downloadedFile(ref.id, fileName),
                )
            },
        )
    }

    private fun fetch(url: String, network: Network?): String {
        val target = URL(url)
        val conn = (network?.openConnection(target) ?: target.openConnection()) as? HttpURLConnection
            ?: throw IOException("Unsupported URL $url")
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun trackFileName(albumId: String) = MusicStore.safeName(albumId) + ".json"

    companion object {
        const val ALBUMS_PATH = com.azlegend.wear.AppConfig.ALBUMS_PATH
        const val ALBUMS_FILE = "albums.json"
        const val ASSETS_DIR = "catalog"
    }
}
