package com.azlegend.wear

/** Static configuration for the watch app. */
object AppConfig {
    /** Production site that serves the album catalog and the MP3 files. */
    const val BASE_URL = "https://azlegend.easierbycode.deno.net"

    /** Path (relative to [BASE_URL]) of the album index. */
    const val ALBUMS_PATH = "/public/music/albums.json"

    /** Artist name shown in media notifications and the system media controls. */
    const val ARTIST = "AZ Legend"

    /** Display name for the pseudo-album made of files pushed with adb / file transfer. */
    const val LOCAL_ALBUM_TITLE = "Local Files"
}
