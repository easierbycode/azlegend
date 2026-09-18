package com.azlegend.wear.data

import android.content.Context
import android.os.Environment
import com.azlegend.wear.AppConfig
import java.io.File

/**
 * Owns the on-watch file layout:
 *  - downloads:  <filesDir>/music/<albumId>/<file>   (private, survives offline)
 *  - catalog:    <filesDir>/catalog/ (cached copies of the site JSON)
 *  - sideloaded: <externalFilesDir>/Music/ (reachable with `adb push`) and <filesDir>/Music/
 *                (reachable with `adb shell run-as`); one pseudo-album per sub-folder.
 */
class MusicStore(private val context: Context) {

    val downloadsRoot: File get() = File(context.filesDir, "music").also { it.mkdirs() }
    val catalogDir: File get() = File(context.filesDir, "catalog").also { it.mkdirs() }
    val localRoots: List<File>
        get() = listOfNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), File(context.filesDir, "Music"))

    fun albumDir(albumId: String): File = File(downloadsRoot, safeName(albumId))
    fun trackFile(albumId: String, fileName: String): File = File(albumDir(albumId), safeName(fileName))
    fun partFile(albumId: String, fileName: String): File = File(albumDir(albumId), safeName(fileName) + ".part")

    fun downloadedFile(albumId: String, fileName: String): File? =
        trackFile(albumId, fileName).takeIf { it.isFile && it.length() > 0 }

    fun deleteAlbumDownloads(albumId: String): Boolean = albumDir(albumId).deleteRecursively()

    fun deleteTrackDownload(albumId: String, fileName: String) {
        trackFile(albumId, fileName).delete()
        partFile(albumId, fileName).delete()
    }

    fun totalDownloadedBytes(): Long =
        downloadsRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Albums made of audio files the user copied to the watch (one album per sub-folder). */
    fun listLocalAlbums(): List<Album> {
        val rootTracks = mutableListOf<File>()
        val folders = LinkedHashMap<String, MutableList<File>>()
        for (root in localRoots) {
            if (!root.isDirectory) continue
            rootTracks += root.listFiles { f -> f.isFile && isAudio(f) }.orEmpty()
            root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }.forEach { dir ->
                val files = dir.walkTopDown().filter { it.isFile && isAudio(it) }.toList()
                if (files.isNotEmpty()) folders.getOrPut(dir.name) { mutableListOf() } += files
            }
        }
        val result = mutableListOf<Album>()
        if (rootTracks.isNotEmpty()) {
            result += localAlbum(LOCAL_ROOT_ID, AppConfig.LOCAL_ALBUM_TITLE, rootTracks.sortedBy { it.name.lowercase() })
        }
        folders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, files) ->
            result += localAlbum("$LOCAL_PREFIX$name", titleFromFolderName(name), files.sortedBy { it.name.lowercase() })
        }
        return result
    }

    private fun localAlbum(id: String, title: String, files: List<File>): Album = Album(
        id = id,
        title = title,
        isLocal = true,
        tracks = files.map { f ->
            Track(
                // Full path: folders are walked recursively, so basenames alone can collide.
                id = "$id/${f.absolutePath}",
                title = titleFromFileName(f.name),
                albumId = id,
                albumTitle = title,
                fileName = f.name,
                remoteUrl = null,
                localFile = f,
            )
        },
    )

    companion object {
        const val LOCAL_PREFIX = "local:"
        const val LOCAL_ROOT_ID = "local:root"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "mp4")

        fun isAudio(file: File): Boolean = file.extension.lowercase() in AUDIO_EXTENSIONS

        /** Keeps file names inside their directory (no separators, no traversal). */
        fun safeName(name: String): String {
            val cleaned = name.replace('/', '_').replace('\\', '_').trim()
            return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
        }
    }
}
