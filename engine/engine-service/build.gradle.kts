plugins {
    id("touvay.android.library")
}

android {
    namespace = "com.touvay.engine.service"
}

dependencies {
    implementation(project(":contract:touvay-contract"))
    implementation(project(":engine:engine-core"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
