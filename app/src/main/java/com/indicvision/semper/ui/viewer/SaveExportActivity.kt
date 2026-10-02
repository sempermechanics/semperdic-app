package com.indicvision.semper.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.dialog.Feedback
import com.indicvision.semper.util.Mime
import kotlinx.coroutines.launch
import java.io.File

/**
 * Transparent proxy launched from the system share chooser as the "Save to Files"
 * destination. Opens SAF, copies the staged file, then finishes.
 */
@MainThread
class SaveExportActivity : AppCompatActivity() {

    private val copier: SaveExportViewModel by viewModels()

    private var pendingFile: File? = null

    /** True while the document picker is open; its result may reach a recreated instance. */
    private var awaitingPicker = false

    /** The picked document, kept so a rotation mid-copy can still finish the copy. */
    private var destUri: Uri? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        awaitingPicker = false
        val uri = result.data?.data
        val file = pendingFile?.takeIf { it.exists() }
        if (result.resultCode != RESULT_OK || uri == null || file == null) {
            finish()
            return@registerForActivityResult
        }
        destUri = uri
        copyInto(uri, file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val mime = intent.getStringExtra(EXTRA_MIME) ?: Mime.ANY
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            Feedback.toast(this, R.string.save_failed)
            finish()
            return
        }
        pendingFile = file
        if (savedInstanceState == null) {
            awaitingPicker = true
            createDocument.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, file.name)
                },
            )
            return
        }
        // Recreated (a rotation, or the process came back). Launching the picker
        // again would stack a second one over the first.
        awaitingPicker = savedInstanceState.getBoolean(STATE_AWAITING_PICKER)
        val uri = BundleCompat.getParcelable(savedInstanceState, STATE_DEST_URI, Uri::class.java)
        when {
            // The picker still open answers this instance.
            awaitingPicker -> Unit
            // A copy was started: wait on it (still running after a rotation),
            // or run it again after process death, rather than leave this
            // transparent proxy over the app doing nothing.
            uri != null -> {
                destUri = uri
                copyInto(uri, file)
            }
            else -> finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_PICKER, awaitingPicker)
        destUri?.let { outState.putParcelable(STATE_DEST_URI, it) }
    }

    /**
     * Waits on the copy into [uri], then reports and finishes. The copy itself
     * runs in [SaveExportViewModel], so an instance recreated mid-copy waits on
     * the same one rather than starting another into the same document.
     */
    private fun copyInto(uri: Uri, file: File) {
        val copy = copier.copyOnce(uri, file)
        lifecycleScope.launch {
            val ok = copy.await()
            val message = if (ok) R.string.save_success else R.string.save_failed
            Feedback.toast(this@SaveExportActivity, message, long = true)
            finish()
        }
    }

    companion object {
        const val EXTRA_PATH = "save_export_path"
        const val EXTRA_MIME = "save_export_mime"
        private const val STATE_AWAITING_PICKER = "save_export_awaiting_picker"
        private const val STATE_DEST_URI = "save_export_dest_uri"

        fun intent(host: Context, file: File, mime: String): Intent =
            Intent(host, SaveExportActivity::class.java).apply {
                putExtra(EXTRA_PATH, file.absolutePath)
                putExtra(EXTRA_MIME, mime)
            }
    }
}
