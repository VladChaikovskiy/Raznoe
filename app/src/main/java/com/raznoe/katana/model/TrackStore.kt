package com.raznoe.katana.model

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Persists the jam-track list (URIs + names) as a single JSON file. */
class TrackStore(context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "tracks.json")

    fun list(): List<Track> =
        if (file.exists())
            runCatching { json.decodeFromString(ListSerializer(Track.serializer()), file.readText()) }
                .getOrDefault(emptyList())
        else emptyList()

    fun saveAll(tracks: List<Track>) {
        file.writeText(json.encodeToString(ListSerializer(Track.serializer()), tracks))
    }
}
