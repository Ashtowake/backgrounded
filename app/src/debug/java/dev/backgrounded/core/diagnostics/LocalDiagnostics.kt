package dev.backgrounded.core.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDiagnostics
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val log = File(context.filesDir, "debug-events.log")

        @Synchronized
        fun record(
            enabled: Boolean,
            event: String,
            durationMs: Long,
        ) {
            if (!enabled) return
            val safeEvent = event.replace(Regex("[^A-Za-z0-9_ .:-]"), "_").take(80)
            log.appendText("${System.currentTimeMillis()} $safeEvent ${durationMs}ms\n")
            if (log.length() > MAX_BYTES) {
                val lines = log.readLines()
                log.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
            }
        }

        @Synchronized
        fun export(): String = if (log.exists()) log.readText() else ""

        private companion object {
            const val MAX_BYTES = 64 * 1024
            const val KEEP_LINES = 400
        }
    }
