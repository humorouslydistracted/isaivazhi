cmake_minimum_required(VERSION 3.18.1)

project("isaivazhi_native")

# Build the native acceleration library. Single source file — keep it simple.
add_library(isaivazhi_native SHARED
    embedding_native.cpp
)

# Optimization. -ffast-math relaxes IEEE-754 strictness for FP ops which is fine
# for embedding similarity (we only compare relative magnitudes). -funroll-loops
# helps the compiler emit tight NEON inner loops.
target_compile_options(isaivazhi_native PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -fvisibility=hidden
    -Wall
    -Wno-unused-parameter
)

# armeabi-v7a needs the NEON FPU flag explicitly enabled. arm64-v8a always has NEON.
# x86/x86_64 fall back to scalar code in the source (no NEON).
if (ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(isaivazhi_native PRIVATE -mfpu=neon)
endif()

# Link Android log library so we can use __android_log_print on debug paths.
find_library(log-lib log)
target_link_libraries(isaivazhi_native ${log-lib})
