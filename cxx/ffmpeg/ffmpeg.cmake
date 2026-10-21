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
    set(FFMPEG_CONFIGURE_COMMAND
            ${FFMPEG_CONFIGURE_COMMAND}
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
    # 32 位 x86 的汇编（含 C 内联汇编）用 32 位绝对重定位，只能编出带 textrel 的 .so，
    # 而 Android 从 API 23 起拒绝加载 textrel ⇒ 只有这一支关；armv7 / arm64 / x86_64 都开。
    if (CMAKE_ANDROID_ARCH STREQUAL "x86")
        list(APPEND FFMPEG_CONFIGURE_COMMAND --disable-asm)
    endif ()
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

if (ANDROID)
    # 汇编用 PC 相对 / ADRP 直接引用 C 侧的常量符号，这些符号默认可抢占，链接器不肯就地
    # 解析（`relocation R_*_PC32 cannot be used against symbol …; recompile with -fPIC`）。
    # 上游链接自己的 libav*.so 同样靠它：configure 把 -Wl,-Bsymbolic 放进 SHFLAGS（`configure:8033`）。
    target_link_options(ffmpeg PRIVATE -Wl,-Bsymbolic)
endif ()