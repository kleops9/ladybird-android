# Ladybird for Android

This repository carries an unofficial Android port of Ladybird. The Ladybird project dropped its own Android port in
November 2026 and doesn't accept outside contributions, so the port lives here, on the `android` branch, on top of the
upstream engine. This document explains how to build it, how it's put together, and how releases are made.

## What you need

- A Linux machine (x86_64) with about 25 GB free and plenty of time: the first build takes one to two hours on 8 cores,
  most of it the engine and its dependencies.
- The Android SDK, with:
  - the NDK version that `ndkVersion` in [UI/Android/build.gradle.kts](../UI/Android/build.gradle.kts) names,
  - CMake 3.23 or later from the SDK manager, which comes with Ninja,
  - build-tools and platform 35.
- Java 21, for Gradle.
- Rust through rustup, with the Android target: `rustup target add aarch64-linux-android`.
- Python 3, Git, and what upstream's [build instructions](BuildInstructionsLadybird.md) list for building Ladybird on
  Linux, such as autoconf, automake, libtool, nasm and pkg-config, which vcpkg needs to build the dependencies.
- A phone or emulator with Android 11 or later. Builds are for 64-bit ARM (arm64-v8a). The x86_64 emulator runs them
  through its ARM translation, slowly.

The scripts look for the SDK in `ANDROID_HOME`, or in `~/Android/Sdk`.

## Building

The native code is built with CMake, apart from Gradle, and Gradle then packages it:

```sh
UI/Android/build-native.sh
cd UI/Android
./gradlew assembleDebug -PladybirdNativeBuildDir=../../Build/android-arm64-v8a
adb install -r build/outputs/apk/debug/Ladybird-debug.apk
```

`build-native.sh` builds the vcpkg dependencies, then the engine, the app's native library and the helper processes,
into `Build/android-arm64-v8a`, and gathers the libraries for Gradle. Run it again after changing native code. After
changing only Kotlin or resources, running Gradle again is enough. `LADYBIRD_ANDROID_BUILD_DIR` builds in another
directory.

Debug builds are optimized with line tables, so backtraces show functions and lines. Their version ends in `-dev`.

## How the port works

- The app is a thin client of the **Hub**, upstream's process that owns the browser's views and starts the other helper
  processes, as the Qt browser is when the Hub runs in a process of its own. The UI side is in
  [UI/Android/src/main/cpp](../UI/Android/src/main/cpp): `HubConnection` talks to the Hub, and `RemoteViewAndroid` is
  the native side of a tab.
- The **helper processes** (Hub, WebContent, RequestServer, Compositor and the rest) are upstream's own programs. Android
  only lets an app run programs from its native library directory, so they are built as `lib<Name>.so` and packaged
  with the libraries, which the APK extracts on install.
- The engine is built as **shared libraries** on Android, while upstream builds it statically. The vcpkg dependencies
  stay static.
- The **Kotlin UI** in [UI/Android/src/main/java](../UI/Android/src/main/java) draws the browser around the pages:
  tabs, the toolbar, the menu and the settings. The engine's own settings, such as ad blocking and DNS, are kept by the
  Hub, and the app edits them through it.

The changes outside `UI/Android` are kept small, so that following upstream stays easy. Each is a commit of its own on
the `android` branch.

## Debugging

- `adb logcat -s Ladybird Serenity` shows what the native code logs.
- The helper processes write their output to the app's cache directory:
  `adb shell run-as org.serenityos.ladybird cat cache/ladybird.log` (debug builds only).
- `adb logcat -s LadybirdLoad` shows how long pages take to load (debug builds only).
- Crashes leave tombstones, which `llvm-symbolizer` from the NDK symbolizes against the libraries in
  `Build/android-arm64-v8a/lib` and `UI/Android/build/helpers/arm64-v8a`.
- Settings › Show frame rate shows how many frames a second the engine draws.

## Versions and releases

The version is in [UI/Android/version.properties](../UI/Android/version.properties), as `major.minor.patch`. It stays
below 1.0 while the port is in alpha. The version code that Android orders versions by follows from it.
[UI/Android/CHANGELOG.md](../UI/Android/CHANGELOG.md) lists what each release changed, with what's coming under
"Unreleased".

To make a release:

```sh
UI/Android/release.sh 0.2.0            # Builds Build/release/Ladybird-0.2.0-arm64-v8a.apk
UI/Android/release.sh 0.2.0 --publish  # Also pushes it, and makes a GitHub release
```

The script needs a clean tree and notes under "Unreleased". It turns the notes into the version's section, sets the
version, commits and tags it as `v0.2.0`, and builds and signs the APK. With `--publish`, it pushes the commit and the
tag and makes a GitHub prerelease with the APK, its SHA-256 and the notes.

Releases are signed with the key in the keystore that `LADYBIRD_KEYSTORE` names, with `LADYBIRD_KEY_ALIAS`,
`LADYBIRD_KEYSTORE_PASSWORD` and `LADYBIRD_KEY_PASSWORD`. The script reads them from
`~/.config/ladybird-android/signing.env` (or the file `LADYBIRD_SIGNING_ENV` names) if it's there, which keeps the key
and its password out of the repository:

```sh
export LADYBIRD_KEYSTORE="$HOME/.config/ladybird-android/ladybird-android-release.jks"
export LADYBIRD_KEY_ALIAS="ladybird-android"
export LADYBIRD_KEYSTORE_PASSWORD="..."
export LADYBIRD_KEY_PASSWORD="..."
```

Without a key, the script signs with the Android SDK's development key. Android only installs a new release over an old one signed with the same key, so a project's releases
should always be signed with the same one.

## Following upstream

`master` mirrors upstream. To move the port onto a newer upstream, rebase `android` onto `master`, rebuild, and try
the app. The engine changes are commits of their own, which keeps conflicts small.
