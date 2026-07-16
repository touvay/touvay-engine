plugins {
    id("touvay.android.application")
}

android {
    namespace = "com.touvay.demo"

    sourceSets.getByName("main").assets.srcDirs(
        "../../docs/demo/pack",
        "../../benchmarks/rewrite",
    )
    sourceSets.getByName("test").resources.srcDir("../../benchmarks/rewrite")

    defaultConfig {
        applicationId = "com.touvay.demo"
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["touvayDemoPackId"] = providers.gradleProperty(
            "touvay.demo.packId",
        ).getOrElse("touvay.demo.qwen2.5-0.5b-rewrite")
        manifestPlaceholders["touvayDemoKeyId"] = providers.gradleProperty(
            "touvay.demo.keyId",
        ).getOrElse("d086218cf3b91da80208646e6a4e0ded99da431e8848d74417d08fb09851edb6")
        manifestPlaceholders["touvayDemoPublicKeyBase64"] = providers.gradleProperty(
            "touvay.demo.publicKeyBase64",
        ).getOrElse("2SmylYadUGTDCKocs5z03VH0J8jE9QBV2RTaux9pWW8=")
    }
}

dependencies {
    implementation(project(":sdk:touvay-sdk"))
    // Hosts the embedded engine (service manifest merges in; runs in :touvay process).
    implementation(project(":engine:engine-service"))
    // Developer Console only: drives the existing signed-pack lifecycle while the
    // embedded Engine client is disconnected. These are not production-client edges.
    implementation(project(":engine:engine-models"))
    implementation(project(":runtime:runtime-api"))
    implementation(project(":runtime:runtime-llamacpp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
    testImplementation(project(":capabilities:capability-rewrite"))
    testImplementation(project(":engine:engine-models"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.protobuf.javalite)
    testImplementation(libs.tink.android)
}
