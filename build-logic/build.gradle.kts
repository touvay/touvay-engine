plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("dependencyRules") {
            id = "touvay.dependency-rules"
            implementationClass = "touvay.buildlogic.DependencyRulesPlugin"
        }
    }
}
