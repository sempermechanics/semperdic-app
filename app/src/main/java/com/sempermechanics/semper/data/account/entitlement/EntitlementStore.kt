package com.sempermechanics.semper.data.account.entitlement

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import com.sempermechanics.semper.data.prefs.remove
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The phone side of entitlement tokens: where the token and its clock state
 * live, and the Keystore, clock and device readings the pure code needs.
 * Every rule is in [EntitlementPolicy], [EntitlementVerifier],
 * [EntitlementClock] and [SealedRecordStore]; this only reads and writes.
 *
 * A build with no pinned keys (`SEMPER_ENTITLEMENT_PUBKEYS` empty, every
 * debug and CI build) returns [TokenStatus.NotUsed] before touching the
 * Keystore or the disk, so it behaves exactly as builds before tokens did.
 *
 * Storage: one string in [PrefFiles.Entitlement], sealed with HMAC-SHA256
 * under a non-exportable Keystore key ([HMAC_ALIAS]), written with
 * `commit()` so it is on disk before anything relies on it; only the clock's
 * once-a-minute write over a record already in place uses `apply()`, so a
 * gate check on the main thread does not wait on the disk (readiness TE1-006).
 * SemperApp reads it once off the main thread at start. The key doubles
 * as a marker: it exists only while a token is stored, so a deleted record
 * with the key still present reads as tampered, unless the first-seal mark
 * ([PrefFiles.Entitlement.SEALING]) says the process died before the record
 * was written; a record whose key is gone reads as no token
 * ([SealedRecordStore]). The verification and the clock live in memory
 * after the first read in a process, and the clock is written back at most
 * once a minute, so a gate check costs no ECDSA and no disk.
 */
object EntitlementStore {

    private const val HMAC_ALIAS = "SemperEntitlementHmac"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val HMAC = "HmacSHA256"
    private const val MS_PER_SECOND = 1000L
    private const val PERSIST_EVERY_MS = 60_000L

    private val builtPins: EntitlementPins by lazy {
        EntitlementPins.parse(BuildConfig.SEMPER_ENTITLEMENT_PUBKEYS).also {
            if (it.invalid.isNotEmpty()) Timber.w("Ignoring unusable entitlement pins: %s", it.invalid)
        }
    }

    /**
     * Test-only pins in place of the build's, so a device test can run the
     * pinned path (Keystore seal, real device key, clock) that every CI build,
     * which pins none, skips (readiness TE4-101). Null in the app. Setting it
     * also drops what this process holds in memory, so the next read loads
     * the record from disk and verifies it again.
     */
    @VisibleForTesting
    @Volatile
    internal var testPins: EntitlementPins? = null
        set(value) = synchronized(lock) {
            field = value
            memory = null
            verified = null
        }

    private val pins: EntitlementPins get() = testPins ?: builtPins

    /** A boot count cannot change while this process lives. */
    @Volatile
    private var bootCount: Int? = null

    private val lock = Any()

    /** Loaded on first use only: a build with no pins never opens the Keystore. */
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    /** What is stored, as last read or written; null until the first read in this process. */
    private var memory: StoredEntitlement? = null

    /**
     * The last verification, keyed by its token and the account it was
     * checked for, so the same token is not verified twice.
     */
    private var verified: Pair<Pair<String, String?>, Verification>? = null

    /** `elapsedRealtime` of the last write; starts a full interval back so the first one is due. */
    private var lastPersistElapsed = -PERSIST_EVERY_MS

    /**
     * Called first by every public entry ([status], [refreshDue], [accept], [clear]), before
     * the no-pins return: tests see which thread a caller reads on, even in a build without
     * pins (readiness TE1-006). Null in the app.
     */
    @VisibleForTesting
    @Volatile
    internal var onCall: (() -> Unit)? = null

    /** The token's status, and the device's best estimate of the server's clock, at one reading. */
    private class Reading(val status: TokenStatus, val effectiveNowMs: Long)

