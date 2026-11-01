package com.sempermechanics.semper.data.prefs

/**
 * Every private preference file the app keeps, with the keys stored in it.
 *
 * Names and keys are copied byte for byte from the objects that own each file
 * today (named on each file below); defaults are the ones those owners read
 * with. Phones already hold values under these names, so none may change.
 * A file's owner keeps its rules (coercion, migration, which key a signed-out
 * phone uses); this is only where the strings live.
 */
object PrefFiles {

    /** [AppSettings]. */
    object Settings {
        const val NAME = "dic_settings"
        val SCHEMA = PrefKey.int("schema")
        val SAVE_TO_CLOUD = PrefKey.boolean("save_to_cloud", default = true)
        val UPLOAD_WIFI_ONLY = PrefKey.boolean("upload_wifi_only")

        /** Download, on Wi-Fi, what the account's other phones backed up (`PhoneSyncWorker`). */
        val SYNC_FROM_OTHER_PHONES = PrefKey.boolean("sync_from_other_phones", default = true)
        val MAX_FRAMES = PrefKey.int("max_frames", AppSettings.DEFAULT_MAX_FRAMES)
        val AUTO_FREE_GB = PrefKey.int("auto_free_gb", AppSettings.AUTO_FREE_OFF)
        val DIAGNOSTICS_ENABLED = PrefKey.boolean("diagnostics_enabled")
        val DIAGNOSTICS_ASKED = PrefKey.boolean("diagnostics_asked")

        /** Running mean of grid points solved per second on this phone; 0 before the first run. */
        val RUN_POINTS_PER_SECOND = PrefKey.int("run_points_per_second")

        /** Retired; [AppSettings.migrate] removes it. */
        val KEEP_EVERY_RERUN = PrefKey.boolean("keep_every_rerun")
    }

    /** [ParamClipboard]. */
    object Clipboard {
        const val NAME = "param_clipboard"
        val HAS_PARAMS = PrefKey.boolean("has_params")
        val SUBSET = PrefKey.int("SUBSET_SIZE")
        val STEP = PrefKey.int("STEP")
        val STRAIN_WINDOW = PrefKey.int("STRAIN_WINDOW")
    }

    /** [CoachPrefs]: one "seen" flag per coached screen. */
    object Coach {
        const val NAME = "semper_coach"
        val HOME = PrefKey.boolean("coach_home_seen")
        val ANALYSIS_IMAGES = PrefKey.boolean("coach_analysis_images_seen")
        val ANALYSIS_SETTINGS = PrefKey.boolean("coach_analysis_settings_seen")
        val ANALYSIS_SWEEP = PrefKey.boolean("coach_analysis_sweep_seen")
        val SWEEP_LATTICE = PrefKey.boolean("coach_sweep_lattice_seen")
        val MEDIA_PICKER_REF = PrefKey.boolean("coach_media_picker_ref_seen")
        val MEDIA_PICKER_DEF = PrefKey.boolean("coach_media_picker_def_seen")

        fun seen(screen: CoachPrefs.Screen): PrefKey<Boolean> = when (screen) {
            CoachPrefs.Screen.HOME -> HOME
            CoachPrefs.Screen.ANALYSIS_IMAGES -> ANALYSIS_IMAGES
            CoachPrefs.Screen.ANALYSIS_SETTINGS -> ANALYSIS_SETTINGS
            CoachPrefs.Screen.ANALYSIS_SWEEP -> ANALYSIS_SWEEP
            CoachPrefs.Screen.SWEEP_LATTICE -> SWEEP_LATTICE
            CoachPrefs.Screen.MEDIA_PICKER_REF -> MEDIA_PICKER_REF
            CoachPrefs.Screen.MEDIA_PICKER_DEF -> MEDIA_PICKER_DEF
        }
    }

    /** [com.sempermechanics.semper.data.net.AccountCache]'s session file; cleared on sign-out. */
    object Session {
        const val NAME = "semper_session"
        val UID = PrefKey.string("uid")
        val EMAIL = PrefKey.string("email")

        /** Last server-confirmed access status. */
        val STATUS = PrefKey.string("last_status")

        /** "admin" or "user". */
        val ROLE = PrefKey.string("role")
        val DEVICE_REGISTERED = PrefKey.boolean("device_registered")

        /** The server's analysis count at the last reconcile. */
        val QUOTA_USED = PrefKey.int("quota_used")
        val LOCAL_COUNT = PrefKey.int("quota_local_count")
        val LIMIT_FORCED = PrefKey.boolean("session_limit_forced")

        /** The device half of the demo cap refused an upload (`device_quota_exceeded`). */
        val DEVICE_LIMIT_FORCED = PrefKey.boolean("device_limit_forced")
        val TERMS_REQUIRED = PrefKey.string("terms_required_version")
        val TERMS_ACCEPTED = PrefKey.string("terms_accepted_version")
        val TERMS_SYNCED = PrefKey.boolean("terms_accepted_synced")

