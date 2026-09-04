package com.raznoe.katana.model

import kotlinx.serialization.Serializable

/**
 * A backing/jam track.
 *
 * Two kinds live side by side. Most come from the phone's own music library
 * (MediaStore), where the URI is stable and readable for as long as the file
 * exists. The rest were hand-picked through the system file picker, and only
 * those need persisting — a library track is found again on the next scan.
 */
@Serializable
data class Track(
    val uri: String,
    val name: String,
    val artist: String = "",
    val durationMs: Long = 0,
    /** true => found in the phone's music library rather than hand-picked. */
    val fromLibrary: Boolean = false,
)
