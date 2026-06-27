cmake_minimum_required(VERSION 4.3.3)

project(NekoProject)

# 设置 C 标准（可选：90, 99, 11, 17, 23)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

set(SOURCE_DIR src/main/cpp)

add_library(native-lib SHARED
        native-lib.cpp
        native-sig.cpp
        native-zip.cpp
        native-unzip.cpp
        crypto_utils.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
    ${log}
    ${android}
    z
)

target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=65536")

# 4096 16384 65536