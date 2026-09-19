package com.azlegend.wear.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.WearUnsuitableOutputPlaybackSuppressionResolverListener
import com.azlegend.wear.AppContainer
import com.azlegend.wear.AzLegendApp
import com.azlegend.wear.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Owns the ExoPlayer and the MediaSession. Media3 turns this into a mediaPlayback foreground
 * service with a media notification while something is playing, and the watch's system media
 * controls talk to the session directly.
 */
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var stateSaver: PlaybackStateSaver? = null

    override fun onCreate() {
        super.onCreate()
        val container = AzLegendApp.container(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // NETWORK, not LOCAL: remotely-added tracks stream from the internet, and a local-only
            // wake lock lets the radio drop while the screen is off, stalling playback mid-track.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Official Wear guidance: don't start music on the watch speaker by accident. When no
            // headphones are connected the resolver opens the system output switcher, where the
            // user can still pick the speaker explicitly, and playback resumes once an output is chosen.
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build()
        player.addListener(WearUnsuitableOutputPlaybackSuppressionResolverListener(this))
        stateSaver = PlaybackStateSaver(player, container.playbackState).also(player::addListener)

        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setCallback(SessionCallback(container, scope))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** When the user swipes the app away while nothing is playing, stop instead of lingering. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        val idle = player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE
        if (!player.playWhenReady || player.mediaItemCount == 0 || idle) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            stateSaver?.saveNow()
            session.player.release()
            session.release()
        }
        mediaSession = null
        stateSaver = null
        scope.cancel()
        super.onDestroy()
    }
}

/** Resolves items coming from controllers and restores the last queue for playback resumption. */
private class SessionCallback(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) : MediaSession.Callback {

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        container.library.initialize()
        // ExoPlayer cannot prepare an item without a URI, so anything unresolvable is dropped.
        mediaItems.mapNotNull { item ->
            if (item.localConfiguration != null) item else container.library.track(item.mediaId)?.toMediaItem()
        }.toMutableList()
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        container.library.initialize()
        val saved = container.playbackState.load() ?: throw UnsupportedOperationException("Nothing to resume")
        val items = saved.trackIds.mapNotNull { id -> container.library.track(id)?.toMediaItem() }
        if (items.isEmpty()) throw UnsupportedOperationException("Saved tracks are no longer on the watch")
        // Tracks may have disappeared since the save, so find the current one by id, not by index.
        val currentId = saved.trackIds.getOrNull(saved.index)
        val startIndex = items.indexOfFirst { it.mediaId == currentId }
        if (startIndex >= 0) {
            MediaSession.MediaItemsWithStartPosition(items, startIndex, saved.positionMs)
        } else {
            MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
        }
    }

    @Deprecated("Media3 calls the overload with isForPlayback; kept for older callers.")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        onPlaybackResumption(mediaSession, controller, true)
}

/** Persists the queue and position whenever they change so [SessionCallback] can restore them. */
private class PlaybackStateSaver(
    private val player: Player,
    private val store: PlaybackStateStore,
) : Player.Listener {

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
        ) {
            saveNow()
        }
    }

    fun saveNow() {
        val count = player.mediaItemCount
        if (count == 0) return
        val ids = (0 until count).map { player.getMediaItemAt(it).mediaId }
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L)
        store.save(ids, player.currentMediaItemIndex, position)
    }
}
