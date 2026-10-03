package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.cloud.CorruptTransferException

/**
 * The backup's file list lacks something a restore or bundle download needs
 * (no completed files, no `metadata.json`, no `Session.zip`). Nothing was
 * downloaded wrong, so it is not a [CorruptTransferException], but no retry can
 * change it either: [RestoreDownloadOutcomes.isTerminalFailure] ends the work.
 *
 * Kept apart from [CorruptTransferException] because a bundle download may still
 * fall back to packing the copy on this phone for this case, and must not for a
 * corrupt one. [message] is a reason code, not text for the user.
 */
class UnrestorableBackupException(message: String) : IllegalStateException(message)
