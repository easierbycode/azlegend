package com.azlegend.wear.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class OutputKind { BLUETOOTH, HEADPHONES, SPEAKER, NONE }

/** Where music will come out of right now. */
data class AudioOutput(val kind: OutputKind, val name: String?)

private val BLUETOOTH_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
)

private val WIRED_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

fun currentAudioOutput(context: Context): AudioOutput {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return AudioOutput(OutputKind.NONE, null)
    // API 33+ can say which device media is routed to (the output switcher may pick the speaker
    // while headphones stay connected); older versions only expose the connected outputs.
    val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            audioManager.getAudioDevicesForAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
            ).firstOrNull()
        }.getOrNull()
    } else {
        null
    }
    if (routed != null) return classify(routed)
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    devices.firstOrNull { it.type in BLUETOOTH_TYPES }?.let {
        return AudioOutput(OutputKind.BLUETOOTH, it.productName?.toString()?.takeIf { n -> n.isNotBlank() })
    }
    devices.firstOrNull { it.type in WIRED_TYPES }?.let {
        return AudioOutput(OutputKind.HEADPHONES, it.productName?.toString()?.takeIf { n -> n.isNotBlank() })
    }
    if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }) {
        return AudioOutput(OutputKind.SPEAKER, null)
    }
    return AudioOutput(OutputKind.NONE, null)
}

private fun classify(device: AudioDeviceInfo): AudioOutput {
    val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
    return when (device.type) {
        in BLUETOOTH_TYPES -> AudioOutput(OutputKind.BLUETOOTH, name)
        in WIRED_TYPES -> AudioOutput(OutputKind.HEADPHONES, name)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioOutput(OutputKind.SPEAKER, null)
        else -> AudioOutput(OutputKind.NONE, name)
    }
}

/** Music-stream volume as 0..1, and the ability to nudge it from the crown / buttons. */
class VolumeControl(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val max: Int = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1

    fun fraction(): Float {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        return (current.toFloat() / max).coerceIn(0f, 1f)
    }

    fun adjust(up: Boolean) {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
    }
}
