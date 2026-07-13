pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "touvay-engine"

include(":contract:touvay-contract")
include(":sdk:touvay-sdk")
include(":engine:engine-core")
include(":engine:engine-models")
include(":engine:engine-service")
include(":capabilities:capability-tck")
include(":runtime:runtime-api")
include(":runtime:runtime-tck")
include(":runtime:runtime-llamacpp")
include(":apps:demo")
include(":apps:benchmark")
