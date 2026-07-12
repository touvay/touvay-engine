cmake_minimum_required(VERSION 3.22)
project(touvay_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page-size compliance (Google Play requirement for API 35+ targets).
# NDK r27 does not align to 16 KB by default; make it explicit.
add_link_options("-Wl,-z,max-page-size=16384")

# Upstream llama.cpp, pinned by scripts/fetch-llamacpp.ps1 (tag b5199). Bumping the
# pin is a reviewed change: build + TCK + benchmark deltas (design doc §1).
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_DIR}. Run scripts/fetch-llamacpp.ps1 first.")
endif()

# CPU-only baseline, statically linked into one 16 KB-aligned shared JNI library.
# GPU/NPU delegates are a later, measured decision (ARCHITECTURE.md §17).
set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)   # no network code, ever (ARCHITECTURE.md §16)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)  # NDK ships no OpenMP runtime
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)  # cross-compiling: no -march=native

add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)

add_library(touvay_llama SHARED llama_jni.cpp)
target_link_libraries(touvay_llama PRIVATE llama log android)
