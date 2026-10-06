package dev.backgrounded.core.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDiagnostics
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        fun record(
            enabled: Boolean,
            event: String,
            durationMs: Long,
        ) = Unit

        fun count(
            enabled: Boolean,
            name: String,
        ) = Unit

        fun gauge(
            enabled: Boolean,
            name: String,
            value: Long,
        ) = Unit

        fun export(): String = ""
    }
