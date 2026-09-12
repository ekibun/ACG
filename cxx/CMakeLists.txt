cmake_minimum_required(VERSION 3.13.0)

project("nekomp")

if (ANDROID)
    find_library(log-lib log)
    set(common-lib ${common-lib} ${log-lib})
else()
    SET(CMAKE_SHARED_LIBRARY_PREFIX "")
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -static -fPIC")
    include_directories("$ENV{JAVA_HOME}/include")
    include_directories("$ENV{JAVA_HOME}/include/win32")
endif ()

include("${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/ffmpeg.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/quickjs/quickjs.cmake")
