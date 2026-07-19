cmake_minimum_required(VERSION 4.3.3)

project(NekoProject)

# 设置 C 标准（可选：90, 99, 11, 17, 23)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

set(SOURCE_DIR src/main/cpp)

find_program(GLSLC_EXECUTABLE glslc HINTS "${ANDROID_NDK}/shaderc")
if(NOT GLSLC_EXECUTABLE)
    message(FATAL_ERROR "No for glslc")
endif()

set(SHADER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/sharpen.comp")
set(SHADER_HEADER "${CMAKE_CURRENT_BINARY_DIR}/sharpen_spv.h")

add_custom_command(
    OUTPUT ${SHADER_HEADER}
    COMMAND ${GLSLC_EXECUTABLE} ${SHADER_SRC} -mfmt=c -o ${SHADER_HEADER}
    DEPENDS ${SHADER_SRC}
    COMMENT "compile sharpen.comp"
)

add_library(native-lib SHARED
        native-lib.cpp
        native-sig.cpp
        native-vulkan.cpp
        ${SHADER_HEADER}
)

target_include_directories(native-lib PRIVATE ${CMAKE_CURRENT_BINARY_DIR})

target_link_libraries(native-lib
    log
    android
    vulkan
    jnigraphics
)

target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=65536")

# 4096 16384 65536