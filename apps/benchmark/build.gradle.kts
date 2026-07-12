// Task 1 Part B benchmark host. Isolated: sees only the runtime SPI and the spike;
// never the SDK, contract, or engine (enforced by checkDependencyRules).
plugins {
    id("touvay.android.application")
}

android {
    namespace = "com.touvay.benchmark"
    // Needed for native-library stripping of the spike .so packaged into this app.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.touvay.benchmark"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":runtime:runtime-api"))
    implementation(project(":runtime:runtime-llamacpp-spike"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test)
}
