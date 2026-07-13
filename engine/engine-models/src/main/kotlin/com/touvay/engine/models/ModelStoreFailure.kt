package com.touvay.engine.models

internal enum class ModelStoreFailure(internal val safeMessage: String) {
    UNSUPPORTED_ROOT("model store root is unsupported"),
    SOURCE_LAYOUT_INVALID("model pack source layout is invalid"),
    SOURCE_ENTRY_UNSAFE("model pack source contains an unsafe entry"),
    FILE_INTEGRITY_MISMATCH("model pack file integrity verification failed"),
    INSTALL_CONFLICT("model pack version conflicts with an installed revision"),
    REVISION_NOT_FOUND("model pack revision is not installed"),
    ACTIVE_REVISION_DELETE_FORBIDDEN("active model pack revision cannot be deleted"),
    STORE_CORRUPT("model store integrity verification failed"),
    ATOMIC_MOVE_UNSUPPORTED("required atomic storage operation is unavailable"),
    IO_FAILURE("model store operation failed"),
}

internal class ModelStoreException(
    internal val failure: ModelStoreFailure,
) : RuntimeException(failure.safeMessage)

internal fun modelStoreFailure(failure: ModelStoreFailure): Nothing =
    throw ModelStoreException(failure)
