import com.android.build.api.artifact.SingleArtifact
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    // Coverage: `koverVerify` enforces the floor below (CI tier 1 and
    // ciReleaseGate); `./gradlew :app:koverHtmlReport` → app/build/reports/kover/.
    alias(libs.plugins.kover)
}

// Read local.properties directly rather than via java.util.Properties, so the
// build script stays pure Kotlin.
val localPropertiesFile = rootProject.file("local.properties")

/** Value of [key] in local.properties, or null when the file/key is absent. */
fun localProperty(key: String): String? =
    if (localPropertiesFile.exists()) {
        localPropertiesFile
            .readLines()
            .find { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } else {
        null
    }

/**
 * [key], or its pre-rename `INDIC_*` spelling, so a local.properties or CI var
 * from before the move to com.sempermechanics still works.
 */
fun semperProperty(key: String): String? = localProperty(key) ?: localProperty(key.replaceFirst("SEMPER_", "INDIC_"))

fun semperEnv(key: String): String? =
    (System.getenv(key) ?: System.getenv(key.replaceFirst("SEMPER_", "INDIC_")))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

// Debug-only convenience: on an emulator, skip sign-in and run the app as a
// local-only dev account, even when SEMPER_API_BASE_URL is set. Cloud calls are
// switched off along with it (see DevAuth), so nothing hits the backend
// unauthenticated. Release builds never get this — the field is hardcoded false
// below. Put SEMPER_DEV_AUTH_BYPASS=false in local.properties to exercise the
// real sign-in flow on an emulator.
val devAuthBypass = semperProperty("SEMPER_DEV_AUTH_BYPASS") != "false"
// Base URL of the Semper GCP backend (API Gateway or Cloud Run). Empty = cloud
// sync disabled for local/debug. Release builds that set -PrequireCloudApi=true
// (CI Release workflow) MUST inject SEMPER_API_BASE_URL via env or
// local.properties — otherwise the build fails instead of silently shipping
// offline-only.
val semperApiBaseUrl =
    semperEnv("SEMPER_API_BASE_URL")
        ?: semperProperty("SEMPER_API_BASE_URL")
        ?: ""

val requireCloudApi =
    (project.findProperty("requireCloudApi") as String?)?.equals("true", ignoreCase = true) == true
if (requireCloudApi && semperApiBaseUrl.isBlank()) {
    throw GradleException(
        "SEMPER_API_BASE_URL is required for this release build " +
            "(-PrequireCloudApi=true). Set the env var or local.properties entry " +
            "to the API Gateway URL so cloud sync is not silently disabled.",
    )
}
if (requireCloudApi && !semperApiBaseUrl.startsWith("https://")) {
    throw GradleException("SEMPER_API_BASE_URL must be https when requireCloudApi=true: $semperApiBaseUrl")
}

// Optional comma-separated CertificatePinner pins for the API host
// (e.g. sha256/AAAA...=). Empty = system trust store only.
val semperApiCertPins = semperProperty("SEMPER_API_CERT_PINS") ?: ""

// Release signing. The keystore and passwords come from the environment
// (SIGNING_* — set by .github/workflows/release.yml and the tier-5 CI job),
// never from the repo. When the keystore is absent — every local build, and
// any CI run without the secrets — the release variant simply stays unsigned
// instead of failing, so `assembleRelease` still works for inspection.
val releaseKeystore =
    System
        .getenv("SIGNING_KEYSTORE")
        ?.takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

android {
    namespace = "com.sempermechanics.semper"
    // core-ktx 1.19+ (gradle-deps) requires compileSdk 37+ (AAR metadata).
    compileSdk = 37
    // Pinned (TD-37): the engine builds with -ffast-math and the .dat oracles
    // are bit-exact, so a compiler change must be a deliberate edit, not a side
    // effect of an AGP bump. This is AGP 9.3.2's default NDK as of 2026-09-23.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.sempermechanics.semper"
        minSdk = 24
        targetSdk = 36
        // Overridable from the release workflow: -PversionCode / -PversionName.
        // Every build handed to anyone must bump versionCode (see docs/ops/RELEASING.md).
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0"

        buildConfigField("String", "SEMPER_API_BASE_URL", "\"$semperApiBaseUrl\"")
        buildConfigField("String", "SEMPER_API_CERT_PINS", "\"$semperApiCertPins\"")
        // Overridden per build type below; the default keeps the flag defined
        // for any variant that doesn't set it (androidTest, lint models).
        buildConfigField("boolean", "DEV_AUTH_BYPASS", "false")

        // Ship arm64-v8a only. Every Android phone from ~2019 onward is 64-bit
        // ARM, so a 2022+ target needs nothing else; armeabi-v7a (32-bit) and
        // x86/x86_64 (emulators only) would just bloat the release APK with
        // native code no real device runs. "Test what you ship": CI builds
        // exactly this ABI, and so does the release build type.
        //
        // The debug build type adds x86_64 on top of this (see buildTypes) so
        // emulator runs work without anyone having to remember a flag.
        //
        // Local override: -PabiFilters=x86_64 pins the build to one ABI
        // (comma-separated for several), for a faster single-target build.
        val requestedAbis =
            (project.findProperty("abiFilters") as String?)
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?: listOf("arm64-v8a")
        ndk { abiFilters.addAll(requestedAbis) }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Portable engine package at repo-root engine/; JNI adapter on.
                arguments += "-DSEMPER_ANDROID=ON"

                // Vendored OpenCV defaults ENABLE_CCACHE to ON for Ninja builds,
                // and when it finds a ccache on PATH it installs it as a GLOBAL
                // RULE_LAUNCH_COMPILE — so it wraps our targets too, not just
                // OpenCV's. Upstream hardcodes IS_CCACHE_WORKS=1 (its own check
                // is commented out as non-functional), so a ccache that fails to
                // start takes down every translation unit with no compiler
                // diagnostic at all. Not a trade we want for a cache.
                arguments += "-DENABLE_CCACHE=OFF"

                // ...but an explicitly requested launcher is a different
                // matter: CI opts in with -PnativeCompilerLauncher=ccache,
                // where the cache is content-addressed and so survives the
                // fresh mtimes that actions/cache gives every restored object
                // file (which is what made the .cxx cache hit and still
                // recompile everything). Unset locally, so nothing changes for
                // developers unless they ask for it.
                val launcher =
                    (project.findProperty("nativeCompilerLauncher") as String?)
                        ?.takeIf { it.isNotBlank() }
                if (launcher != null) {
                    arguments += "-DCMAKE_C_COMPILER_LAUNCHER=$launcher"
                    arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=$launcher"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
        // Screens are moving from findViewById to generated bindings, one screen
        // per PR; until every screen has moved, both styles coexist.
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            releaseKeystore?.let { ks ->
                storeFile = ks
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_AUTH_BYPASS", "$devAuthBypass")
            // Enables JVM unit-test coverage collection for Kover/JaCoCo tooling.
            enableUnitTestCoverage = true

            // Emulators are x86_64. An arm64-only APK does install there and
            // runs under ARM translation (berberis), but libomp aborts inside
            // __kmp_parallel_initialize the moment the engine opens a parallel
            // region — SIGABRT with no usable diagnostic. Carrying x86_64 in
            // every debug APK means the emulator runs native code whatever
            // installs it, including Android Studio's Run button, which cannot
            // pass -PabiFilters. Costs one extra OpenCV compile (cached after
            // the first) and APK size that never reaches a user; opt out with
            // -PabiFilters=arm64-v8a.
            if (project.findProperty("abiFilters") == null) {
                ndk { abiFilters.add("x86_64") }
            }
        }
        release {
            // Never in a shipped build, whatever local.properties says.
            buildConfigField("boolean", "DEV_AUTH_BYPASS", "false")
            isMinifyEnabled = true // 🚀 THE SHREDDER IS NOW ON
            isShrinkResources = true // 🚀 Destroys unused files
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the release key when the keystore is present (CI with
            // secrets). Absent it, the variant stays unsigned rather than
            // silently debug-signed, so an unsigned APK never masquerades as a
            // release build.
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Local builds stay offline: the plugin still injects the build-ID
            // resource the SDK needs and skips the mapping upload. The release
            // workflow passes -PuploadCrashlyticsMapping=true so Crashlytics keeps
            // the R8 mapping privately for the project's life (TD-40); the
            // workflow artifact alone expires after 90 days, and attaching it to
            // the public GitHub Release would undo the obfuscation.
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled =
                    (project.findProperty("uploadCrashlyticsMapping") as String?)
                        ?.equals("true", ignoreCase = true) == true
            }
        }
        // Non-debuggable, debug-signed release-like variant for Macrobenchmark.
        // CI runs on an x86_64 AVD, so carry that ABI the same way debug does.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            if (project.findProperty("abiFilters") == null) {
                ndk { abiFilters.add("x86_64") }
            }
        }
    }

    // No ABI splits: the app targets a single ABI (arm64-v8a, see abiFilters
    // above), so each build produces one APK (e.g. app-debug.apk) — there is
    // nothing to split per architecture.

    // 🚀 NEW: Ensures C++ debug symbols are physically stripped from the final APK
    packaging {
        jniLibs {
            keepDebugSymbols.clear()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../engine/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests {
            // Robolectric (UploadResumableTest) needs the real resource table.
            isIncludeAndroidResources = true
        }
    }

    // Android Lint gate: empty baseline; new issues fail CI.
    // Version-availability noise is silenced — bumps are deliberate catalog PRs.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        // Layouts are fully extracted to strings.xml — keep it that way.
        error += "HardcodedText"
        disable +=
            setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                // Photo Picker is the primary path; broad gallery access is unused.
                "SelectedPhotoAccess",
                // targetSdk 36 is deliberate until a dedicated bump PR (TECH_DEBT).
                "OldTargetApi",
            )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    // Firebase Authentication (email/password, email-link, Google) — the identity layer.
    implementation(libs.firebase.auth)
    // App Check: proves to the backend that a device caller is a genuine,
    // unmodified build of this app. The Web API key that mints ID tokens ships
    // inside the APK and is an identifier, not a secret, so an ID token alone
    // cannot make that claim. Play Integrity is the only provider installed —
    // the debug provider would need a per-install secret registered by hand in
    // the Firebase console, and the interceptor fails open, so a developer
    // build simply sends no header against APP_CHECK_MODE=off / monitor.
    implementation(libs.firebase.appcheck.playintegrity)
    // Crash + non-fatal reporting (field visibility for release builds). The
    // Crashlytics Gradle plugin (applied above) injects the build-ID resource the
    // SDK requires at startup; mapping-file upload is disabled below so no build-time
    // network/credentials are needed. Non-fatals and breadcrumbs from
    // CrashReportingTree still flow.
    implementation(libs.firebase.crashlytics)
    // Await() on Firebase Task<T> from coroutines.
    implementation(libs.kotlinx.coroutines.play.services)
    // AndroidX transitions power the shared-element / expand-collapse motion in ui/Motion.kt
    implementation(libs.androidx.transition)
    // Pull-to-refresh on the Home list (re-checks cloud backup state on demand)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    // JVM tests for the network layer: MockWebServer fakes the backend/Drive,
    // Robolectric supplies a real Context + org.json without a device.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.mockwebserver.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    // WorkManagerTestInitHelper: Robolectric tests that launch HomeActivity.
    testImplementation(libs.androidx.work.testing)
    // runTest / virtual time for coroutine code under test.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    // 3.6.1 crashes on API 37 (Espresso's InputManagerEventInjectionStrategy
    // calls the hidden InputManager.getInstance, removed in Android 17).
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    // Microbenchmark: median timeNs + allocationCount for the round-2 hot paths
    // (HotPathMicroBenchmark). Same version as the macro library.
    androidTestImplementation(libs.androidx.benchmark.junit4)

    // Google SSO via Credential Manager (native one-tap) → Google ID token
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Semper GCP backend client: OkHttp + kotlinx.serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.timber)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.recyclerview)
    // Baseline Profile / Macrobenchmark companion — installs profiles at first run.
    implementation(libs.androidx.profileinstaller)
}

