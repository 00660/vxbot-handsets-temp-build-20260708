cmake_minimum_required(VERSION 3.22.1)

set(PADDLEOCR_RUNTIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../paddleocr-runtime")
set(PaddleLite_DIR "${PADDLEOCR_RUNTIME_DIR}/PaddleLite")
set(OpenCV_DIR "${PADDLEOCR_RUNTIME_DIR}/OpenCV/sdk/native/jni")

include_directories(${PaddleLite_DIR}/cxx/include)
find_package(OpenCV REQUIRED)
include_directories(${OpenCV_INCLUDE_DIRS})

file(GLOB PADDLEOCR_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")
add_library(Native SHARED ${PADDLEOCR_SOURCES})

find_library(log-lib log)

add_library(paddle_light_api_shared SHARED IMPORTED)
set_target_properties(paddle_light_api_shared PROPERTIES
        IMPORTED_LOCATION "${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libpaddle_light_api_shared.so")

target_compile_options(Native PRIVATE
        -ffast-math
        -Ofast
        -Os
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -fdata-sections
        -ffunction-sections)

target_link_options(Native PRIVATE -Wl,--gc-sections -Wl,-z,nocopyreloc)

target_link_libraries(Native
        paddle_light_api_shared
        ${OpenCV_LIBS}
        GLESv2
        EGL
        jnigraphics
        ${log-lib})

foreach(runtime_lib IN ITEMS libc++_shared.so libpaddle_light_api_shared.so libhiai.so libhiai_ir.so libhiai_ir_build.so)
    add_custom_command(TARGET Native POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/${runtime_lib}"
            "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/${runtime_lib}")
endforeach()
