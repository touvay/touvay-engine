plugins {
    id("touvay.kotlin.jvm")
}

// No dependencies, by design: the SPI owns its types and sees nothing else
// (ARCHITECTURE.md §8). The conformance kit (runtime-tck) arrives with the first
// real runtime adapter and becomes this module's executable specification.
