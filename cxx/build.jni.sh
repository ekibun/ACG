#!/bin/bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

BUILD_DIR="$DIR/build"
cmake "${CMAKE_FLAG[@]}" -DLIBRARY_OUTPUT_PATH=bin -B "$BUILD_DIR" -S "$DIR"
cmake --build "$BUILD_DIR" --config Release --verbose