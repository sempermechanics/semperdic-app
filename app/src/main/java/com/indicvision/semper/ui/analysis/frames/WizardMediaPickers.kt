package com.indicvision.semper.ui.analysis.frames

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.ui.common.media.MediaPickerSheet
import com.indicvision.semper.ui.common.media.MediaSourceChooser

/**
 * Page 1's pickers: the Photos sheet, with Files (SAF) still reaching
 * DNG/RAW and Drive, which MediaStore may not index. A picked reference goes
 * to [onReference], picked frames to [onDeformed].
 *
 * Construct it in `onCreate`, before the ROI studio's launcher: it registers
 * three result launchers, and their order is how a result finds its launcher
 * after a process death.
 */
class WizardMediaPickers(
    private val activity: AppCompatActivity,
    private val onReference: (Uri) -> Unit,
    private val onDeformed: (List<Uri>) -> Unit,
) {
    private var mediaPicker: MediaPickerSheet? = null

    private val requestMediaPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPicker?.onPermissionResult()
        }
    private val pickRefFiles =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onReference)
        }
    private val pickDefFiles =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            onDeformed(uris)
        }

    fun pickReference() {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.REFERENCE,
            requestPermission = ::requestPermission,
            onBrowseSaf = { pickRefFiles.launch(arrayOf(IMAGES)) },
            onPicked = { uris -> uris.firstOrNull()?.let(onReference) },
        )
    }

    fun pickDeformed() {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.DEFORMED,
            requestPermission = ::requestPermission,
            onBrowseSaf = { pickDefFiles.launch(arrayOf(IMAGES)) },
            onPicked = onDeformed,
        )
    }

    private fun requestPermission() {
        requestMediaPermission.launch(MediaSourceChooser.requiredPermissions(includeVideo = false))
    }

    private companion object {
        const val IMAGES = "image/*"
    }
}
