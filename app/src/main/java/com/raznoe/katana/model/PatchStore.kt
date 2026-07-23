package com.raznoe.katana.model

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** Persists patches as individual JSON files under the app's files dir. */
class PatchStore(context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "patches").apply { mkdirs() }

    fun list(): List<Patch> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<Patch>(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun save(patch: Patch) {
        val safe = patch.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "patch" }
        File(dir, "$safe.json").writeText(json.encodeToString(Patch.serializer(), patch))
    }

    fun delete(name: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        File(dir, "$safe.json").delete()
    }
}
