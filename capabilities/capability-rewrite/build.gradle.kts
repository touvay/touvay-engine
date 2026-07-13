plugins {
    id("touvay.android.library")
}

android {
    namespace = "com.touvay.capability.rewrite"
}

dependencies {
    implementation(project(":contract:touvay-contract"))
    implementation(project(":engine:engine-core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":capabilities:capability-tck"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
