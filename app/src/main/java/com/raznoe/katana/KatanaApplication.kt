package com.raznoe.katana

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the stack trace of a crash before the process dies.
 *
 * The app runs on a phone at a rehearsal, plugged into an amp, with no cable to
 * a computer and no logcat. When it did go down there was nothing to go on. Now
 * the trace lands in a file, the next launch shows it on the Патч screen, and
 * the diagnostics text includes it — so a crash can actually be fixed instead
 * of guessed at.
 *
 * The previous handler is always called afterwards: swallowing the exception
 * would leave the process alive in an unknown state, which is worse than a
 * clean restart.
 */
class KatanaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val TAG = "KatanaApp"
        private const val FILE = "last-crash.txt"

        private fun record(app: Application, thread: Thread, error: Throwable) {
            val trace = StringWriter().also { w ->
                PrintWriter(w).use { error.printStackTrace(it) }
            }.toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            Log.e(TAG, "uncaught on ${thread.name}", error)
            File(app.filesDir, FILE).writeText("$stamp  поток=${thread.name}\n$trace")
        }

        /** The last recorded crash, or null if the app has never gone down. */
        fun lastCrash(app: Application): String? =
            runCatching {
                File(app.filesDir, FILE).takeIf { it.exists() }?.readText()
            }.getOrNull()?.takeIf { it.isNotBlank() }

        fun clearCrash(app: Application) {
            runCatching { File(app.filesDir, FILE).delete() }
        }
    }
}