    /** The stored token's status now. */
    fun status(context: Context): TokenStatus {
        onCall?.invoke()
        if (pins.isEmpty) return TokenStatus.NotUsed
        return read(context.applicationContext).status
    }

    /**
     * Whether the stored token asks for a fresh one now: its `refreshAfter`
     * has passed on the device's estimate of the server's clock, or it has
     * lapsed ([EntitlementRefresh.refreshDue]). False with no token in play.
     */
    fun refreshDue(context: Context): Boolean {
        onCall?.invoke()
        if (pins.isEmpty) return false
        val reading = read(context.applicationContext)
        return EntitlementRefresh.refreshDue(reading.status, reading.effectiveNowMs)
    }

    private fun read(appContext: Context): Reading = synchronized(lock) {
        val now = deviceTime(appContext)
        val stored = memory ?: load(appContext).also { memory = it }
        val verification = (stored as? StoredEntitlement.Sealed)?.let { verify(appContext, it.record.token) }
        val (status, clock) = EntitlementPolicy.evaluate(stored, verification, now)
        if (stored is StoredEntitlement.Sealed && clock != null && clock != stored.record.clock) {
            val next = StoredEntitlement.Sealed(stored.record.copy(clock = clock))
            memory = next
            val urgent = clock.rolledBack != stored.record.clock.rolledBack
            // A rollback is written durably at once; the periodic clock write is the quick one.
            if (urgent || now.elapsedMs - lastPersistElapsed >= PERSIST_EVERY_MS) {
                save(appContext, next.record, now, quick = !urgent)
            }
        }
        if (status is TokenStatus.Lapsed) Timber.d("Entitlement token lapsed: %s", status.why)
        Reading(status, clock?.let { EntitlementClock.effectiveNowMs(it, now) } ?: now.wallMs)
    }

    /**
     * A `/v1/config`-shaped answer arrived carrying [token] (or none). Only
     * the `/v1/config` answer is [authoritative] about a missing token.
     * [serverNowMs] is the server's clock as the API's `Date` header taught it,
     * when known; the token's `iat` is used too, whichever is later.
     */
    fun accept(context: Context, token: String?, authoritative: Boolean, serverNowMs: Long?) {
        onCall?.invoke()
        val appContext = context.applicationContext
        when (EntitlementPolicy.intake(!pins.isEmpty, token, authoritative)) {
            TokenIntake.IGNORE, TokenIntake.KEEP -> Unit
            TokenIntake.CLEAR -> clear(appContext)
            TokenIntake.STORE -> synchronized(lock) { store(appContext, checkNotNull(token), serverNowMs) }
        }
    }

    /** Forget the token, its clock and its key (sign-out, or a backend that sends none). */
    fun clear(context: Context) {
        onCall?.invoke()
        if (pins.isEmpty) return
        synchronized(lock) {
            runCatching { SealedRecordStore.clear(PhoneDisk(context)) }
                .onFailure { Timber.w(it, "Could not drop the entitlement record") }
            memory = StoredEntitlement.Nothing
            verified = null
        }
    }

    private fun store(context: Context, token: String, serverNowMs: Long?) {
        val now = deviceTime(context)
        val verification = verify(context, token)
        if (verification is Verification.Rejected) Timber.w("Entitlement token refused: %s", verification.reason)
        val iatMs = (verification as? Verification.Verified)?.claims?.iat?.times(MS_PER_SECOND)
        val serverMs = maxOf(iatMs ?: 0L, serverNowMs ?: 0L).takeIf { it > 0L } ?: now.wallMs
        val previous = (memory ?: load(context)) as? StoredEntitlement.Sealed
        val clock = EntitlementClock.observeServer(previous?.record?.clock, serverMs, now)
        val record = SealedEntitlement.Record(token, clock)
        memory = StoredEntitlement.Sealed(record)
        save(context, record, now)
    }

