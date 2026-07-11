plugins {
    id("touvay.android.library")
    // No version: the Kotlin Gradle plugin (which bundles parcelize) is already on the
    // build classpath via build-logic; versioning it here would conflict.
    id("org.jetbrains.kotlin.plugin.parcelize")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.touvay.contract"
    buildFeatures {
        aidl = true
    }
}

kotlin {
    // The contract is a published API surface: nothing becomes public by accident.
    explicitApi()
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // api: generated payload message classes are part of the contract surface.
    api(libs.protobuf.javalite)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
