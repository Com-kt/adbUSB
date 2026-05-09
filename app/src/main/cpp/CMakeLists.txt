cmake_minimum_required(VERSION 3.14)

project(adb_auth)

include(FetchContent)

set(ANDROID_STL c++_static)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    boringssl
    GIT_REPOSITORY https://github.com/google/boringssl.git
    GIT_TAG d589045a772678d5ca131f4c8087d001b9258380
)

FetchContent_MakeAvailable(boringssl)

set(SOURCE_FILES
    auth.cpp
    jni.cc
)

add_library(adb_auth SHARED ${SOURCE_FILES})

target_include_directories(adb_auth PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${boringssl_SOURCE_DIR}/src/include
)

target_link_libraries(adb_auth PRIVATE
    crypto
    log
)

target_compile_definitions(adb_auth PRIVATE
    ADB_HOST=0
    _GNU_SOURCE
)

target_compile_options(adb_auth PRIVATE
    -Oz
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -fomit-frame-pointer
    -fno-rtti
    -fno-exceptions
)

target_link_options(adb_auth PRIVATE
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--strip-all
)

include(CheckIPOSupported)

check_ipo_supported(RESULT ipo_supported)

if(ipo_supported)
    set_property(
        TARGET adb_auth
        PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE
    )
endif()