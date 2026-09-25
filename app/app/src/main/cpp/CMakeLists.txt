
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GSTREAMER_ABI_DIR "arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GSTREAMER_ABI_DIR "armv7")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GSTREAMER_ABI_DIR "x86_64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(GSTREAMER_ABI_DIR "x86")
else()
    message(FATAL_ERROR "No GStreamer libs bundled for ABI: ${ANDROID_ABI}")
endif()

set(GSTREAMER_ROOT E:/gstreamer_android/${GSTREAMER_ABI_DIR})


# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("wfdsinkapp")

include_directories(
    ${GSTREAMER_ROOT}/include/gstreamer-1.0
    ${GSTREAMER_ROOT}/include/glib-2.0
    ${GSTREAMER_ROOT}/lib/glib-2.0/include   # glibconfig.h lives here, not above
)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    wfdsinkapp.cpp)

add_library(gstreamer-1.0 STATIC IMPORTED)
set_target_properties(gstreamer-1.0 PROPERTIES IMPORTED_LOCATION
    ${GSTREAMER_ROOT}/lib/libgstreamer-1.0.a)

add_library(glib-2.0 STATIC IMPORTED)
set_target_properties(glib-2.0 PROPERTIES IMPORTED_LOCATION
    ${GSTREAMER_ROOT}/lib/libglib-2.0.a)

add_library(gobject-2.0 STATIC IMPORTED)
set_target_properties(gobject-2.0 PROPERTIES IMPORTED_LOCATION
    ${GSTREAMER_ROOT}/lib/libgobject-2.0.a)

add_library(gmodule-2.0 STATIC IMPORTED)
set_target_properties(gmodule-2.0 PROPERTIES IMPORTED_LOCATION
    ${GSTREAMER_ROOT}/lib/libgmodule-2.0.a)
# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    "${GSTREAMER_ROOT}/lib/libffi.a"
    "${GSTREAMER_ROOT}/lib/libpcre2-8.a"
    "${GSTREAMER_ROOT}/lib/libiconv.a"
    "${GSTREAMER_ROOT}/lib/libintl.a"
    "${GSTREAMER_ROOT}/lib/libz.a"
    gstreamer-1.0
    glib-2.0
    gobject-2.0
    gmodule-2.0
    android
    log)
