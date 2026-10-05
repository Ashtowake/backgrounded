package dev.backgrounded.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** The vault key exists in memory only after system authentication or optional PIN entry. */
@Singleton
class PinVault
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val preferences = context.getSharedPreferences("hidden_vault", Context.MODE_PRIVATE)
        private val random = SecureRandom()

        @Volatile
        private var unlockedKey: ByteArray? = null

        fun configured(): Boolean = preferences.contains("pin_cipher")

        fun biometricConfigured(): Boolean = preferences.contains("biometric_cipher")

        fun recoveryEnabled(): Boolean = preferences.contains("recovery_cipher")

        fun unlocked(): Boolean = unlockedKey != null

        fun key(): ByteArray? = unlockedKey?.copyOf()

        /** Create the auth-bound key before prompting so its use can consume that prompt's auth token. */
        fun prepareSystemKey(): Boolean = runCatching { systemKey() }.isSuccess

        /** Removes the optional PIN route only while a working system route is unlocked. */
        fun removePin(): Boolean {
            if (!configured() || !biometricConfigured() || !unlocked()) return false
            val saved =
                preferences.edit()
                    .remove("salt")
                    .remove("pin_nonce")
                    .remove("pin_cipher")
                    .remove("recovery_nonce")
                    .remove("recovery_cipher")
                    .remove("failures")
                    .remove("retry_at")
                    .commit()
            if (saved) {
                runCatching {
                    keyStore().apply {
                        deleteEntry(PEPPER_ALIAS)
                        deleteEntry(RECOVERY_ALIAS)
                    }
                }
            }
            return saved
        }

        fun setup(
            pin: String,
            allowRecovery: Boolean,
        ): Boolean {
            if (configured() || !validPin(pin)) return false
            val salt = randomBytes(SALT_SIZE)
            val vaultKey = unlockedKey?.copyOf() ?: randomBytes(KEY_SIZE)
            val pinNonce = randomBytes(NONCE_SIZE)
            val pinCipher = runCatching { wrap(pinKey(pin, salt), pinNonce, vaultKey) }.getOrNull() ?: return false
            val recovery =
                if (allowRecovery) {
                    runCatching { wrapWithKeystore(recoveryKey(), vaultKey) }.getOrNull() ?: return false
                } else {
                    null
                }
            preferences.edit()
                .putString("salt", salt.encoded())
                .putString("pin_nonce", pinNonce.encoded())
                .putString("pin_cipher", pinCipher.encoded())
                .apply {
                    if (recovery != null) {
                        putString("recovery_nonce", recovery.first.encoded())
                        putString("recovery_cipher", recovery.second.encoded())
                    }
                }.apply()
            unlockedKey = vaultKey
            return true
        }

        @Suppress("ReturnCount")
        fun unlock(pin: String): Boolean {
            if (!validPin(pin) || System.currentTimeMillis() < preferences.getLong("retry_at", 0L)) return false
            val salt = preferences.getString("salt", null)?.decoded() ?: return false
            val nonce = preferences.getString("pin_nonce", null)?.decoded() ?: return false
            val ciphertext = preferences.getString("pin_cipher", null)?.decoded() ?: return false
            val key = runCatching { unwrap(pinKey(pin, salt), nonce, ciphertext) }.getOrNull()
            if (key?.size == KEY_SIZE) {
                unlockedKey = key
                preferences.edit().remove("failures").remove("retry_at").apply()
                return true
            }
            val failures = preferences.getInt("failures", 0) + 1
            val delay = if (failures < 5) 0L else (1L shl (failures - 5).coerceAtMost(8)) * 1_000L
            preferences.edit().putInt("failures", failures)
                .putLong("retry_at", System.currentTimeMillis() + delay).apply()
            return false
        }

        /** Call only after a successful system credential confirmation. */
        @Suppress("ReturnCount")
        fun recover(): Boolean {
            val nonce = preferences.getString("recovery_nonce", null)?.decoded() ?: return false
            val ciphertext = preferences.getString("recovery_cipher", null)?.decoded() ?: return false
            val key = runCatching { unwrap(recoveryKey(), nonce, ciphertext) }.getOrNull() ?: return false
            if (key.size != KEY_SIZE) return false
            unlockedKey = key
            return true
        }

        /** Call only after a successful system biometric or device-credential prompt. */
        @Suppress("ReturnCount")
        fun unlockWithSystem(): Boolean {
            if (!biometricConfigured()) {
                // A PIN-only vault must be unlocked before adding another route to its key.
                if (configured() && !unlocked()) return false
                val vaultKey = unlockedKey?.copyOf() ?: randomBytes(KEY_SIZE)
                val (nonce, ciphertext) =
                    runCatching { wrapWithKeystore(systemKey(), vaultKey) }.getOrNull() ?: return false
                preferences.edit()
                    .putString("biometric_nonce", nonce.encoded())
                    .putString("biometric_cipher", ciphertext.encoded())
                    .apply()
                unlockedKey = vaultKey
                return true
            }
            val nonce = preferences.getString("biometric_nonce", null)?.decoded() ?: return false
            val ciphertext = preferences.getString("biometric_cipher", null)?.decoded() ?: return false
            val key = runCatching { unwrap(systemKey(), nonce, ciphertext) }.getOrNull() ?: return false
            if (key.size != KEY_SIZE) return false
            unlockedKey = key
            return true
        }

        fun lock() {
            unlockedKey?.fill(0)
            unlockedKey = null
        }

        private fun pinKey(
            pin: String,
            salt: ByteArray,
        ): SecretKey {
            val spec = PBEKeySpec(pin.toCharArray(), salt, KDF_ITERATIONS, KEY_SIZE * 8)
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(pepperKey())
            val combined = mac.doFinal(derived)
            derived.fill(0)
            return SecretKeySpec(combined, "AES")
        }

        private fun pepperKey(): SecretKey {
            val store = keyStore()
            (store.getKey(PEPPER_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    PEPPER_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setDigests(KeyProperties.DIGEST_SHA256).build(),
            )
            return generator.generateKey()
        }

        private fun recoveryKey(): SecretKey {
            val store = keyStore()
            (store.getKey(RECOVERY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    RECOVERY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(300, KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun systemKey(): SecretKey {
            val store = keyStore()
            (store.getKey(SYSTEM_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    SYSTEM_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        300,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    ).build(),
            )
            return generator.generateKey()
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun wrap(
            key: SecretKey,
            nonce: ByteArray,
            plaintext: ByteArray,
        ): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                doFinal(plaintext)
            }

        /** Keystore forbids a caller-supplied IV for encryption; persist the generated IV. */
        private fun wrapWithKeystore(
            key: SecretKey,
            plaintext: ByteArray,
        ): Pair<ByteArray, ByteArray> =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                iv to doFinal(plaintext)
            }

        private fun unwrap(
            key: SecretKey,
            nonce: ByteArray,
            ciphertext: ByteArray,
        ): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                doFinal(ciphertext)
            }

        private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

        private fun ByteArray.encoded(): String = Base64.encodeToString(this, Base64.NO_WRAP)

        private fun String.decoded(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

        companion object {
            fun validPin(pin: String): Boolean = pin.length >= 6 && pin.all(Char::isDigit)

            private const val PEPPER_ALIAS = "backgrounded.pin.pepper"
            private const val RECOVERY_ALIAS = "backgrounded.pin.recovery"
            private const val SYSTEM_ALIAS = "backgrounded.system.vault"
            private const val SALT_SIZE = 16
            private const val KEY_SIZE = 32
            private const val NONCE_SIZE = 12
            private const val KDF_ITERATIONS = 600_000
        }
    }
