# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================
# JNI REFLECTION CONTRACT
# SemperJNI.cpp resolves this callback method BY NAME at
# runtime (GetMethodID("onProgressUpdate", "(I)V")). If R8
# renames it, the lookup returns null and engine progress
# callbacks silently stop working in release builds.
# ============================================================
-keepclassmembers class * implements com.sempermechanics.semper.ProgressCallback {
    public void onProgressUpdate(int);
}

# Native entry points: AGP's default rules keep classes with native
# methods, but be explicit — the C symbol names embed this class name.
-keep class com.sempermechanics.semper.SemperNativeLib { *; }

# ============================================================
# KOTLINX-SERIALIZATION MODELS (backend wire DTOs)
# The library ships consumer rules, but keep our own DTOs'
# serializers explicitly so a library update can't silently
# break the cloud sync payloads.
# ============================================================
-keepclassmembers @kotlinx.serialization.Serializable class com.sempermechanics.semper.** {
    *** Companion;
}
-keepclasseswithmembers class com.sempermechanics.semper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================
# ROOM-BACKED WORKMANAGER DATABASE
# WorkManager's WorkDatabase is a Room database, and Room finds
# its generated `<Database>_Impl` reflectively by canonical name.
# Under R8 full mode (AGP 8 default) that generated class and its
# no-arg constructor are stripped, so androidx.startup's
# InitializationProvider throws "Failed to create an instance of
# class androidx.work.impl.WorkDatabase" while binding the
# application — killing every release build at launch, before
# Application.onCreate ever runs.
# ============================================================
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Database class * { *; }
-dontwarn androidx.room.paging.**

# ============================================================
# WORKMANAGER INPUT MERGERS (R8 full mode)
# WorkManager instantiates InputMerger subclasses by class name
# from the WorkSpec (reflective no-arg ctor). Newer R8 strips
# unused no-arg constructors; release builds then fail uploads:
#   NoSuchMethodException: OverwritingInputMerger.<init> []
# Keep explicitly — work-runtime consumer rules vary by version.
# ============================================================
-keepnames class * extends androidx.work.InputMerger
-keepclassmembers class * extends androidx.work.InputMerger {
    public <init>();
}
-keep class androidx.work.OverwritingInputMerger { public <init>(); }
-keep class androidx.work.ArrayCreatingInputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# Keep readable crash reports from the field
-keepattributes SourceFile,LineNumberTable
