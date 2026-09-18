package com.azlegend.wear.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.azlegend.wear.data.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Snapshot of the player as the screens need it. */
data class PlayerUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val showPlayButton: Boolean = true,
    val isBuffering: Boolean = false,
    val isSuppressed: Boolean = false,
    val trackId: String? = null,
    val title: String = "",
    val album: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val index: Int = 0,
    val count: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val error: String? = null,
) {
    /** 0..1 progress through the current track. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The UI's handle on [PlaybackService]: a MediaController bound for the Activity's whole lifetime.
 * Keeping the binding while the Activity is merely stopped (covered by the output switcher, or the
 * watch face) keeps the service and its paused queue alive; the binding is dropped in onDestroy.
 * Playback itself never depends on this connection.
 */
class PlayerConnection(context: Context) {
    private val appContext = context.applicationContext
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var pendingCommand: ((MediaController) -> Unit)? = null
    private var uiVisible = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    /** Activity.onStart: connect if needed and resume position updates. */
    fun onStart() {
        uiVisible = true
        connect()
        if (controller != null) startTicker()
    }

    /** Activity.onStop: stop position updates but keep the controller (and thus the service) alive. */
    fun onStop() {
        uiVisible = false
        ticker?.cancel()
        ticker = null
    }

    /** Connects to the service (starting it if needed). Safe to call repeatedly. */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                val connected = try {
                    future.get()
                } catch (e: Exception) {
                    _state.update { it.copy(connected = false, error = e.message ?: "Could not connect to the player") }
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                refresh()
                if (uiVisible) startTicker()
                pendingCommand?.let { command ->
                    pendingCommand = null
                    command(connected)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /** Activity.onDestroy: drops the controller. Playback, if any, continues in the service. */
    fun release() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _state.update { it.copy(connected = false) }
    }

    /** Replaces the queue with [tracks] and starts playing at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L) = withController { c ->
        val playable = tracks.mapIndexedNotNull { index, track -> track.toMediaItem()?.let { index to it } }
        if (playable.isEmpty()) return@withController
        val startAt = playable.indexOfFirst { it.first >= startIndex }.takeIf { it >= 0 } ?: 0
        c.setMediaItems(playable.map { it.second }, startAt, startPositionMs)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() = withController { c ->
        when {
            c.playbackState == Player.STATE_IDLE -> {
                c.prepare()
                c.play()
            }
            c.playbackState == Player.STATE_ENDED -> {
                c.seekToDefaultPosition()
                c.play()
            }
            c.playWhenReady -> c.pause()
            else -> c.play()
        }
    }

    /**
     * Re-resolves queued items of [albumId] (never the one playing) after its files were downloaded or
     * deleted, so the queue switches between local files and streaming without a restart.
     */
    fun refreshQueue(albumId: String, resolve: (String) -> MediaItem?) {
        mainScope.launch {
            val c = controller ?: return@launch
            val prefix = "$albumId/"
            for (index in 0 until c.mediaItemCount) {
                if (index == c.currentMediaItemIndex) continue
                val id = c.getMediaItemAt(index).mediaId
                if (id.startsWith(prefix)) resolve(id)?.let { c.replaceMediaItem(index, it) }
            }
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pendingCommand = block
            connect()
        }
    }

    private fun refresh() {
        val c = controller ?: return
        _state.value = PlayerUiState(
            connected = true,
            hasMedia = c.mediaItemCount > 0,
            isPlaying = c.isPlaying,
            showPlayButton = !c.playWhenReady || c.playbackState == Player.STATE_IDLE ||
                c.playbackState == Player.STATE_ENDED,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            isSuppressed = c.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            trackId = c.currentMediaItem?.mediaId,
            title = c.mediaMetadata.title?.toString().orEmpty(),
            album = c.mediaMetadata.albumTitle?.toString().orEmpty(),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.knownDuration(),
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            error = c.playerError?.let { it.message ?: "Playback error" },
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = mainScope.launch {
            while (isActive) {
                val c = controller ?: break
                if (c.isPlaying) {
                    val position = c.currentPosition.coerceAtLeast(0L)
                    val duration = c.knownDuration()
                    _state.update { it.copy(positionMs = position, durationMs = duration) }
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun Player.knownDuration(): Long = duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    private companion object {
        const val POSITION_TICK_MS = 500L
    }
}
