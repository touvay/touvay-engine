package com.touvay.engine.models

internal enum class RuntimeLifecycleFailure(internal val safeMessage: String) {
    INVALID_REGISTRATION("runtime registration is invalid"),
    DUPLICATE_REGISTRATION("runtime registration is duplicated"),
    RUNTIME_INCOMPATIBLE("model runtime is incompatible"),
    RUNTIME_UNAVAILABLE("model runtime is unavailable"),
    INVALID_EXECUTION_PROFILE("execution profile is invalid"),
    MODEL_LOAD_FAILED("model runtime load failed"),
    INSTANCE_INCONSISTENT("runtime instance consistency verification failed"),
    INSTANCE_REFERENCED("runtime instance is still referenced"),
    MANAGER_CLOSED("runtime instance manager is closed"),
    CACHE_INCONSISTENT("runtime instance cache consistency verification failed"),
}

internal class RuntimeLifecycleException(
    internal val failure: RuntimeLifecycleFailure,
) : RuntimeException(failure.safeMessage)

internal fun runtimeLifecycleFailure(failure: RuntimeLifecycleFailure): Nothing =
    throw RuntimeLifecycleException(failure)
