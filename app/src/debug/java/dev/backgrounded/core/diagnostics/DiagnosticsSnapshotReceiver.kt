package dev.backgrounded.core.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/** ADB-only local snapshot; this receiver and its storage code are absent from release. */
@AndroidEntryPoint
class DiagnosticsSnapshotReceiver : BroadcastReceiver() {
    @Inject lateinit var diagnostics: LocalDiagnostics

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != "dev.backgrounded.DEBUG_SNAPSHOT") return
        File(context.filesDir, "debug-counters.txt").writeText(diagnostics.export())
    }
}
