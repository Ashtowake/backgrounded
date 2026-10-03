package dev.backgrounded.core.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

/** Permission-free system credential confirmation (no Play-services dependency). */
object DeviceCredentialGate {
    @Suppress("DEPRECATION")
    fun confirmIntent(
        context: Context,
        title: String,
    ): Intent? =
        context.getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(title, null)
}
