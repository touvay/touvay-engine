package com.touvay.engine.models

internal enum class ModelCatalogFailure(internal val safeMessage: String) {
    REVISION_NOT_FOUND("model revision is unavailable"),
    REVISION_INCOMPATIBLE("model revision is incompatible"),
    REVISION_PENDING_REMOVAL("model revision is pending removal"),
    ACTIVE_REVISION_REMOVE_FORBIDDEN("active model revision cannot be removed"),
    CATALOG_INCONSISTENT("model catalog consistency verification failed"),
}

internal class ModelCatalogException(
    internal val failure: ModelCatalogFailure,
) : RuntimeException(failure.safeMessage)

internal fun modelCatalogFailure(failure: ModelCatalogFailure): Nothing =
    throw ModelCatalogException(failure)
