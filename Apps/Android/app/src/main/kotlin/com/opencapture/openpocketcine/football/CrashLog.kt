package com.opencapture.openpocketcine.football

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Crash- en logboekvastlegging voor testers, zonder dat de gebruiker iets hoeft te doen:
 *
 *  - Onafgevangen Kotlin/Java-fouten worden meteen naar een bestand geschreven.
 *  - Bij elke start kijkt de app via [ApplicationExitInfo] waarom hij de vorige keer stopte
 *    (crash, native crash, ANR, geheugen) en haalt de bijbehorende trace op — dat werkt ook voor
 *    harde crashes die geen Kotlin-handler halen.
 *  - Alles wat gevonden wordt gaat automatisch als tekstbestand naar
 *    `Downloads/PocketVoetbal/` (zichtbaar in "Mijn bestanden"), zodat het gewoon als bijlage te
 *    sturen is. De voetbal-modus schrijft daar ook live zijn logboek heen (zie [FootballLog]).
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val DIR = "crashlog"
    private const val LAST = "last-crash.txt"
    private const val PREFS = "openpocketcine.crashlog"
    const val DOWNLOADS_SUBDIR = "PocketVoetbal"
    private var installed = false

    /** Laatst gemaakte rapport (pad in Downloads), voor de banner. */
    @Volatile var lastReportName: String? = null
        private set

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
        // Op de achtergrond: waarom stopte de app de vorige keer? Rapport naar Downloads.
        thread(name = "crashlog-startup", isDaemon = true) {
            try {
                reportPreviousExit(app)
            } catch (t: Throwable) {
                Log.w(TAG, "startrapport mislukt", t)
            }
        }
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), LAST)

    /** Omschrijving van de laatste vastgelegde crash voor de banner, of null. */
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
        val text =
            buildString {
                appendLine("Tijd: " + stamp())
                appendLine("Thread: ${thread.name}")
                appendLine("Toestel: ${device()}")
                appendLine()
                append(sw.toString())
                appendLine()
                appendLine("--- voetbal-modus logboek ---")
                appendLine(FootballLog.dump())
            }
        f.writeText(text)
        // Ook meteen naar Downloads: dan is het er zelfs als de app daarna niet meer opstart.
        runCatching { saveToDownloads(context, "crash-${fileStamp()}.txt", text) }
    }

    /** Leest de exit-reden(en) sinds de vorige keer dat we keken en schrijft een rapport weg. */
    private fun reportPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("lastExitTimestamp", 0L)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val fresh = exits.filter { it.timestamp > seen && it.reason != ApplicationExitInfo.REASON_USER_REQUESTED }
        if (exits.isNotEmpty()) prefs.edit().putLong("lastExitTimestamp", exits.maxOf { it.timestamp }).apply()
        val interesting =
            fresh.filter {
                it.reason in
                    setOf(
                        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_UNKNOWN,
                    )
            }
        if (interesting.isEmpty()) return
        val sb = StringBuilder()
        sb.appendLine("=== PocketVoetbal: waarom de app de vorige keer stopte ===")
        sb.appendLine("Toestel: ${device()}")
        sb.appendLine("Nu: ${stamp()}")
        sb.appendLine()
        for (e in interesting) {
            sb.appendLine("--- ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(e.timestamp))} ---")
            sb.appendLine("Reden: ${reasonName(e.reason)} (${e.reason}), status ${e.status}, belang ${e.importance}, pss ${e.pss / 1024} MB, rss ${e.rss / 1024} MB")
            sb.appendLine("Omschrijving: ${e.description}")
            val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (!trace.isNullOrBlank()) {
                sb.appendLine("Trace:")
                sb.appendLine(trimTrace(trace))
            }
            sb.appendLine()
        }
        val lastCrash = file(context)
        if (lastCrash.isFile) {
            sb.appendLine("--- laatste opgevangen Kotlin/Java-crash ---")
            sb.appendLine(lastCrash.readText().take(20_000))
            sb.appendLine()
        }
        sb.appendLine("--- logcat crashbuffer ---")
        sb.appendLine(logcat("-b", "crash", "-d", "-t", "300").take(20_000))
        val name = "exit-${fileStamp()}.txt"
        saveToDownloads(context, name, sb.toString())
        lastReportName = "$DOWNLOADS_SUBDIR/$name"
        // Zorg dat de banner ook verschijnt bij een native crash.
        if (!lastCrash.isFile) {
            lastCrash.parentFile?.mkdirs()
            lastCrash.writeText("Tijd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(interesting.first().timestamp))} (${reasonName(interesting.first().reason)})\n\n" + sb.toString().take(30_000))
        }
    }

    private fun trimTrace(trace: String): String {
        // Native tombstones zijn enorm; de kop + de crashende thread zijn genoeg.
        if (trace.length <= 25_000) return trace
        val head = trace.take(18_000)
        val backtrace = trace.indexOf("backtrace:").let { if (it > 0) trace.substring(it, minOf(trace.length, it + 7_000)) else "" }
        return head + "\n…\n" + backtrace
    }

    private fun reasonName(reason: Int) =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR (hing)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER (systeem)"
            else -> "UNKNOWN"
        }

    /** Alles wat nuttig is om te delen: laatste crash + voetbal-logboek + recente eigen logregels. */
    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== PocketVoetbal logboek ===")
        sb.appendLine("Toestel: ${device()}")
        sb.appendLine("Nu: ${stamp()}")
        sb.appendLine()
        val f = file(context)
        if (f.isFile) {
            sb.appendLine("--- Laatste vastgelegde crash ---")
            sb.appendLine(f.readText().take(12_000))
            sb.appendLine()
        }
        sb.appendLine("--- voetbal-modus logboek ---")
        sb.appendLine(FootballLog.dump().takeLast(30_000))
        sb.appendLine()
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
        runCatching { saveToDownloads(context, "logboek-${fileStamp()}.txt", text) }
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PocketVoetbal logboek")
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(send, "Logboek delen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Downloads ----------------------------------------------------------------------------

    /** Nieuw tekstbestand in Downloads/PocketVoetbal; geeft de Uri terug (voor herschrijven). */
    fun saveToDownloads(context: Context, name: String, text: String): Uri? {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_SUBDIR)
            }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        return uri
    }

    /** Herschrijft een eerder gemaakt bestand (het live voetbal-logboek). */
    fun rewrite(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), app ${appVersion}"
    private var appVersion: String = "?"
    fun noteVersion(context: Context) {
        appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }.getOrDefault("?")
    }
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    private fun fileStamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
}
