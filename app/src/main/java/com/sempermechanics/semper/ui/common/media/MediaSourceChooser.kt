package com.sempermechanics.semper.ui.common.media

import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity

/**
 * Asks *where* to pick media from, in a sheet styled like the rest of the app.
 *
 * Images lists the gallery in the sheet (default). Files dismisses the sheet
 * and opens the system picker (SAF) for Drive, storage, or DNG.
 */
object MediaSourceChooser {

    enum class Mode {
        HOME_REFERENCE,
        REFERENCE,
        DEFORMED,
    }

    fun requiredPermissions(includeVideo: Boolean): Array<String> =
        MediaStoreBrowser.permissions(Build.VERSION.SDK_INT, includeVideo)

    fun show(
        activity: AppCompatActivity,
        mode: Mode,
        requestPermission: () -> Unit,
        onBrowseSaf: () -> Unit,
        onPicked: (List<Uri>) -> Unit,
    ): MediaPickerSheet = MediaPickerSheet.show(
        activity = activity,
        mode = mode,
        requestPermission = requestPermission,
        onBrowseSaf = onBrowseSaf,
        onPicked = onPicked,
    )
}
