#!/bin/sh
#
# Makes a release of Ladybird for Android:
#
#   UI/Android/release.sh <version> [--publish]
#
# 1. Checks that the tree is clean and that CHANGELOG.md has notes under "## Unreleased".
# 2. Turns those notes into the section of <version>, sets the version in version.properties, commits both, and tags
#    the commit v<version>.
# 3. Builds the native code (build-native.sh) and a release APK, Build/release/Ladybird-<version>-arm64-v8a.apk.
# 4. With --publish, pushes the commit and the tag, and makes a GitHub prerelease with the APK and the version's notes.
#
# The APK is signed with the key that LADYBIRD_KEYSTORE names (see build.gradle.kts). The script reads those variables
# from ~/.config/ladybird-android/signing.env, or the file LADYBIRD_SIGNING_ENV names, if there's one. Without a key,
# it's signed with the Android SDK's development key, which only installs over builds signed with the same key.
#
# Versions are major.minor.patch. They stay below 1.0 while the port is in alpha.

set -e

VERSION=$1
PUBLISH=no
[ "$2" = "--publish" ] && PUBLISH=yes
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "usage: $0 <major.minor.patch> [--publish]" >&2
    exit 1
fi

SIGNING_ENV=${LADYBIRD_SIGNING_ENV:-$HOME/.config/ladybird-android/signing.env}
# shellcheck disable=SC1090
[ -f "$SIGNING_ENV" ] && . "$SIGNING_ENV"

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
SOURCE_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
CHANGELOG="$SCRIPT_DIR/CHANGELOG.md"
TAG="v$VERSION"
ABI=arm64-v8a
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
cd "$SOURCE_DIR"

if [ -n "$(git status --porcelain)" ]; then
    echo "The tree has changes that aren't committed." >&2
    exit 1
fi
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "$TAG exists already." >&2
    exit 1
fi
NOTES=$(sed -n '/^## Unreleased/,/^## /{/^## /d;p}' "$CHANGELOG" | sed -e '/./,$!d')
if [ -z "$(echo "$NOTES" | tr -d '[:space:]')" ]; then
    echo "CHANGELOG.md has no notes under \"## Unreleased\"." >&2
    exit 1
fi

# The notes become the section of the version, under a new, empty "Unreleased".
DATE=$(date +%Y-%m-%d)
sed -i "s/^## Unreleased$/## Unreleased\n\n## $VERSION ($DATE)/" "$CHANGELOG"
sed -i "s/^version=.*/version=$VERSION/" "$SCRIPT_DIR/version.properties"
git add "$CHANGELOG" "$SCRIPT_DIR/version.properties"
git commit -q -m "Android: Release $VERSION"
git tag -a "$TAG" -m "Ladybird for Android $VERSION"
echo "Tagged $TAG."

"$SCRIPT_DIR/build-native.sh" "$ABI"
BUILD_DIR=${LADYBIRD_ANDROID_BUILD_DIR:-$SOURCE_DIR/Build/android-$ABI}
(cd "$SCRIPT_DIR" && ./gradlew -q assembleRelease "-PladybirdNativeBuildDir=$BUILD_DIR")

mkdir -p Build/release
APK="$SOURCE_DIR/Build/release/Ladybird-$VERSION-$ABI.apk"
# Gradle leaves the APK in build/outputs, or in build/intermediates when Android Studio's options are passed on.
newest_apk() { ls -t "$SCRIPT_DIR"/build/outputs/apk/release/$1 "$SCRIPT_DIR"/build/intermediates/apk/release/$1 2>/dev/null | head -1; }
SIGNED=$(newest_apk Ladybird-release.apk)
UNSIGNED=$(newest_apk Ladybird-release-unsigned.apk)
if [ -n "$LADYBIRD_KEYSTORE" ] && [ -n "$SIGNED" ]; then
    cp "$SIGNED" "$APK"
else
    echo "warning: LADYBIRD_KEYSTORE isn't set, so the APK is signed with the development key." >&2
    BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
    "$BUILD_TOOLS/zipalign" -f -P 16 4 "$UNSIGNED" "$APK.aligned"
    "$BUILD_TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --out "$APK" "$APK.aligned"
    rm -f "$APK.aligned" "$APK.idsig"
fi
SHA256=$(sha256sum "$APK" | cut -d' ' -f1)
echo "$APK"
echo "SHA-256: $SHA256"

if [ "$PUBLISH" = yes ]; then
    git push origin HEAD "$TAG"
    printf '%s\n\n**APK:** `%s` (arm64-v8a, Android 11 or later)  \n**SHA-256:** `%s`\n' \
        "$NOTES" "$(basename "$APK")" "$SHA256" > Build/release/notes-$VERSION.md
    gh release create "$TAG" --verify-tag --prerelease --title "Ladybird for Android $VERSION" \
        --notes-file "Build/release/notes-$VERSION.md" "$APK"
fi
