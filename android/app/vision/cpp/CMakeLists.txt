cmake_minimum_required(VERSION 3.22.1)

project(bionicfish_ncnn LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(bionicfish_ncnn SHARED ncnn_jni.cpp)

# The default layout matches the official NCNN Android archive after it is copied to:
#   app/src/main/cpp/third_party/ncnn/<abi>/include
#   app/src/main/cpp/third_party/ncnn/<abi>/lib/cmake/ncnn/ncnnConfig.cmake
# Callers may also pass -Dncnn_DIR=... or -DNCNN_ROOT=... from Gradle/local.properties.
set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn" CACHE PATH
        "Root of the extracted NCNN Android package")

if(NOT ncnn_DIR)
    set(_ncnn_config_candidates
            "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn"
            "${NCNN_ROOT}/lib/${ANDROID_ABI}/cmake/ncnn"
            "${NCNN_ROOT}/lib/cmake/ncnn"
            "${NCNN_ROOT}/${ANDROID_ABI}/cmake/ncnn")
    # Also accept an untouched official archive directory nested one level below NCNN_ROOT.
    file(GLOB _nested_ncnn_configs
            "${NCNN_ROOT}/*/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    foreach(_config IN LISTS _nested_ncnn_configs)
        get_filename_component(_config_dir "${_config}" DIRECTORY)
        list(APPEND _ncnn_config_candidates "${_config_dir}")
    endforeach()
    foreach(_candidate IN LISTS _ncnn_config_candidates)
        if(EXISTS "${_candidate}/ncnnConfig.cmake")
            set(ncnn_DIR "${_candidate}" CACHE PATH "Directory containing ncnnConfig.cmake" FORCE)
            break()
        endif()
    endforeach()
endif()

find_package(ncnn REQUIRED CONFIG)

if(TARGET ncnn::ncnn)
    set(_ncnn_target ncnn::ncnn)
elseif(TARGET ncnn)
    set(_ncnn_target ncnn)
else()
    message(FATAL_ERROR "The NCNN package was found, but it exported neither ncnn::ncnn nor ncnn")
endif()

find_library(android_lib android REQUIRED)
find_library(jnigraphics_lib jnigraphics REQUIRED)
find_library(log_lib log REQUIRED)

target_link_libraries(bionicfish_ncnn
        PRIVATE
        ${_ncnn_target}
        ${android_lib}
        ${jnigraphics_lib}
        ${log_lib})

# The official prebuilt NCNN target exports -fno-exceptions to consumers.  The JNI
# boundary deliberately catches allocation/runtime failures and converts them into a
# Java-visible error, so this translation unit must re-enable exceptions.  A
# source-level option is emitted after target usage requirements by CMake.
target_compile_options(bionicfish_ncnn PRIVATE -Wall -Wextra -Wpedantic)
set_source_files_properties(ncnn_jni.cpp PROPERTIES COMPILE_OPTIONS "-fexceptions")
