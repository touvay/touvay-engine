// Production llama.cpp adapter. Design: docs/runtime/runtime-llamacpp-design.md.
// Conformance claim: src/androidTest LlamaCppTck (runtime-tck).
// NOT wired to the engine or SDK — router/model-manager integration is a separate,
// approved task (Task 3).
plugins {
    id("touvay.android.library")
}

android {
    namespace = "com.touvay.runtime.llamacpp"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        ndk {
            // arm64-v8a is the product target; x86_64 exists for emulator CI. Never 32-bit.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":runtime:runtime-api"))

    androidTestImplementation(project(":runtime:runtime-tck"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
