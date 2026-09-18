package com.azlegend.wear.playback

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers the last queue and position so playback can resume from a headset button or the
 * system media controls after the service was stopped.
 */
class PlaybackStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)

    data class Saved(val trackIds: List<String>, val index: Int, val positionMs: Long)

    fun save(trackIds: List<String>, index: Int, positionMs: Long) {
        prefs.edit {
            putString(KEY_IDS, trackIds.joinToString(SEPARATOR))
            putInt(KEY_INDEX, index)
            putLong(KEY_POSITION, positionMs)
        }
    }

    fun load(): Saved? {
        val ids = prefs.getString(KEY_IDS, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return null
        return Saved(ids, prefs.getInt(KEY_INDEX, 0), prefs.getLong(KEY_POSITION, 0L))
    }

    private companion object {
        const val KEY_IDS = "ids"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val SEPARATOR = "\n"
    }
}
