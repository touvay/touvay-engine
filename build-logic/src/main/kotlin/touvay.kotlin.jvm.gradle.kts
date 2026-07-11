// Convention for pure-JVM Kotlin modules (engine-core, runtime-api).
// These modules must have no Android dependency (ARCHITECTURE.md §8 rule 3).
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        showExceptions = true
        showCauses = true
    }
}
