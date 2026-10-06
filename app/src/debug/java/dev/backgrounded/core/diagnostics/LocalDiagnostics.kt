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
        private val counters = linkedMapOf<String, Long>()
        private val gauges = linkedMapOf<String, Long>()

        @Synchronized fun count(
            enabled: Boolean,
            name: String,
        ) {
            if (!enabled || counters.size >= 64 && name !in counters) return
            counters[name] = (counters[name] ?: 0L).let { if (it == Long.MAX_VALUE) it else it + 1 }
        }

        @Synchronized fun gauge(
            enabled: Boolean,
            name: String,
            value: Long,
        ) {
            if (!enabled || gauges.size >= 64 && name !in gauges) return
            gauges[name] = maxOf(gauges[name] ?: 0L, value)
        }

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
        fun export(): String =
            buildString {
                counters.forEach { (name, count) -> append("counter $name $count\n") }
                gauges.forEach { (name, value) -> append("peak $name $value\n") }
                if (log.exists()) append(log.readText())
            }

        private companion object {
            const val MAX_BYTES = 64 * 1024
            const val KEEP_LINES = 400
        }
    }
