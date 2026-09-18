package com.azlegend.wear.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.azlegend.wear.AppConfig
import com.azlegend.wear.data.Track

/** Builds the Media3 item for a track, or null when it has neither a local file nor a URL. */
fun Track.toMediaItem(): MediaItem? {
    val uri = localFile?.let { Uri.fromFile(it) } ?: remoteUrl?.toUri() ?: return null
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(AppConfig.ARTIST)
                .setAlbumTitle(albumTitle)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()
}
