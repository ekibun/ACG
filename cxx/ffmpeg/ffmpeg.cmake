cmake_minimum_required(VERSION 3.13.0)

set(FFMPEG_CONFIGURE_COMMAND
        ${CMAKE_CURRENT_LIST_DIR}/ffmpeg/configure
        --enable-pic
        --enable-static
        --disable-shared
        --disable-programs
        --disable-encoders
        --disable-muxers
        --disable-network
        --disable-avdevice
        --disable-protocols
        --disable-doc
        --disable-filters
        --disable-avfilter
        # configure 默认带 -g,~90MB 的 DWARF 调试信息会随静态库进最终的 ffmpeg 动态库;
        # 需要单步跟 ffmpeg C 源码时临时去掉这行重编。
        --disable-debug
        --prefix=${CMAKE_CURRENT_BINARY_DIR}
        )
if (${CMAKE_HOST_SYSTEM_NAME} MATCHES "Windows")
    set(BASH_EXEC ${CMAKE_CURRENT_LIST_DIR}/../exec)
endif()

if (ANDROID)
    set(ffmpeg-lib
            mediandk
            android
            z)
    # 安卓侧维持 `--disable-asm`：x86/x86_64 的手写汇编要 nasm，NDK 不自带，
    # 交叉编译时 configure 找不到它会直接报错退出。桌面端已不关，见 TODO.md 的 B11。
    set(FFMPEG_CONFIGURE_COMMAND
            ${FFMPEG_CONFIGURE_COMMAND}
            --disable-asm
            --arch=${CMAKE_ANDROID_ARCH}
            --target-os=android
            --enable-jni
            --enable-mediacodec
            --enable-cross-compile
            --cc=${CMAKE_C_COMPILER}
            --cxx=${CMAKE_CXX_COMPILER}
            --extra-cflags="--target=${ANDROID_LLVM_TRIPLE}"
            --extra-ldflags=--target=${ANDROID_LLVM_TRIPLE}
            --cross-prefix=${ANDROID_TOOLCHAIN_PREFIX}
            )
else ()
    set(ffmpeg-lib
            z bz2 iconv lzma ole32 bcrypt)
    set(FFMPEG_CONFIGURE_COMMAND
            ${FFMPEG_CONFIGURE_COMMAND}
            --arch=x86_64
            --target-os=mingw32
            )
endif ()

include(ExternalProject)

# 源码树（submodule）保持上游原样，本地改动只存在于 patches/：configure.sh 跑完 configure 后
# 把 hls.c 的副本放进构建目录、用 hls.sed 改它，并把 hls.o 重定向到副本。sed 匹配不到不报错，
# 脚本里用守卫计数兜底，上游改了锚点就构建失败。
ExternalProject_Add(
        ffmpeg_lib
        PREFIX "ffmpeg_lib"
        SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/ffmpeg"
        BINARY_DIR "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
        INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}"
        CONFIGURE_COMMAND
            ${BASH_EXEC} bash "${CMAKE_CURRENT_LIST_DIR}/patches/configure.sh"
            ${FFMPEG_CONFIGURE_COMMAND}
        BUILD_COMMAND ${BASH_EXEC} make -j8
        INSTALL_COMMAND ${BASH_EXEC} make install
)

add_library(ffmpeg SHARED
        ${CMAKE_CURRENT_LIST_DIR}/ffmpeg.cpp)

add_dependencies(ffmpeg ffmpeg_lib)

target_link_directories(ffmpeg PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/lib")
target_include_directories(ffmpeg PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/include")
if (${CMAKE_ANDROID_ARCH} MATCHES "x86")
    target_include_directories(ffmpeg PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../build/include")
endif ()

target_link_libraries(ffmpeg PRIVATE
        ${common-lib}
        avformat avcodec avutil swresample swscale
        ${ffmpeg-lib}
        )