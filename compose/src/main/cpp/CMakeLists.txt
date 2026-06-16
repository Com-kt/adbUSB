cmake_minimum_required(VERSION 4.3.3)

project(NekoProject)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD_REQUIRED True)

set(SOURCE_DIR src/main/cpp)
  
add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
    ${log}
    ${android}
)

target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=65536")

# 4096 16384 65536