// Static analysis gate: `./gradlew :app:detekt` (CI). New findings fail the
// build. A few large UI orchestration files use targeted @file:Suppress for
// inherent size/complexity; keep that list small and prefer extracts instead.
detekt {
    buildUponDefaultConfig = true
    // Empty baseline retained so `./gradlew :app:detektBaseline` can still
    // freeze accidental regressions deliberately if needed.
    baseline = file("detekt-baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
}

// Coverage: exclude view classes only — ViewModels and the pure helpers that
// live alongside them (AnalysisViewModel, FrameOrderHelper, …) are the app's
// highest-churn logic and were invisible while the whole `ui` package was
// excluded. Android view/entry-point classes stay out: they need an emulator,
// not JVM unit tests, so counting them would only depress the floor.
kover {
    reports {
        filters {
            excludes {
                // Trailing `*` also swallows nested/synthetic classes
                // (SettingsActivity$Companion, lambdas) without needing a `$`
                // literal in a Kotlin string.
                classes(
                    "com.sempermechanics.semper.ui.*Activity*",
                    "com.sempermechanics.semper.ui.*Adapter*",
                    "com.sempermechanics.semper.ui.*Fragment*",
                    "com.sempermechanics.semper.ui.*Dialog*",
                    // Generated ViewBinding classes: no logic of ours to cover.
                    "com.sempermechanics.semper.databinding.*",
                )
            }
        }
        verify {
            // Two points under the measured line coverage (51.7 % on 2026-09-24,
            // after the TD-57 view-class tests), so churn does not fail unrelated
            // PRs while a real drop does. Raise it as coverage climbs; never lower it.
            rule {
                minBound(49)
            }
        }
    }
}

/**
 * Fails the build when a foreground service reaches the *merged* manifest
 * without the service type it needs.
 *
 * Android 14+ refuses to start a typeless foreground service
 * (`InvalidForegroundServiceTypeException: Starting FGS with type none … has
 * been prohibited`). The declaration at fault usually comes from a library
 * manifest — WorkManager ships SystemForegroundService with no type — so it is
 * invisible in app sources and cannot be seen by a JVM unit test either. This
 * reads the merged XML, which is the artifact that actually ships.
 */
abstract class VerifyForegroundServiceTypes : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    /** Fully-qualified service name → a type its declaration must include. */
    @get:Input
    abstract val required: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)

        val nodes = document.getElementsByTagName("service")
        val declared =
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .associate {
                    it.getAttributeNS(androidNs, "name") to
                        it.getAttributeNS(androidNs, "foregroundServiceType")
                }

        val problems =
            required.get().toSortedMap().mapNotNull { (service, type) ->
                val actual = declared[service]
                when {
                    actual == null -> "$service is missing from the merged manifest"
                    type !in actual.split('|') ->
                        "$service declares foregroundServiceType=\"$actual\", " +
                            "which does not include \"$type\""
                    else -> null
                }
            }

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString(
                    prefix = "Foreground service type check failed:\n  - ",
                    separator = "\n  - ",
                    postfix = "\nAndroid 14+ kills a foreground service started with type none.",
                ),
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val verifyTask =
            tasks.register<VerifyForegroundServiceTypes>(
                "verify${suffix}ForegroundServiceTypes",
            ) {
                group = "verification"
                description = "Checks merged-manifest foreground service types ($suffix)."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                required.set(
                    mapOf(
                        // The app manifest merges dataSync onto this; the type passed
                        // to ForegroundInfo in TransferNotifications must be a subset.
                        "androidx.work.impl.foreground.SystemForegroundService" to "dataSync",
                    ),
                )
            }
        // Runs inside CI Tier 1, which is the only tier that always executes.
        tasks.matching { it.name == "test${suffix}UnitTest" }.configureEach {
            dependsOn(verifyTask)
        }
    }
}
