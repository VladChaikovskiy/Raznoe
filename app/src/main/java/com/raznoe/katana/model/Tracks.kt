package com.raznoe.katana.model

/**
 * Pure list handling for the Jam tab, kept out of [MusicLibrary] so it can be
 * unit-tested without a device.
 */
object Tracks {

    /**
     * The list the Jam tab shows: hand-picked tracks first (few and chosen on
     * purpose), then the phone's library, each alphabetical.
     *
     * A hand-picked entry pointing at a file the library already has would show
     * up twice, so library URIs win and the duplicate is dropped.
     */
    fun merge(library: List<Track>, picked: List<Track>): List<Track> {
        val libraryUris = library.mapTo(HashSet()) { it.uri }
        val byName = compareBy<Track> { it.name.lowercase() }
        return picked.filterNot { it.uri in libraryUris }.sortedWith(byName) +
            library.sortedWith(byName)
    }

    /**
     * Free-text filter over title and artist. A phone holds hundreds of tracks,
     * and scrolling to one mid-rehearsal is not workable.
     */
    fun filter(tracks: List<Track>, query: String): List<Track> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return tracks
        return tracks.filter {
            it.name.lowercase().contains(needle) || it.artist.lowercase().contains(needle)
        }
    }

    /** "3:07", or "" when MediaStore had no duration for the file. */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** One-line subtitle for a row: artist and length, whichever we have. */
    fun subtitle(t: Track): String =
        listOf(t.artist, formatDuration(t.durationMs)).filter { it.isNotEmpty() }.joinToString(" · ")
}