        /** "true", "false", or absent (never asked). */
        val IMPROVEMENT_CONSENT = PrefKey.string("improvement_consent")
    }

    /** AccountCache's onboarding file, which outlives sign-out. */
    object Onboarding {
        const val NAME = "semper_onboarding"
        const val BETA_ACKED_PREFIX = "beta_notice_acked_"
        val BETA_ACKED_SIGNED_OUT = PrefKey.boolean("signed_out_beta_notice_acked")

        /** The `VersionVerdict.Recommended.nudgeKey` last shown, so the update nudge shows once. */
        val UPDATE_NUDGE = PrefKey.string("update_nudge")

        /**
         * The uid of the account that signed in last, kept through sign-out:
         * whose analyses `SessionOwner` shows while nobody is signed in.
         */
        val LAST_SIGNED_IN_UID = PrefKey.string("last_signed_in_uid")

        /** The beta-notice ack of account [uid], or the phone's own when signed out. */
        fun betaAcked(uid: String?): PrefKey<Boolean> =
            if (uid == null) BETA_ACKED_SIGNED_OUT else PrefKey.boolean(BETA_ACKED_PREFIX + uid)
    }

    /**
     * `AppRemoteConfig`: the last `/v1/config` the backend sent. Its defaults
     * are spelled out rather than read from `data.net`, so this package keeps
     * pointing one way (net → prefs); a test pins them to AppRemoteConfig's.
     */
    object RemoteConfig {
        const val NAME = "semper_remote_config"
        val MAX_SESSIONS = PrefKey.int("max_sessions")
        val MAX_FILES_PER_SESSION = PrefKey.int("max_files_per_session")
        val MAX_FRAMES = PrefKey.int("max_frames")
        val DAT_CODEC_ENCODING = PrefKey.boolean("dat_codec_encoding_enabled")
        val MODE = PrefKey.string("mode")
        val CLOUD_BACKUP = PrefKey.boolean("cloud_backup_enabled")
        val SHARE = PrefKey.boolean("share_enabled")
        val LICENSE_PREFIX = PrefKey.string("license_prefix", "")
        val LICENSE_KIND = PrefKey.string("license_kind", "")
        val FAIL_STREAK = PrefKey.int("config_fail_streak")
        val LICENSE_DURATION = PrefKey.string("license_duration", "perpetual")
        val LICENSE_EXPIRES_AT = PrefKey.long("license_expires_at", 0L)
        val IN_GRACE = PrefKey.boolean("license_in_grace")

        /** `licenseGraceEndsAt`, epoch millis; 0 = none on file. */
        val GRACE_ENDS_AT = PrefKey.long("license_grace_ends_at", 0L)
        val SEATING = PrefKey.string("license_seating", "assigned")
        val HEARTBEAT_MINUTES = PrefKey.int("lease_heartbeat_minutes")
        val FETCHED_AT = PrefKey.long("fetched_at")

        /** `minSupportedVersionCode`; 0 = none. */
        val MIN_SUPPORTED_VERSION = PrefKey.int("min_supported_version_code")

        /** `recommendedVersionCode`; 0 = none. */
        val RECOMMENDED_VERSION = PrefKey.int("recommended_version_code")

        /** `killSwitches`, by wire name. */
        val KILL_SWITCHES = PrefKey.stringSet("kill_switches")

        /** `testAccount.purpose` (`qc` / `ci`); "" = not a test account. */
        val TEST_ACCOUNT_PURPOSE = PrefKey.string("test_account_purpose", "")

        /** `testAccount.expiresAt`, epoch millis; 0 = no end on file. */
        val TEST_ACCOUNT_EXPIRES_AT = PrefKey.long("test_account_expires_at", 0L)

        /** `activation.state` (ADR-033): `active`, `available`, `full`, `floating` or `none`. */
        val ACTIVATION_STATE = PrefKey.string("activation_state", "none")

        /** `activation.devicesActive`. */
        val DEVICES_ACTIVE = PrefKey.int("activation_devices_active")

        /** `activation.maxDevices`; 0 = no count on file. */
        val MAX_DEVICES = PrefKey.int("activation_max_devices")

        /** `activation.pool`: the count is an institution's. */
        val ACTIVATION_POOL = PrefKey.boolean("activation_pool")

        /** `activation.model`: `activation` or `lock`. */
        val ACTIVATION_MODEL = PrefKey.string("activation_model", "")

        /** `pendingLicence.licenseId`; "" = no new licence waiting. */
        val PENDING_LICENCE_ID = PrefKey.string("pending_licence_id", "")

        /** `pendingLicence.prefix`. */
        val PENDING_LICENCE_PREFIX = PrefKey.string("pending_licence_prefix", "")

