plugins {
    id("touvay.dependency-rules")
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // Only published API surfaces are validated (ARCHITECTURE.md §9, ADR-009):
    // touvay-contract, touvay-sdk, runtime-api. Internal engine modules and apps evolve
    // freely. `gradlew apiCheck` runs in `check`; update dumps with `gradlew apiDump`
    // only as part of a reviewed, additive API change.
    ignoredProjects += listOf(
        "engine-core",
        "engine-service",
        "demo",
        // Spike artifacts are not public API (Task 1 Part B; isolated by design).
        "runtime-llamacpp-spike",
        "benchmark",
    )
}
