// Runtime TCK: executable form of docs/runtime/runtime-spi.md. Pure JVM so the kit
// runs in ordinary CI (self-test) and on device (adapter androidTest classes extend
// AbstractRuntimeTck). Design: docs/runtime/runtime-tck.md.
plugins {
    id("touvay.kotlin.jvm")
}

dependencies {
    api(project(":runtime:runtime-api"))
    // api: consumers subclass AbstractRuntimeTck, whose @Test surface is JUnit4.
    api(libs.junit4)

    testImplementation(libs.kotlin.test)
}
