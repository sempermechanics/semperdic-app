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

    /** [DicSettings]. */
    object Settings {
        const val NAME = "dic_settings"
        val SCHEMA = PrefKey.int("schema")
        val SAVE_TO_CLOUD = PrefKey.boolean("save_to_cloud", default = true)
        val UPLOAD_WIFI_ONLY = PrefKey.boolean("upload_wifi_only")
        val MAX_FRAMES = PrefKey.int("max_frames", DicSettings.DEFAULT_MAX_FRAMES)
        val AUTO_FREE_GB = PrefKey.int("auto_free_gb", DicSettings.AUTO_FREE_OFF)
        val DIAGNOSTICS_ENABLED = PrefKey.boolean("diagnostics_enabled")
        val DIAGNOSTICS_ASKED = PrefKey.boolean("diagnostics_asked")

        /** Retired; [DicSettings.migrate] removes it. */
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

    /** [com.sempermechanics.semper.data.net.TokenStore]'s session file; cleared on sign-out. */
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
        val TERMS_REQUIRED = PrefKey.string("terms_required_version")
        val TERMS_ACCEPTED = PrefKey.string("terms_accepted_version")
        val TERMS_SYNCED = PrefKey.boolean("terms_accepted_synced")

        /** "true", "false", or absent (never asked). */
        val IMPROVEMENT_CONSENT = PrefKey.string("improvement_consent")
    }

    /** TokenStore's onboarding file, which outlives sign-out. */
    object Onboarding {
        const val NAME = "semper_onboarding"
        const val BETA_ACKED_PREFIX = "beta_notice_acked_"
        val BETA_ACKED_SIGNED_OUT = PrefKey.boolean("signed_out_beta_notice_acked")

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
        val SEATING = PrefKey.string("license_seating", "assigned")
        val HEARTBEAT_MINUTES = PrefKey.int("lease_heartbeat_minutes")
        val FETCHED_AT = PrefKey.long("fetched_at")

        /** Written by builds before the plan → mode rename; read once on upgrade. */
        val LEGACY_PLAN = PrefKey.string("plan")
    }

    /** `DeviceKeyManager.deviceId`. */
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
    }

    /** `CloudSync.reconcile`'s throttle. */
    object CloudSync {
        const val NAME = "semper_cloudsync"
        val LAST_RECONCILE_AT = PrefKey.long("last_reconcile_at")
    }

    /** `RestoreFailureLedger`. */
    object RestoreOutcomes {
        const val NAME = "semper_restore_outcomes"

        /** Comma-separated work ids whose failure was already announced. */
        val ANNOUNCED = PrefKey.string("announced", "")
    }

    /** `AuthRepository`'s email-link sign-in. */
    object EmailLink {
        const val NAME = "semper_emaillink"
        val PENDING_EMAIL = PrefKey.string("pending_email")
    }

    /** `DeleteFeedback`: delete outcomes already shown. */
    object SessionDeletes {
        const val NAME = "session_deletes"
        val SHOWN_OUTCOMES = PrefKey.stringSet("shown_outcomes")
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
        CloudSync.NAME,
        RestoreOutcomes.NAME,
        EmailLink.NAME,
        SessionDeletes.NAME,
    )
}
