package com.azlegend.wear.data

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
    /** File name used both on the server and for the local copy. */
    val file: String,
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
