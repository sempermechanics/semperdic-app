package com.sempermechanics.semper.fixtures

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * A default [FirebaseApp] with placeholder options, for screens that ask
 * Firebase Auth who is signed in (nobody is). Robolectric does not run the
 * google-services initializer, so without this `FirebaseAuth.getInstance()`
 * throws on the main thread. Idempotent.
 */
fun ensureTestFirebaseApp(context: Context) {
    if (FirebaseApp.getApps(context).isNotEmpty()) return
    val options = FirebaseOptions.Builder()
        .setApplicationId("1:1:android:1")
        .setApiKey("test-api-key")
        .setProjectId("test-project")
        .build()
    FirebaseApp.initializeApp(context, options)
}
