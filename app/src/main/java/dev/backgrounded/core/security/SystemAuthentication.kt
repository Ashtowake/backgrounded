package dev.backgrounded.core.security

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/** Uses the system prompt, including its device-credential fallback. */
object SystemAuthentication {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS ||
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun authenticate(
        context: Context,
        title: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (!available(context)) {
            onResult(false)
            return
        }
        BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(
                CancellationSignal(),
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onResult(true)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        onResult(false)
                    }
                },
            )
    }
}
