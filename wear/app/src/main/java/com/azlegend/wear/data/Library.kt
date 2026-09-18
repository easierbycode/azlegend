package com.azlegend.wear.data

import java.io.File

/** A playable track, either downloaded/local (has [localFile]) or remote-only (has [remoteUrl]). */
data class Track(
    val id: String,
    val title: String,
    val albumId: String,
    val albumTitle: String,
    val fileName: String,
    val remoteUrl: String?,
    val localFile: File?,
) {
    val isDownloaded: Boolean get() = localFile != null
    val isPlayable: Boolean get() = localFile != null || remoteUrl != null
}

data class Album(
    val id: String,
    val title: String,
    val tracks: List<Track>,
    /** True for pseudo-albums built from files that were copied onto the watch directly. */
    val isLocal: Boolean = false,
) {
    val downloadedCount: Int get() = tracks.count { it.isDownloaded }
    val isFullyDownloaded: Boolean get() = tracks.isNotEmpty() && downloadedCount == tracks.size
    val downloadedBytes: Long get() = tracks.sumOf { it.localFile?.length() ?: 0L }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
