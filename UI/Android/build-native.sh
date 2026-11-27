#!/bin/sh
#
# Builds Ladybird's native code for Android with CMake and gathers the libraries for Gradle to package:
#
#   UI/Android/build-native.sh [arm64-v8a|x86_64]
#   cd UI/Android && ./gradlew assembleDebug -PladybirdNativeBuildDir=../../Build/android-arm64-v8a
#
# This is quicker to work with than Gradle's own CMake build, and builds the helper processes as well.
# Needs ANDROID_HOME (or ~/Android/Sdk), the NDK version build.gradle.kts names, and rustup's Android target.

set -e

ABI=${1:-arm64-v8a}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
SOURCE_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
# LADYBIRD_ANDROID_BUILD_DIR picks another build directory, such as one configured before.
BUILD_DIR=${LADYBIRD_ANDROID_BUILD_DIR:-$SOURCE_DIR/Build/android-$ABI}
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK_VERSION=$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' "$SCRIPT_DIR/build.gradle.kts")
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
CMAKE_BIN=$(ls -d "$ANDROID_HOME"/cmake/*/bin | sort -V | tail -1)
export PATH="$CMAKE_BIN:$HOME/.cargo/bin:$PATH"
export LADYBIRD_SOURCE_DIR="$SOURCE_DIR" LADYBIRD_MAIN_SOURCE_DIR="$SOURCE_DIR"
export XDG_CACHE_HOME=${XDG_CACHE_HOME:-$SOURCE_DIR/Build/caches}

cd "$SOURCE_DIR"
python3 -c 'import sys, pathlib; sys.path.insert(0, "Meta"); from Utils.build_vcpkg import build_vcpkg; build_vcpkg(pathlib.Path("Build/vcpkg-release").resolve())'

if [ ! -f "$BUILD_DIR/CMakeCache.txt" ]; then
    cmake -S . -B "$BUILD_DIR" -G Ninja \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-30 -DANDROID_NDK="$NDK" -DCMAKE_ANDROID_NDK="$NDK" \
        -DANDROID_STL=c++_shared -DCMAKE_SYSTEM_NAME=Android -DCMAKE_SYSTEM_VERSION=30 -DCMAKE_ANDROID_ARCH_ABI="$ABI" \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
        -DLADYBIRD_VCPKG_TYPE=release -DVCPKG_TARGET_ANDROID=ON -DLADYBIRD_CACHE_DIR="$SOURCE_DIR/Build/caches" \
        -DBUILD_SHARED_LIBS=ON -DCMAKE_SKIP_INSTALL_RULES=ON \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo "-DCMAKE_MAP_IMPORTED_CONFIG_RELWITHDEBINFO=RelWithDebInfo;Release;None;NOCONFIG;" \
        -DCMAKE_CXX_FLAGS=-std=c++23
fi

HELPERS=$(sed -n '/^set(ladybird_helper_processes/,/^)/p' Meta/CMake/ladybird_helper_processes.cmake | grep -v '[()]' | tr -d ' ')
ninja -C "$BUILD_DIR" ladybird $HELPERS

# The helpers are already in UI/Android/build/helpers/$ABI. The libraries go next to them in the APK.
JNI_DIR="$SCRIPT_DIR/build/prebuilt-jni/$ABI"
rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
find "$BUILD_DIR/lib" -maxdepth 1 -name '*.so' -exec cp {} "$JNI_DIR/" \;
case "$ABI" in
    arm64-v8a) TRIPLE=aarch64-linux-android ;;
    x86_64) TRIPLE=x86_64-linux-android ;;
esac
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE/libc++_shared.so" "$JNI_DIR/"
echo "Native libraries for $ABI are in $JNI_DIR"
