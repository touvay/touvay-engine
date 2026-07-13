plugins {
    id("touvay.kotlin.jvm")
}

dependencies {
    implementation(project(":runtime:runtime-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
