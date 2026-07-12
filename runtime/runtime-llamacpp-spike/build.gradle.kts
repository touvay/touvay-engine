// Task 1 Part B feasibility spike (isolated): NOT wired to the SDK, engine, or router.
// Implements the runtime-api SPI over upstream llama.cpp (pinned; scripts/fetch-llamacpp.ps1).
// Conversion into the production runtime adapter requires explicit approval.
plugins {
    id("touvay.android.library")
}

android {
    namespace = "com.touvay.runtime.llamacpp.spike"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        ndk {
            // arm64-v8a is the product target; x86_64 exists to functionally validate
            // on the emulator. Never 32-bit.
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
}
