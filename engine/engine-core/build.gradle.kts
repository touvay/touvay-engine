plugins {
    id("touvay.kotlin.jvm")
    alias(libs.plugins.protobuf)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(project(":runtime:runtime-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
