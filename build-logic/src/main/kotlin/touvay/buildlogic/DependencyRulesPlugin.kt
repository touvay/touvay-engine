package touvay.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Enforces the module dependency rules from `docs/ARCHITECTURE.md` §8.
 *
 * Every module must have an entry here listing the project dependencies it is allowed to
 * declare. A module without an entry fails the check: adding a module requires a conscious
 * decision about its place in the dependency graph, made in code review, not by accident.
 *
 * Only production configurations are checked. Test configurations may pragmatically cross
 * boundaries (e.g. instrumented tests binding a real service); the architecture governs the
 * production graph.
 *
 * Apply to the root project only. Wired into the root `check` task so `gradlew check` and CI
 * both fail on a violation.
 */
class DependencyRulesPlugin : Plugin<Project> {

    /** ARCHITECTURE.md §8: allowed project dependencies per module. */
    private val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
        // §8 rule 1: the contract depends on nothing in the repo.
        ":contract:touvay-contract" to emptySet(),
        // §8 rule 2: the SDK depends only on the contract.
        ":sdk:touvay-sdk" to setOf(":contract:touvay-contract"),
        // §8: the SPI owns its types and sees nothing else.
        ":runtime:runtime-api" to emptySet(),
        // §8 rule 3: engine-core is pure Kotlin and sees only abstractions.
        ":engine:engine-core" to setOf(":runtime:runtime-api"),
        // ADR-015/016: offline model metadata and verification; no concrete runtime/network.
        ":engine:engine-models" to setOf(":runtime:runtime-api"),
        // §8 rule 4: the composition root is the only module that wires concretes.
        ":engine:engine-service" to setOf(
            ":contract:touvay-contract",
            ":engine:engine-core",
            ":runtime:runtime-api",
        ),
        // §8 rule 7: client apps depend only on the SDK (+ the engine host they embed).
        ":apps:demo" to setOf(
            ":sdk:touvay-sdk",
            ":engine:engine-service",
        ),
        // Conformance kit: executable form of docs/runtime/runtime-spi.md.
        ":runtime:runtime-tck" to setOf(":runtime:runtime-api"),
        // Production adapter (Task 2): SPI only; engine wiring is a separate approved task.
        ":runtime:runtime-llamacpp" to setOf(":runtime:runtime-api"),
        ":apps:benchmark" to setOf(
            ":runtime:runtime-api",
            ":runtime:runtime-llamacpp",
        ),
    )

    private val checkedConfigurations = setOf(
        "api",
        "implementation",
        "compileOnly",
        "runtimeOnly",
    )

    override fun apply(target: Project) {
        require(target == target.rootProject) {
            "touvay.dependency-rules must be applied to the root project only"
        }
        target.plugins.apply("base")

        val checkTask = target.tasks.register("checkDependencyRules") {
            group = "verification"
            description = "Verifies module dependencies against ARCHITECTURE.md §8"
            notCompatibleWithConfigurationCache("inspects the project model at execution time")

            doLast {
                val violations = mutableListOf<String>()
                target.subprojects.forEach { project ->
                    val allowed = allowedProjectDependencies[project.path]
                    val declared = project.configurations
                        .filter { it.name in checkedConfigurations }
                        .flatMap { it.dependencies }
                        .filterIsInstance<ProjectDependency>()
                        .map { it.path }
                        .distinct()

                    if (allowed == null) {
                        if (declared.isNotEmpty() || project.subprojects.isEmpty()) {
                            violations += "${project.path}: no dependency rule declared. " +
                                "Add it to DependencyRulesPlugin after an architecture review."
                        }
                        return@forEach
                    }
                    declared.filterNot { it in allowed }.forEach { illegal ->
                        violations += "${project.path} -> $illegal is not allowed by " +
                            "ARCHITECTURE.md §8 (allowed: ${allowed.ifEmpty { "none" }})"
                    }
                }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "Dependency rule violations:\n" +
                            violations.joinToString("\n") { "  - $it" },
                    )
                }
            }
        }

        target.tasks.named("check").configure { dependsOn(checkTask) }
    }
}
