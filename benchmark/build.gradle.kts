import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.test)
}

// The app these benchmarks drive is :app's applicationId, read from :app's build
// instead of written here, so the benchmark sources run unchanged in
// material_testing, which has its own id (its ADR-009). The code reads it as
// BuildConfig.TARGET_PACKAGE, the manifest's <queries> as ${targetPackage}.
evaluationDependsOn(":app")
val targetPackage: String =
    checkNotNull(project(":app").extensions.getByType(ApplicationExtension::class.java).defaultConfig.applicationId) {
        ":app sets no applicationId"
    }

android {
    namespace = "com.sempermechanics.semper.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,LOW-BATTERY,UNLOCKED"
        buildConfigField("String", "TARGET_PACKAGE", "\"$targetPackage\"")
        manifestPlaceholders["targetPackage"] = targetPackage
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // Self-instrumenting Macrobenchmark APK; targets :app's benchmark type.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"

    // Macrobenchmarks need a non-debuggable target; use the release-like
    // :app benchmark variant (see app/build.gradle.kts).
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the Macrobenchmark variant — skip the default debug test APK.
        // AGP 9 binds this lambda as Action<TestVariantBuilder>, so the
        // builder is `it` (not a receiver).
        it.enable = it.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
