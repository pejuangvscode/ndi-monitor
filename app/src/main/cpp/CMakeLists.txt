cmake_minimum_required(VERSION 3.10.2)

project("myapplication")

# Add NDI library
add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so)

# include NDI headers
include_directories(${CMAKE_SOURCE_DIR}/../jniLibs/include)

add_library(native-lib SHARED
    native-lib.cpp)

find_library(log-lib log)

# Ensure we link the Android native library (ANativeWindow symbols)
# If find_library fails for some reason, link by name 'android' as a fallback.
find_library(android-lib android)
if(NOT android-lib)
    message(STATUS "Could not locate libandroid via find_library; using 'android' as fallback link name")
    set(android-lib android)
endif()

# Optional: emit a helpful message if libndi.so is not found for an ABI
if(NOT EXISTS ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so)
    message(FATAL_ERROR "Missing libndi.so for ABI=${ANDROID_ABI} at ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so")
endif()

target_link_libraries(native-lib
    ndi
    ${log-lib}
    ${android-lib}
    c++_shared)
