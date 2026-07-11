plugins {
    id("touvay.android.application")
}

android {
    namespace = "com.touvay.demo"

    defaultConfig {
        applicationId = "com.touvay.demo"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":sdk:touvay-sdk"))
    // Hosts the embedded engine (service manifest merges in; runs in :touvay process).
    implementation(project(":engine:engine-service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
