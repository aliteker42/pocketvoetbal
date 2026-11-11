package com.opencapture.openpocketcine.football

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Klein ringlogboek van wat de voetbal-modus deed; gaat mee in "Logboek delen". */
object FootballLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        lines.addLast(fmt.format(Date()) + " " + line)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(leeg)" else lines.joinToString("\n")
}
