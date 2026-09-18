package com.azlegend.wear.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.azlegend.wear.AppContainer
import com.azlegend.wear.R
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(container: AppContainer) {
    val state by container.player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val volume = remember(context) { VolumeControl(context) }
    var volumeFraction by remember { mutableFloatStateOf(volume.fraction()) }
    var volumeVisibleUntil by remember { mutableStateOf(0L) }
    var output by remember { mutableStateOf(currentAudioOutput(context)) }
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    // The crown is a high-resolution encoder: many small events per turn, so step per ~48 px.
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }

    LifecycleResumeEffect(Unit) {
        output = currentAudioOutput(context)
        volumeFraction = volume.fraction()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.trackId, state.isPlaying) { output = currentAudioOutput(context) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun nudgeVolume(up: Boolean) {
        volume.adjust(up)
        volumeFraction = volume.fraction()
        volumeVisibleUntil = System.currentTimeMillis() + VOLUME_OVERLAY_MS
    }

    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(volumeVisibleUntil) {
        val remaining = volumeVisibleUntil - System.currentTimeMillis()
        showVolume = remaining > 0
        if (remaining > 0) {
            delay(remaining)
            showVolume = false
        }
    }

    ScreenScaffold(
        scrollState = scrollState,
        modifier = Modifier
            .onRotaryScrollEvent { event ->
                // Positive pixels are clockwise, which raises the volume by system convention.
                rotaryAccumulator += event.verticalScrollPixels
                while (rotaryAccumulator >= ROTARY_STEP_PX) {
                    nudgeVolume(up = true)
                    rotaryAccumulator -= ROTARY_STEP_PX
                }
                while (rotaryAccumulator <= -ROTARY_STEP_PX) {
                    nudgeVolume(up = false)
                    rotaryAccumulator += ROTARY_STEP_PX
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) { contentPadding ->
        // Centered when it fits (45 mm), scrollable when it does not (41 mm).
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(contentPadding)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    !state.connected -> StatusText(stringResource(R.string.player_connecting))
                    !state.hasMedia -> StatusText(stringResource(R.string.player_empty))
                    else -> NowPlaying(state = state, container = container, output = output, onVolume = ::nudgeVolume)
                }
            }
            if (showVolume) {
                LevelIndicator(value = { volumeFraction }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun NowPlaying(
    state: com.azlegend.wear.playback.PlayerUiState,
    container: AppContainer,
    output: AudioOutput,
    onVolume: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    // Extra side padding keeps long titles clear of the round edge (they wrap instead of clipping).
    Text(
        text = state.title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = state.album,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { container.player.previous() }, enabled = state.hasPrevious || state.positionMs > 3_000) {
            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.cd_previous))
        }
        Spacer(Modifier.width(4.dp))
        FilledIconButton(
            onClick = { container.player.togglePlayPause() },
            modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
        ) {
            val playing = !state.showPlayButton
            Icon(
                painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
                modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { container.player.next() }, enabled = state.hasNext) {
            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.cd_next))
        }
    }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(0.7f))
    Spacer(Modifier.height(2.dp))
    Text(
        text = "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}" +
            if (state.count > 1) "  ·  ${state.index + 1}/${state.count}" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
    val message = when {
        state.error != null -> stringResource(R.string.player_error, state.error)
        state.isSuppressed -> stringResource(R.string.player_suppressed)
        else -> null
    }
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(4.dp))
    val outputLabel = output.name ?: stringResource(
        when (output.kind) {
            OutputKind.BLUETOOTH -> R.string.output_bluetooth
            OutputKind.HEADPHONES -> R.string.output_headphones
            OutputKind.SPEAKER -> R.string.output_speaker
            OutputKind.NONE -> R.string.output_none
        },
    )
    val outputIcon = when (output.kind) {
        OutputKind.BLUETOOTH -> R.drawable.ic_bluetooth
        OutputKind.HEADPHONES -> R.drawable.ic_headphones
        OutputKind.SPEAKER -> R.drawable.ic_speaker
        OutputKind.NONE -> R.drawable.ic_warning
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = { onVolume(false) }, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)) {
            Icon(painterResource(R.drawable.ic_volume_down), stringResource(R.string.cd_volume_down), Modifier.size(IconButtonDefaults.SmallIconSize))
        }
        // Icon-only: there is no room for a device name between the volume buttons on a round screen.
        CompactButton(
            onClick = { openOutputSwitcher(context) },
            modifier = Modifier.padding(horizontal = 6.dp),
            icon = { Icon(painterResource(outputIcon), outputLabel, Modifier.size(ButtonDefaults.SmallIconSize)) },
        )
        IconButton(onClick = { onVolume(true) }, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)) {
            Icon(painterResource(R.drawable.ic_volume_up), stringResource(R.string.cd_volume_up), Modifier.size(IconButtonDefaults.SmallIconSize))
        }
    }
    Text(
        text = outputLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val VOLUME_OVERLAY_MS = 1_500L
private const val ROTARY_STEP_PX = 48f

/** Opens the system media output switcher (Wear OS 5+), falling back to Bluetooth settings. */
private fun openOutputSwitcher(context: android.content.Context) {
    val switcher = Intent(ACTION_MEDIA_OUTPUT)
        .putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val bluetooth = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (intent in listOf(switcher, bluetooth)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

private const val ACTION_MEDIA_OUTPUT = "com.android.settings.panel.action.MEDIA_OUTPUT"
private const val EXTRA_PACKAGE_NAME = "com.android.settings.panel.extra.PACKAGE_NAME"
