package com.raznoe.katana.model

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * The phone's own music, read straight from MediaStore.
 *
 * Hand-picking every backing track through the system file picker was the only
 * way in before, and it was both tedious and fragile: a picker URI is a grant
 * to one document, it can come back cancelled, and without a persistable grant
 * it stops working when the process dies. MediaStore is the library the phone
 * already maintains — one permission, every music file, URIs that keep working.
 */
object MusicLibrary {

    /** The permission that lets us read audio, which moved in Android 13. */
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Every music file on the phone. Runs a cursor over the whole library, so
     * call it off the main thread.
     *
     * `IS_MUSIC != 0` is what keeps ringtones, alarms and notification blips
     * out — MediaStore flags those separately from actual music.
     */
    fun query(context: Context): List<Track> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        ) ?: return emptyList()

        val found = ArrayList<Track>(cursor.count.coerceAtMost(4096))
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = titleCol.takeIf { it >= 0 }?.let { c.getString(it) }
                val fileName = nameCol.takeIf { it >= 0 }?.let { c.getString(it) }
                val artist = artistCol.takeIf { it >= 0 }?.let { c.getString(it) }
                val duration = durCol.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                val albumId = albumCol.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                found.add(
                    Track(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                        ).toString(),
                        name = title?.takeIf { it.isNotBlank() }
                            ?: fileName?.takeIf { it.isNotBlank() }
                            ?: "трек $id",
                        // MediaStore writes "<unknown>" rather than null when a
                        // file has no artist tag; that is noise on screen.
                        artist = artist?.takeIf { it.isNotBlank() && it != UNKNOWN }.orEmpty(),
                        durationMs = duration,
                        artUri = if (albumId > 0) {
                            ContentUris.withAppendedId(ALBUM_ART, albumId).toString()
                        } else {
                            ""
                        },
                        fromLibrary = true,
                    ),
                )
            }
        }
        return found
    }

    private const val UNKNOWN = "<unknown>"

    /**
     * Where MediaStore keeps album covers. Not a documented constant, but the
     * path every Android release has served album art from, and the one thing
     * that works the same from API 24 through 34.
     */
    private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
}
