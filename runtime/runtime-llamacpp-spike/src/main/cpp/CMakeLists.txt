cmake_minimum_required(VERSION 3.22)
project(touvay_llama_spike C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page-size compliance (Google Play requirement for API 35+ targets).
# NDK r27 does not align to 16 KB by default; make it explicit rather than relying
# on toolchain defaults.
add_link_options("-Wl,-z,max-page-size=16384")

# Upstream llama.cpp, pinned by scripts/fetch-llamacpp.ps1 (tag b5199).
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_DIR}. Run scripts/fetch-llamacpp.ps1 first.")
endif()

# Spike build policy: CPU-only baseline, everything statically linked into one
# 16 KB-aligned shared JNI library. GPU/NPU delegates are a later, measured decision.
set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)   # no network code, ever (ARCHITECTURE.md §16)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)  # NDK ships no OpenMP runtime; ggml uses raw threads
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)  # cross-compiling: no -march=native

add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)

add_library(touvay_llama_spike SHARED spike_llama_jni.cpp)
target_link_libraries(touvay_llama_spike PRIVATE llama log android)
