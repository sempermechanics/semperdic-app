package com.indicvision.semper.util

/** The MIME types the app's exports are handed to the share sheet and the document picker as. */
object Mime {
    const val PDF = "application/pdf"
    const val ZIP = "application/zip"
    const val CSV = "text/csv"
    const val PNG = "image/png"
    const val GIF = "image/gif"

    /** Any type: the save-as proxy's fallback when it was not told one. */
    const val ANY = "*/*"
}
