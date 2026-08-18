package com.moodboard.keyboard

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P0 stability work. The client reports the app "crashing, unable to open the app at all
 * most of the time" and we cannot reproduce it locally, so the first requirement is simply
 * a record of what actually happened on their device.
 *
 * Installs a process-wide [Thread.UncaughtExceptionHandler] that appends every crash to
 * `filesDir/crash_log.txt` - capped to the last [MAX_ENTRIES] crashes and [MAX_TOTAL_CHARS]
 * total characters so it can never grow unbounded - and then delegates to whatever handler
 * was previously installed (the platform default, which still shows the "app has stopped"
 * dialog and terminates the process; this class only *observes* the crash, it never
 * suppresses it).
 *
 * [ui.SetupActivity] surfaces the log via a "Copy crash log" / "Share diagnostics" action,
 * shown only when the file is non-empty.
 */
class MoodBoardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendCrash(thread, throwable)
            } catch (t: Throwable) {
                // The crash logger must never itself become the reason the crash handler
                // fails to run.
                Log.e(TAG, "Failed to write crash log", t)
            }
            // Always delegate - this handler only taps the exception, it never swallows it,
            // so normal platform crash behaviour (and any prior handler, e.g. a test harness
            // or ANR watchdog) is preserved.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Runtime.getRuntime().exit(10)
            }
        }
    }

    private fun appendCrash(thread: Thread, throwable: Throwable) {
        val file = File(filesDir, CRASH_LOG_NAME)
        val stackTrace = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val newEntry = "$ENTRY_MARKER$stamp on thread '${thread.name}' -----\n$stackTrace\n"

        val existingEntries = if (file.exists()) splitEntries(safeReadText(file)) else emptyList()
        val cappedEntries = (existingEntries + newEntry).takeLast(MAX_ENTRIES)
        var combined = cappedEntries.joinToString("")
        if (combined.length > MAX_TOTAL_CHARS) {
            // Even the capped entry count is too large (e.g. one huge stack trace) - keep the
            // most recent content, which is what actually matters for diagnosing the latest
            // crash.
            combined = combined.takeLast(MAX_TOTAL_CHARS)
        }
        file.writeText(combined)
    }

    private fun safeReadText(file: File): String = try {
        file.readText()
    } catch (t: Throwable) {
        ""
    }

    private fun splitEntries(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(ENTRY_MARKER)
            .filter { it.isNotBlank() }
            .map { ENTRY_MARKER + it }
    }

    companion object {
        private const val TAG = "MoodBoardApp"

        /** Read by [ui.SetupActivity] to locate and share the log. */
        const val CRASH_LOG_NAME = "crash_log.txt"

        private const val ENTRY_MARKER = "----- "
        private const val MAX_ENTRIES = 5
        private const val MAX_TOTAL_CHARS = 200_000
    }
}
