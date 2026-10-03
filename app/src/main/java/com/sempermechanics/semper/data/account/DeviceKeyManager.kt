package com.sempermechanics.semper.data.account

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.sempermechanics.semper.data.prefs.PrefFiles.Device
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Device identity for the challenge-response device binding.
 *
 * - A non-exportable **EC P-256** key pair lives in the Android Keystore; the
 *   private key never leaves the device. The public key is uploaded once at
 *   registration and stored in Firestore by the backend.
 * - The device id is persisted in app-private storage on first use so it never
 *   flips between `and-{ANDROID_ID}` and a legacy `dev-{uuid}`.
 * - [signMessage] produces an ECDSA-SHA256 signature the backend verifies with
 *   the stored public key (see backend/app/deps.py).
 */
class DeviceKeyManager(private val context: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val KEY_ALIAS = "SemperDeviceKeyEc"

        /** PEM wraps its base64 body at 64 characters a line (RFC 7468). */
        private const val PEM_LINE_LENGTH = 64

        // The infamous Android 2.2 bug value shared by many devices — never use it.
        private const val LEGACY_BAD_ANDROID_ID = "9774d56d682e549c"

        /**
         * Stable device id persisted on first use. Prefers a previously stored
         * value so an upgrade cannot flip `dev-{uuid}` to `and-{ANDROID_ID}`. If
         * none is stored, writes `and-{ANDROID_ID}` (app-scoped, survives
         * reinstall) or a `dev-{uuid}` fallback.
         *
         * Needs no Keystore, unlike constructing a [DeviceKeyManager] (which
         * loads the Keystore and may generate a key): screens that only show or
         * mail the id call this, so a Keystore fault cannot crash them.
         */
        @Suppress("HardwareIds") // ANDROID_ID is app-scoped, not a hardware identifier
        fun deviceId(context: Context): String {
            val prefs = privatePrefs(context, Device.NAME)
            prefs[Device.DEVICE_ID]?.let { return it }
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
            val id = if (!androidId.isNullOrBlank() && androidId != LEGACY_BAD_ANDROID_ID) {
                "and-$androidId"
            } else {
                "dev-" + UUID.randomUUID().toString()
            }
            prefs.edit { put(Device.DEVICE_ID, id) }
            return id
        }
    }

    init {
        generateDeviceKeyIfNeeded()
    }

    private fun generateDeviceKeyIfNeeded() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
    }

    /** This device's id; see [deviceId]. */
    fun getDeviceId(): String = deviceId(context)

    /** SubjectPublicKeyInfo as a standard PEM block (parsed by the backend). */
    fun getPublicKeyPem(): String {
        val der = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val wrapped = b64.chunked(PEM_LINE_LENGTH).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----\n"
    }

    /** ECDSA-SHA256 over [message]; returns base64(DER) for the X-Signature header. */
    fun signMessage(message: ByteArray): String {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }
}
