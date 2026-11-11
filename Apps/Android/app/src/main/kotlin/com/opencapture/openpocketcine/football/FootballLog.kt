package com.opencapture.openpocketcine.football

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Ringlogboek van wat de voetbal-modus deed. Tijdens een sessie wordt het elke paar seconden
 * weggeschreven naar `Downloads/PocketVoetbal/voetbal-<start>.txt`, zodat ook na een harde crash
 * te zien is wat er vlak daarvoor gebeurde.
 */
object FootballLog {
    private const val MAX = 600
    private const val FLUSH_MS = 4_000L
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "football-log").apply { isDaemon = true } }
    private var fileUri: Uri? = null
    private var appContext: Context? = null
    private var lastFlushAt = 0L
    private var dirty = false

    /** Nieuw bestand voor deze sessie. */
    @Synchronized
    fun beginSession(context: Context) {
        appContext = context.applicationContext
        fileUri = null
        val name = "voetbal-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
        writer.execute {
            val uri = runCatching { CrashLog.saveToDownloads(context, name, "") }.getOrNull()
            synchronized(this) { fileUri = uri }
        }
    }

    @Synchronized
    fun add(line: String) {
        lines.addLast(fmt.format(Date()) + " " + line)
        while (lines.size > MAX) lines.removeFirst()
        dirty = true
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushAt >= FLUSH_MS) flush()
    }

    /** Schrijft het logboek weg (asynchroon). */
    @Synchronized
    fun flush() {
        val ctx = appContext ?: return
        if (!dirty) return
        dirty = false
        lastFlushAt = SystemClock.elapsedRealtime()
        val text = dump()
        writer.execute {
            val uri = synchronized(this) { fileUri } ?: return@execute
            try {
                CrashLog.rewrite(ctx, uri, text)
            } catch (t: Throwable) {
                Log.w("FootballLog", "wegschrijven mislukt", t)
            }
        }
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(leeg)" else lines.joinToString("\n")
}
