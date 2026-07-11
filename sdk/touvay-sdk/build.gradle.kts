plugins {
    id("touvay.android.library")
}

android {
    namespace = "com.touvay.sdk"
}

kotlin {
    // The SDK is the long-term public contract with client apps: nothing becomes
    // public by accident.
    explicitApi()
}

dependencies {
    // implementation, not api: contract types never leak through the SDK surface.
    implementation(project(":contract:touvay-contract"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