        /** `pendingLicence.expiresAt`, epoch millis; 0 = perpetual. */
        val PENDING_LICENCE_EXPIRES_AT = PrefKey.long("pending_licence_expires_at", 0L)

        /** The Home activation card the user hid (`HomeActivationCard.Offer.key`); "" = none. */
        val ACTIVATION_CARD_HIDDEN = PrefKey.string("activation_card_hidden", "")

        /** Written by builds before the plan → mode rename; read once on upgrade. */
        val LEGACY_PLAN = PrefKey.string("plan")
    }

    /**
     * `EntitlementStore`: the signed entitlement token and its clock state,
     * HMAC-sealed under a Keystore key. Cleared with the remote config.
     */
    object Entitlement {
        const val NAME = "semper_entitlement"
        val SEALED = PrefKey.string("sealed")

        /** Set, committed, before a first seal makes its key; cleared with the record's write. */
        val SEALING = PrefKey.boolean("sealing")
    }

    /** `DeviceKeys.deviceId`. */
    object Device {
        const val NAME = "semper_device"
        val DEVICE_ID = PrefKey.string("device_id")
    }

    /** `CloudBackupListing`: the last cloud listing and the backups hidden from Home. */
    object CloudListing {
        const val NAME = "semper_cloud_listing"

        /** JSON array of the listed backups. */
        val BACKUPS = PrefKey.string("backups")
        val HIDDEN = PrefKey.stringSet("hidden")

        /** Backups the sync from other phones has already started a restore of, once each (`PhoneSync`). */
        val SYNC_TRIED = PrefKey.stringSet("sync_tried")
    }

    /** `CloudSync.reconcile`'s throttle. */
    object CloudSyncPrefs {
        const val NAME = "semper_cloudsync"
        val LAST_RECONCILE_AT = PrefKey.long("last_reconcile_at")
    }

    /** `RestoreFailureLedger`. */
    object RestoreOutcomes {
        const val NAME = "semper_restore_outcomes"

        /** Comma-separated work ids whose failure was already announced. */
        val ANNOUNCED = PrefKey.string("announced", "")
    }

    /** `BackupFailureLedger`. */
    object BackupOutcomes {
        const val NAME = "semper_backup_outcomes"

        /** Comma-separated work ids whose failure was already announced. */
        val ANNOUNCED = PrefKey.string("announced", "")
    }

    /** `AuthRepository`'s email-link sign-in. */
    object EmailLink {
        const val NAME = "semper_emaillink"
        val PENDING_EMAIL = PrefKey.string("pending_email")
    }

    /** `DeleteFeedback`: delete outcomes already shown. */
    object SessionDeletesPrefs {
        const val NAME = "session_deletes"
        val SHOWN_OUTCOMES = PrefKey.stringSet("shown_outcomes")
    }

    /**
     * [AccountDeletionMarker]: an account erase that was sent, and how far it
     * got, so a deletion the process did not live to finish ends at the next start.
     * Outlives sign-out and the local wipe on purpose.
     */
    object AccountDeletion {
        const val NAME = "semper_account_deletion"

        /** An [AccountDeletionMarker.Stage] name, or absent when nothing is owed. */
        val STAGE = PrefKey.string("stage")

        /** The account the erase was sent for (`AccountCache.cachedUid`), "" when unknown. */
        val UID = PrefKey.string("uid", "")
    }

    /**
     * The files the first screens read: Home's beta-notice and delete-feedback
     * flags (the beta flag's key is per account, so the account file
     * [Session] is read first), the wizard's coach marks and parameter
     * clipboard, Settings' device id. Opened by [warm] at app start, on IO, so
     * their first read on the main thread finds them loaded (readiness
     * TE1-104). [Settings] is not here: [AppSettings.migrate] must be its
     * first reader.
     */
    private val FIRST_SCREENS: List<String> = listOf(
        Onboarding.NAME,
        Session.NAME,
        SessionDeletesPrefs.NAME,
        Coach.NAME,
        Clipboard.NAME,
        Device.NAME,
    )

    /** Opens [FIRST_SCREENS], which loads each from disk. Blocking: call it off the main thread. */
    @androidx.annotation.WorkerThread
    fun warm(context: android.content.Context) {
        FIRST_SCREENS.forEach { privatePrefs(context, it) }
    }

    /** Every file name above, for a test or a wipe that must not miss one. */
    val ALL_NAMES: List<String> = listOf(
        Settings.NAME,
        Clipboard.NAME,
        Coach.NAME,
        Session.NAME,
        Onboarding.NAME,
        RemoteConfig.NAME,
        Device.NAME,
        CloudListing.NAME,
        CloudSyncPrefs.NAME,
        RestoreOutcomes.NAME,
        BackupOutcomes.NAME,
        EmailLink.NAME,
        SessionDeletesPrefs.NAME,
        AccountDeletion.NAME,
        Entitlement.NAME,
    )
}
