package com.raznoe.katana.model

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** Persists captured raw tones as individual JSON files. */
class RawPatchStore(context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "raw-patches").apply { mkdirs() }

    fun list(): List<RawPatch> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<RawPatch>(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun save(patch: RawPatch) {
        runCatching {
            File(dir, "${fileName(patch.name)}.json")
                .writeText(json.encodeToString(RawPatch.serializer(), patch))
        }
    }

    fun delete(name: String) {
        runCatching { File(dir, "${fileName(name)}.json").delete() }
    }

    /** Cyrillic names are normal here, so keep letters and drop only separators. */
    private fun fileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifBlank { "tone" }
}
