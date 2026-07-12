package com.touvay.engine.models

internal enum class VerificationFailure(internal val safeMessage: String) {
    MANIFEST_TOO_LARGE("model manifest exceeds its size limit"),
    MALFORMED_MANIFEST("model manifest is malformed"),
    UNSUPPORTED_MANIFEST_SCHEMA("model manifest schema is unsupported"),
    INVALID_MANIFEST_FIELD("model manifest contains an invalid field"),
    MANIFEST_LIMIT_EXCEEDED("model manifest exceeds a structural limit"),
    DUPLICATE_MANIFEST_ENTRY("model manifest contains a duplicate entry"),
    INVALID_LOGICAL_PATH("model manifest contains an invalid logical path"),
    INVALID_FILE_REFERENCE("model manifest contains an invalid file reference"),
    INVALID_FILE_DIGEST("model manifest contains an invalid file digest"),
    DECLARED_SIZE_EXCEEDED("model manifest exceeds the declared-size limit"),
    UNKNOWN_REQUIRED_FEATURE("model manifest requires an unsupported feature"),
    INCOMPATIBLE_ENGINE("model pack is incompatible with this engine"),
    INCOMPATIBLE_RUNTIME("model pack is incompatible with available runtimes"),
    INCOMPATIBLE_DEVICE("model pack is incompatible with this device"),
    SIGNATURE_ENVELOPE_TOO_LARGE("signature envelope exceeds its size limit"),
    MALFORMED_SIGNATURE_ENVELOPE("signature envelope is malformed"),
    UNSUPPORTED_SIGNATURE_ENVELOPE("signature envelope is unsupported"),
    KEY_ID_MISMATCH("signature key identifiers do not match"),
    UNKNOWN_SIGNING_KEY("signature key is not trusted"),
    REVOKED_SIGNING_KEY("signature key is revoked"),
    INVALID_SIGNATURE("model manifest signature is invalid"),
}

internal class ModelPackVerificationException(
    internal val failure: VerificationFailure,
) : RuntimeException(failure.safeMessage)

internal fun verificationFailure(failure: VerificationFailure): Nothing =
    throw ModelPackVerificationException(failure)
