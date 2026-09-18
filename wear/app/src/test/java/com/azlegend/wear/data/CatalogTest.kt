package com.azlegend.wear.data

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogTest {

    private val json = CatalogJson.json

    @Test
    fun `albums json from the site parses`() {
        val text = """
            [
              {"id": "az_legend_demo", "title": "AZ Legend Demo", "tracks": "/public/music/az_legend_demo.json"},
              {"id": "midas_ii", "title": "Midas II", "tracks": "/public/music/midas_ii.json", "extra": 1}
            ]
        """.trimIndent()
        val albums = json.decodeFromString(ListSerializer(AlbumRef.serializer()), text)
        assertEquals(listOf("az_legend_demo", "midas_ii"), albums.map { it.id })
        assertEquals("Midas II", albums[1].title)
    }

    @Test
    fun `track json tolerates missing optional fields`() {
        val text = """[{"url": "/public/music/az_legend_demo/1._Hold_You_Down.mp3", "file": "1._Hold_You_Down.mp3"}]"""
        val tracks = json.decodeFromString(ListSerializer(TrackRef.serializer()), text)
        assertEquals(1, tracks.size)
        assertNull(tracks[0].title)
        assertEquals("1._Hold_You_Down.mp3", tracks[0].file)
    }

    @Test
    fun `relative urls resolve against the site and spaces are escaped`() {
        assertEquals(
            "https://azlegend.easierbycode.deno.net/public/music/a%20b.mp3",
            resolveUrl("https://azlegend.easierbycode.deno.net/", "/public/music/a b.mp3"),
        )
        assertEquals("https://cdn.example.com/x.mp3", resolveUrl("https://azlegend.easierbycode.deno.net", "https://cdn.example.com/x.mp3"))
    }

    @Test
    fun `titles come from file names`() {
        assertEquals("1. Hold You Down", titleFromFileName("1._Hold_You_Down.mp3"))
        assertEquals("Track 05", titleFromFileName("track_05.mp3"))
        assertEquals("AZ Is The New A Town", titleFromFileName("AZ_Is_The_New_A_Town.MP3"))
    }

    @Test
    fun `folder titles keep everything after a dot`() {
        assertEquals("Midas II", titleFromFolderName("Midas_II"))
        assertEquals("Vol.2 B-sides", titleFromFolderName("vol.2_b-sides"))
    }

    @Test
    fun `safe names never escape their directory`() {
        assertEquals("a_b", MusicStore.safeName("a/b"))
        assertEquals("_", MusicStore.safeName(".."))
        assertEquals("song.mp3", MusicStore.safeName(" song.mp3 "))
    }
}
