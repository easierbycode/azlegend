package com.azlegend.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.azlegend.wear.AppContainer
import com.azlegend.wear.R
import com.azlegend.wear.data.Album
import com.azlegend.wear.data.DownloadProgress
import com.azlegend.wear.data.DownloadState
import com.azlegend.wear.data.Track
import com.azlegend.wear.data.formatBytes
import com.azlegend.wear.playback.toMediaItem
import kotlinx.coroutines.launch

@Composable
fun AlbumScreen(
    container: AppContainer,
    albumId: String,
    onOpenPlayer: () -> Unit,
) {
    val albums by container.library.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }
    val progressMap by container.downloads.progress.collectAsStateWithLifecycle()
    val progress = progressMap[albumId]
    val player by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(album?.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (album == null) {
                item {
                    Text(
                        text = stringResource(R.string.library_empty),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                item {
                    Button(
                        onClick = {
                            container.player.play(album.tracks, 0)
                            onOpenPlayer()
                        },
                        enabled = album.tracks.any { it.isPlayable },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(ButtonDefaults.IconSize)) },
                        secondaryLabel = { Text(pluralStringResource(R.plurals.album_tracks, album.tracks.size, album.tracks.size), maxLines = 1) },
                        label = { Text(stringResource(R.string.action_play_album), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                if (!album.isLocal) {
                    item { DownloadButton(album, progress, spec, container) }
                    if (album.downloadedCount > 0) {
                        item {
                            FilledTonalButton(
                                onClick = { confirmDelete = true },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                                icon = { Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(ButtonDefaults.IconSize)) },
                                secondaryLabel = { Text(formatBytes(album.downloadedBytes), maxLines = 1) },
                                label = { Text(stringResource(R.string.action_delete_downloads), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
                    TrackButton(track, isCurrent = player.trackId == track.id, spec) {
                        container.player.play(album.tracks, index)
                        onOpenPlayer()
                    }
                }
            }
        }
    }

    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_confirm_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.delete_confirm_text, album?.title.orEmpty()), textAlign = TextAlign.Center) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.library.deleteAlbumDownloads(albumId)
                        container.player.refreshQueue(albumId) { id -> container.library.track(id)?.toMediaItem() }
                    }
                },
            )
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmDelete = false }) },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.DownloadButton(
    album: Album,
    progress: DownloadProgress?,
    spec: TransformationSpec,
    container: AppContainer,
) {
    val active = progress?.isActive == true
    when {
        active -> FilledTonalButton(
            onClick = { container.downloads.cancelAlbum(album.id) },
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
            icon = { CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 3.dp) },
            secondaryLabel = {
                val text = if (progress.state == DownloadState.RUNNING && progress.trackCount > 0) {
                    stringResource(R.string.album_downloading, progress.trackIndex + 1, progress.trackCount, progress.percent)
                } else {
                    stringResource(R.string.album_queued)
                }
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            label = { Text(stringResource(R.string.action_cancel_download), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        album.isFullyDownloaded -> FilledTonalButton(
            onClick = { },
            enabled = false,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
            icon = { Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(ButtonDefaults.IconSize)) },
            secondaryLabel = { Text(formatBytes(album.downloadedBytes), maxLines = 1) },
            label = { Text(stringResource(R.string.track_downloaded), maxLines = 1) },
        )
        else -> {
            val failure = progress?.takeIf { it.state == DownloadState.FAILED }?.error
            Button(
                onClick = { container.downloads.enqueueAlbum(album.id) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { Icon(painterResource(R.drawable.ic_download), null, Modifier.size(ButtonDefaults.IconSize)) },
                secondaryLabel = {
                    val text = failure?.let { stringResource(R.string.album_download_failed, it) }
                        ?: stringResource(R.string.album_downloaded_of, album.downloadedCount, album.tracks.size)
                    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                label = { Text(stringResource(R.string.action_download_album), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.TrackButton(
    track: Track,
    isCurrent: Boolean,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    val iconRes = when {
        isCurrent -> R.drawable.ic_music_note
        track.remoteUrl == null -> R.drawable.ic_folder
        track.isDownloaded -> R.drawable.ic_check_circle
        else -> R.drawable.ic_cloud
    }
    val secondary = when {
        track.remoteUrl == null -> stringResource(R.string.track_local)
        track.isDownloaded -> formatBytes(track.localFile?.length() ?: 0L)
        else -> stringResource(R.string.track_stream)
    }
    FilledTonalButton(
        onClick = onClick,
        enabled = track.isPlayable,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = if (isCurrent) ButtonDefaults.filledVariantButtonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(painterResource(iconRes), null, Modifier.size(ButtonDefaults.IconSize)) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}