    private fun load(context: Context): StoredEntitlement =
        SealedRecordStore.load(PhoneDisk(context)).also {
            if (it == StoredEntitlement.Tampered) Timber.w("Entitlement record failed its integrity check")
        }

    /**
     * Seals [record] to disk: durably (`commit()`), or when [quick] for the
     * periodic clock write, over the record already in place without waiting
     * for the disk ([SealedRecordStore.update], readiness TE1-006).
     */
    private fun save(context: Context, record: SealedEntitlement.Record, now: DeviceTime, quick: Boolean = false) {
        runCatching {
            val disk = PhoneDisk(context)
            if (quick) true.also { SealedRecordStore.update(disk, record) } else SealedRecordStore.save(disk, record)
        }
            .onSuccess { written ->
                if (written) lastPersistElapsed = now.elapsedMs else Timber.w("Entitlement record not written")
            }
            .onFailure { Timber.w(it, "Could not seal the entitlement record") }
    }

    /**
     * [token] checked against this install and the account signed in here
     * ([AccountCache.cachedUid]), so a token another account's answer
     * carried is not taken for this one's.
     */
    private fun verify(context: Context, token: String): Verification {
        val account = AccountCache.cachedUid(context)?.takeIf { it.isNotBlank() }
        val key = token to account
        verified?.takeIf { it.first == key }?.let { return it.second }
        val expected = runCatching {
            EntitlementVerifier.Expected(
                appId = EntitlementApp.shortId(BuildConfig.APPLICATION_ID),
                deviceId = DeviceKeys.deviceId(context),
                deviceKeyThumbprint = DeviceKeyThumbprint.of(DeviceKeys(context).publicKeyDer()),
                accountUid = account,
            )
        }.onFailure { Timber.w(it, "Could not read the device key for the entitlement check") }.getOrNull()
        val result = expected?.let { EntitlementVerifier(pins).verify(token, it) }
            ?: Verification.Rejected(RejectReason.WRONG_DEVICE_KEY)
        verified = key to result
        return result
    }

    private fun deviceTime(context: Context): DeviceTime {
        val boot = bootCount ?: Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
            DeviceTime.UNKNOWN_BOOT,
        ).also { bootCount = it }
        return DeviceTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot)
    }

    /** The record and its first-seal mark in one preference file, committed; the key in the Keystore. */
    private class PhoneDisk(context: Context) : EntitlementDisk {

        private val prefs: SharedPreferences = privatePrefs(context, PrefFiles.Entitlement.NAME)

        override val tagger = Tagger { data ->
            Mac.getInstance(HMAC).run {
                init(checkNotNull(keyStore.getKey(HMAC_ALIAS, null) as? SecretKey) { "No entitlement key" })
                doFinal(data)
            }
        }

        override fun record(): String? = prefs[PrefFiles.Entitlement.SEALED]

        override fun sealing(): Boolean = prefs[PrefFiles.Entitlement.SEALING]

        override fun key(): KeyPresence = runCatching { keyStore.containsAlias(HMAC_ALIAS) }
            .onFailure { Timber.w(it, "Could not read the Keystore for the entitlement key") }
            .map { if (it) KeyPresence.PRESENT else KeyPresence.ABSENT }
            .getOrDefault(KeyPresence.UNREADABLE)

        override fun markSealing() {
            val written = prefs.edit()
                .remove(PrefFiles.Entitlement.SEALED)
                .put(PrefFiles.Entitlement.SEALING, true)
                .commit()
            check(written) { "First-seal mark not written" }
        }

        override fun createKey() {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }
        }

        override fun deleteKey() {
            keyStore.deleteEntry(HMAC_ALIAS)
        }

        override fun commit(record: String?): Boolean = prefs.edit()
            .put(PrefFiles.Entitlement.SEALED, record)
            .remove(PrefFiles.Entitlement.SEALING)
            .commit()

        override fun update(record: String) {
            prefs.edit().put(PrefFiles.Entitlement.SEALED, record).apply()
        }
    }
}
