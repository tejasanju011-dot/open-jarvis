package com.openjarvis.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val TAG = "OpenJarvis"
    private const val MAX_BYTES = 512 * 1024
    private const val FILE_NAME = "openjarvis-diagnostics.log"

    @Volatile private var initialized = false
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                record(context, "FATAL", "Uncaught exception on ${thread.name}", error)
                previous?.uncaughtException(thread, error)
            }
            record(context, "INFO", "Diagnostics initialized; app version logging enabled")
        }
    }

    fun info(context: Context, message: String) = record(context, "INFO", message)
    fun warn(context: Context, message: String, error: Throwable? = null) =
        record(context, "WARN", message, error)
    fun error(context: Context, message: String, error: Throwable? = null) =
        record(context, "ERROR", message, error)

    private fun record(context: Context, level: String, message: String, error: Throwable? = null) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val safeMessage = message.replace(Regex("(?i)(api[_ -]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s,;]+"), "$1=[REDACTED]")
        val entry = buildString {
            append(timestamp).append(" ").append(level).append(" ").append(safeMessage).append("\n")
            error?.let { append(Log.getStackTraceString(it)).append("\n") }
        }
        Log.println(
            when (level) {
                "ERROR", "FATAL" -> Log.ERROR
                "WARN" -> Log.WARN
                else -> Log.INFO
            },
            TAG,
            safeMessage
        )
        synchronized(lock) {
            runCatching {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() + entry.toByteArray().size > MAX_BYTES) {
                    val old = file.readText()
                    file.writeText(old.takeLast(MAX_BYTES / 2))
                }
                file.appendText(entry)
            }
        }
    }

    fun readAll(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else "No diagnostic logs have been recorded yet.\n"
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
        info(context, "Diagnostic log cleared by user")
    }
}
