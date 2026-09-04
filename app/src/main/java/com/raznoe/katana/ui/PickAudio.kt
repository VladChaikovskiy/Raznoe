package com.raznoe.katana.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Pick one or more audio files, with a grant that survives a restart.
 *
 * The stock `ActivityResultContracts.OpenDocument` contract does NOT set
 * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, so `takePersistableUriPermission`
 * throws and the URI only works until the process dies — a track added today
 * failed to open tomorrow with "lost access to the file". Asking for the
 * persistable grant up front is the fix, and it cannot be done through the
 * stock contract, hence this one.
 *
 * Multi-select is on: adding a set of backing tracks one file at a time, with
 * a trip through the picker each time, is the reason nobody bothered.
 */
class PickAudio : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        // A multi-select result arrives in ClipData; a single pick in getData().
        val clip = intent.clipData
        if (clip != null) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }
}
