package com.azlegend.wear.data

import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of albums.json on the site. */
@Serializable
data class AlbumRef(
    val id: String,
    val title: String,
    /** Relative or absolute URL of the JSON file that lists this album's tracks. */
    val tracks: String,
)

/** One entry of an album's track-list JSON on the site. */
@Serializable
data class TrackRef(
    /** Relative ("/public/music/...") or absolute ("https://...") URL of the audio file. */
    val url: String,
    /**
     * File name used both on the server and for the local copy. Optional: a song added remotely
     * may arrive without one, and a missing field here used to abort the whole catalog sync.
     */
    val file: String = "",
    val title: String? = null,
    val modified: String? = null,
    val created: String? = null,
)

object CatalogJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

/** Resolves a catalog URL against the site base URL; absolute URLs pass through unchanged. */
fun resolveUrl(base: String, url: String): String {
    val trimmed = url.trim()
    val absolute = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    val joined = if (absolute) trimmed else base.trimEnd('/') + "/" + trimmed.trimStart('/')
    return joined.replace(" ", "%20")
}

/** Basename of a URL path, with the query and fragment dropped: ".../b.mp3?dl=1#x" -> "b.mp3". */
fun fileNameFromUrl(url: String): String {
    val withoutQuery = url.trim().substringBefore('#').substringBefore('?')
    // Drop scheme and authority first, or a URL with no path ("https://x.com/") would be named
    // after its host.
    val afterScheme = withoutQuery.substringAfter("://", withoutQuery)
    val path = afterScheme.substringAfter('/', "").trimEnd('/')
    val last = path.substringAfterLast('/')
    // '+' is a literal plus in a path segment, so protect it from URLDecoder's form decoding.
    return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }
        .getOrDefault(last)
        .ifBlank { "track" }
}

/**
 * Local file names for one album's tracks: taken from [TrackRef.file] or the URL, sanitized, and
 * made unique within the album.
 *
 * Uniqueness is not cosmetic. [Track.id] is "<albumId>/<fileName>" and the track list is keyed by
 * it, so two tracks sharing a name would crash the list; they would also download over each other.
 */
fun trackFileNames(tracks: List<TrackRef>): List<String> {
    val used = HashSet<String>()
    return tracks.map { track ->
        uniqueName(MusicStore.safeName(track.file.ifBlank { fileNameFromUrl(track.url) }), used)
    }
}

/** "x.mp3", then "x-2.mp3", "x-3.mp3", ... */
private fun uniqueName(name: String, used: MutableSet<String>): String {
    if (used.add(name)) return name
    val stem = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var n = 2
    while (true) {
        val candidate = "$stem-$n$ext"
        if (used.add(candidate)) return candidate
        n++
    }
}

/** Turns "1._Hold_You_Down.mp3" into "1. Hold You Down" and "track_05.mp3" into "Track 05". */
fun titleFromFileName(fileName: String): String =
    titleFromFolderName(fileName.substringBeforeLast('.', fileName)).ifBlank { fileName }

/** Turns "Midas_II.deluxe" into "Midas II.deluxe"; unlike [titleFromFileName] nothing after a dot is dropped. */
fun titleFromFolderName(name: String): String =
    name.replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
        }
        .ifBlank { name }
