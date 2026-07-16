plugins {
    id("touvay.dependency-rules")
    alias(libs.plugins.binary.compatibility.validator)
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

apiValidation {
    // Only published API surfaces are validated (ARCHITECTURE.md §9, ADR-009):
    // touvay-contract, touvay-sdk, runtime-api. Internal engine modules and apps evolve
    // freely. `gradlew apiCheck` runs in `check`; update dumps with `gradlew apiDump`
    // only as part of a reviewed, additive API change.
    ignoredProjects += listOf(
        "engine-core",
        "engine-models",
        "engine-service",
        "capability-tck",
        "capability-rewrite",
        "demo",
        // Not public API surfaces: the adapter is engine-internal, the TCK evolves
        // with the repo, the benchmark is a tool.
        "runtime-llamacpp",
        "runtime-tck",
        "benchmark",
    )
}
