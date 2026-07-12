cmake_minimum_required(VERSION 3.22.1)
project(opusnative C CXX)

include(FetchContent)

# Build libopus from source (static) as part of the NDK build. We use 1.4 — it
# decodes everything our sender produces and avoids the heavy ML/DNN code paths
# added in 1.5 that bloat build time and binary size.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    opus
    URL https://github.com/xiph/opus/releases/download/v1.4/opus-1.4.tar.gz
)
FetchContent_MakeAvailable(opus)

# Build libFLAC from source (static) for the native lossless decoder. Disable every
# program/example/test/ogg/docs target so only the core library compiles.
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(INSTALL_PKGCONFIG_MODULES OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    flac
    URL https://github.com/xiph/flac/releases/download/1.4.3/flac-1.4.3.tar.xz
)
FetchContent_MakeAvailable(flac)

# Always optimise libopus / libFLAC, even in debug builds — an unoptimised decoder is
# very slow, which would defeat the point of going native.
target_compile_options(opus PRIVATE -O3 -DNDEBUG)
target_compile_options(FLAC PRIVATE -O3 -DNDEBUG)

add_library(opusnative SHARED opus_jni.cpp flac_jni.cpp)
target_include_directories(opusnative PRIVATE
    ${opus_SOURCE_DIR}/include
    ${flac_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(opusnative opus FLAC ${log-lib})
