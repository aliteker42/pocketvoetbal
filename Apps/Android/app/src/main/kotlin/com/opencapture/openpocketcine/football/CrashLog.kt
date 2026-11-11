package com.opencapture.openpocketcine.football

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eenvoudig crashlogboek voor testers: vangt onafgevangen fouten (Kotlin/Java) op en schrijft ze
 * naar een bestand; bij de volgende start kan de gebruiker het delen. Voor native crashes (die
 * het proces direct doden) wordt de eigen logcat-crashbuffer meegenomen.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val DIR = "crashlog"
    private const val LAST = "last-crash.txt"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (t: Throwable) {
                Log.e(TAG, "kon crash niet wegschrijven", t)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), LAST)

    /** Tijdstip van de laatste vastgelegde crash, of null. */
    fun lastCrashDescription(context: Context): String? {
        val f = file(context)
        if (!f.isFile) return null
        return f.useLines { lines -> lines.firstOrNull { it.startsWith("Tijd: ") } }?.removePrefix("Tijd: ")
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        f.writeText(
            buildString {
                appendLine("Tijd: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
                appendLine("Thread: ${thread.name}")
                appendLine("Toestel: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine()
                append(sw.toString())
            },
        )
    }

    /** Alles wat nuttig is om te delen: laatste crash + recente eigen logregels (incl. crashbuffer). */
    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== PocketVoetbal crashlog ===")
        sb.appendLine("Toestel: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Nu: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
        sb.appendLine()
        val f = file(context)
        if (f.isFile) {
            sb.appendLine("--- Laatste opgevangen crash ---")
            sb.appendLine(f.readText().take(12_000))
            sb.appendLine()
        } else {
            sb.appendLine("--- Geen opgevangen Kotlin/Java-crash (mogelijk native of door het systeem gestopt) ---")
            sb.appendLine()
        }
        sb.appendLine("--- logcat crashbuffer ---")
        sb.appendLine(logcat("-b", "crash", "-d", "-t", "200").take(20_000))
        sb.appendLine()
        sb.appendLine("--- logcat recent (eigen proces) ---")
        sb.appendLine(logcat("-b", "main", "-d", "-t", "400", "*:W").take(20_000))
        return sb.toString()
    }

    private fun logcat(vararg args: String): String =
        try {
            val p = ProcessBuilder(listOf("logcat") + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.ifBlank { "(leeg)" }
        } catch (t: Throwable) {
            "(logcat niet beschikbaar: ${t.message})"
        }

    fun share(context: Context) {
        val text = collect(context)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PocketVoetbal crashlog")
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(send, "Crashlog delen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
