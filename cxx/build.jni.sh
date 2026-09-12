#!/bin/bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

# 1. 判断并处理 JAVA_HOME 路径
if [ -n "$1" ]; then
    if command -v cygpath >/dev/null 2>&1; then
        export JAVA_HOME=$(cygpath -u "$1")
    else
        export JAVA_HOME="$1"
    fi
    echo "JAVA_HOME=$JAVA_HOME"
else
    echo "Warning: No JAVA_HOME path provided as \$1"
fi

BUILD_DIR="$DIR/build"
cmake "${CMAKE_FLAG[@]}" -DLIBRARY_OUTPUT_PATH=bin -B "$BUILD_DIR" -S "$DIR"
cmake --build "$BUILD_DIR" --config Release --verbose