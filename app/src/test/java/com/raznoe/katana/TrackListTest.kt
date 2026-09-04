package com.raznoe.katana

import com.raznoe.katana.model.Track
import com.raznoe.katana.model.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Jam tab's list handling: merging the phone's library with hand-picked files. */
class TrackListTest {

    private fun lib(name: String, artist: String = "", ms: Long = 0, uri: String = name) =
        Track(uri = "content://media/external/audio/media/$uri", name = name,
            artist = artist, durationMs = ms, fromLibrary = true)

    private fun picked(name: String, uri: String = name) =
        Track(uri = "content://com.android.providers.downloads/$uri", name = name)

    @Test fun merge_putsHandPickedFirstThenLibrary_eachAlphabetical() {
        val merged = Tracks.merge(
            library = listOf(lib("Zebra"), lib("Apple")),
            picked = listOf(picked("Own Two"), picked("Own One")),
        )
        assertEquals(listOf("Own One", "Own Two", "Apple", "Zebra"), merged.map { it.name })
    }

    @Test fun merge_isCaseInsensitive() {
        val merged = Tracks.merge(listOf(lib("beta"), lib("Alpha"), lib("gamma")), emptyList())
        assertEquals(listOf("Alpha", "beta", "gamma"), merged.map { it.name })
    }

    /** A file that is also in the library must not appear twice. */
    @Test fun merge_dropsAHandPickedDuplicateOfALibraryTrack() {
        val shared = "content://media/external/audio/media/7"
        val merged = Tracks.merge(
            library = listOf(lib("Song", uri = "7")),
            picked = listOf(Track(uri = shared, name = "Song (copy)")),
        )
        assertEquals(1, merged.size)
        assertEquals(shared, merged.first().uri)
        assertTrue(merged.first().fromLibrary)
    }

    @Test fun merge_handlesEmptySides() {
        assertEquals(0, Tracks.merge(emptyList(), emptyList()).size)
        assertEquals(1, Tracks.merge(listOf(lib("One")), emptyList()).size)
        assertEquals(1, Tracks.merge(emptyList(), listOf(picked("One"))).size)
    }

    @Test fun filter_matchesTitleAndArtist_caseInsensitively() {
        val all = listOf(
            lib("Still Got the Blues", "Gary Moore"),
            lib("Comfortably Numb", "Pink Floyd"),
            lib("Texas Flood", "Stevie Ray Vaughan"),
        )
        assertEquals(listOf("Still Got the Blues"), Tracks.filter(all, "blues").map { it.name })
        assertEquals(listOf("Comfortably Numb"), Tracks.filter(all, "FLOYD").map { it.name })
        assertEquals(3, Tracks.filter(all, "   ").size)
        assertEquals(0, Tracks.filter(all, "nothing here").size)
    }

    @Test fun formatDuration_isMinutesAndSeconds_blankWhenUnknown() {
        assertEquals("3:07", Tracks.formatDuration(187_000))
        assertEquals("0:05", Tracks.formatDuration(5_400))
        assertEquals("12:00", Tracks.formatDuration(720_000))
        assertEquals("", Tracks.formatDuration(0))
        assertEquals("", Tracks.formatDuration(-1))
    }

    @Test fun subtitle_showsWhatWeActuallyKnow() {
        assertEquals("Gary Moore · 3:07", Tracks.subtitle(lib("X", "Gary Moore", 187_000)))
        assertEquals("Gary Moore", Tracks.subtitle(lib("X", "Gary Moore", 0)))
        assertEquals("3:07", Tracks.subtitle(lib("X", "", 187_000)))
        assertEquals("", Tracks.subtitle(lib("X")))
    }

    /** Old saved files have no such fields; defaults must keep them loadable. */
    @Test fun track_defaultsKeepOlderSavedEntriesValid() {
        val t = Track(uri = "content://x", name = "Old")
        assertEquals("", t.artist)
        assertEquals(0L, t.durationMs)
        assertEquals(false, t.fromLibrary)
    }
}
