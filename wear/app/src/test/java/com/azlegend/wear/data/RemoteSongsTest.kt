package com.azlegend.wear.data

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the catalog handling a remotely-added song goes through. */
class RemoteSongsTest {

    private val json = CatalogJson.json

    @Test
    fun `an added album entry parses alongside the seed albums`() {
        val text = """
            [
              {"id": "midas_ii", "title": "Midas II", "tracks": "/public/music/midas_ii.json"},
              {"id": "added", "title": "Added", "tracks": "/public/music/added.json"}
            ]
        """.trimIndent()
        val albums = json.decodeFromString(ListSerializer(AlbumRef.serializer()), text)
        assertEquals(listOf("midas_ii", "added"), albums.map { it.id })
    }

    @Test
    fun `the served added json parses into a track`() {
        val text = """
            [{"url": "https://res.cloudinary.com/dujip8nqb/video/upload/v1770804101/Sol_Invicto.mp3",
              "file": "Sol_Invicto.mp3",
              "title": "Sol Invicto - Initium",
              "created": "2026-09-19T10:55:14.265Z"}]
        """.trimIndent()
        val tracks = json.decodeFromString(ListSerializer(TrackRef.serializer()), text)
        assertEquals(1, tracks.size)
        assertEquals("Sol Invicto - Initium", tracks[0].title)
        assertEquals("Sol_Invicto.mp3", tracks[0].file)
    }

    @Test
    fun `a track with no file field no longer aborts the whole sync`() {
        val tracks = json.decodeFromString(
            ListSerializer(TrackRef.serializer()),
            """[{"url": "https://cdn.example.com/a/b/song.mp3"}]""",
        )
        assertEquals("", tracks[0].file)
        assertEquals(listOf("song.mp3"), trackFileNames(tracks))
    }

    @Test
    fun `file names fall back to the url basename without query or fragment`() {
        assertEquals("song.mp3", fileNameFromUrl("https://x.com/a/song.mp3?dl=1#t=1"))
        assertEquals("My Song.mp3", fileNameFromUrl("https://x.com/My%20Song.mp3"))
        assertEquals("a+b.mp3", fileNameFromUrl("https://x.com/a+b.mp3"))
        assertEquals("track", fileNameFromUrl("https://x.com/"))
    }

    @Test
    fun `two tracks sharing a basename get distinct file names`() {
        val tracks = listOf(
            TrackRef(url = "https://a.example.com/x/song.mp3"),
            TrackRef(url = "https://b.example.com/y/song.mp3"),
            TrackRef(url = "https://c.example.com/z/song.mp3"),
        )
        assertEquals(listOf("song.mp3", "song-2.mp3", "song-3.mp3"), trackFileNames(tracks))
    }

    @Test
    fun `file names stay inside the album directory`() {
        val tracks = listOf(TrackRef(url = "https://x.com/a.mp3", file = "../../etc/passwd"))
        val name = trackFileNames(tracks).single()
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
    }

    @Test
    fun `an absolute third-party url reaches the player untouched`() {
        val url = "https://res.cloudinary.com/dujip8nqb/video/upload/v1770804101/Sol_Invicto.mp3"
        assertEquals(url, resolveUrl("https://azlegend.easierbycode.deno.net", url))
    }

    @Test
    fun `auto refresh waits out the interval and stays off when offline`() {
        val minute = 60_000L

        // Never synced, online, nothing in flight: go.
        assertTrue(shouldAutoRefresh(minute, null, null, syncing = false, online = true))

        // Offline, or a sync already running: never.
        assertFalse(shouldAutoRefresh(minute, null, null, syncing = false, online = false))
        assertFalse(shouldAutoRefresh(minute, null, null, syncing = true, online = true))

        // Synced a moment ago: wait for the interval, then go.
        val now = 10 * minute
        assertFalse(shouldAutoRefresh(now, now - minute, null, syncing = false, online = true))
        assertTrue(shouldAutoRefresh(now, now - 6 * minute, null, syncing = false, online = true))

        // A failed attempt backs off for a minute, independently of the last success.
        assertFalse(shouldAutoRefresh(now, null, now - 30_000L, syncing = false, online = true))
        assertTrue(shouldAutoRefresh(now, null, now - 2 * minute, syncing = false, online = true))
    }
}
