// Reusable executable contract for production capability plugins (ADR-020/021).
plugins {
    id("touvay.kotlin.jvm")
}

dependencies {
    api(project(":engine:engine-core"))
    api(project(":runtime:runtime-api"))
    api(libs.junit4)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.protobuf.javalite)
}
