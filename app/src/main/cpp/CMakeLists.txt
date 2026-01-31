cmake_minimum_required(VERSION 3.22.1)

project("ndkarte" LANGUAGES C CXX)

# C/C++ standards
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compiler warnings
add_compile_options(
    -Wall
    -Wextra
    -Wpedantic
)

# Add the native_app_glue library from NDK
# This provides the android_main() entry point and event handling
set(NATIVE_APP_GLUE_DIR ${ANDROID_NDK}/sources/android/native_app_glue)
add_library(native_app_glue STATIC
    ${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c
)
target_include_directories(native_app_glue PUBLIC
    ${NATIVE_APP_GLUE_DIR}
)
# Suppress warnings for NDK's native_app_glue code (not our code)
target_compile_options(native_app_glue PRIVATE -w)

# Main application library
add_library(ndkarte SHARED
    main.c
)

# Include native_app_glue headers
target_include_directories(ndkarte PRIVATE
    ${NATIVE_APP_GLUE_DIR}
)

# Find required Android libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries
target_link_libraries(ndkarte
    native_app_glue
    ${log-lib}
    ${android-lib}
    EGL
    GLESv3
)
