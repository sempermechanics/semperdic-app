package com.sempermechanics.semper.data.cloud

/** Restore/download payload failed integrity checks; must not retry. */
class CorruptTransferException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
