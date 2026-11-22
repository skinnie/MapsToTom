#!/bin/sh
# Builds MapsToTom-x86_64.AppImage from the Gradle desktop app.
# usage: build-appimage.sh [output dir]      APPIMAGETOOL=/path/to/appimagetool skips the download.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT=${1:-$ROOT/composeApp/build/appimage}
WORK=$OUT/work
TOOL_URL=https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
TOOL_SHA256=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0

rm -rf "$WORK"; mkdir -p "$WORK/AppDir"
(cd "$ROOT" && ./gradlew --console=plain :composeApp:createDistributable)
cp -a "$ROOT/composeApp/build/compose/binaries/main/app/MapsToTom" "$WORK/AppDir/MapsToTom"
cp "$ROOT/composeApp/icons/icon.png" "$WORK/AppDir/mapstotom.png"
cp "$ROOT/tools/appimage/AppRun" "$WORK/AppDir/AppRun"; chmod 755 "$WORK/AppDir/AppRun"
cp "$ROOT/tools/appimage/mapstotom.desktop" "$WORK/AppDir/mapstotom.desktop"

TOOL=${APPIMAGETOOL:-}
if [ -z "$TOOL" ]; then
  TOOL=$WORK/appimagetool
  curl -fsSL -o "$TOOL" "$TOOL_URL"
  echo "$TOOL_SHA256  $TOOL" | sha256sum -c -
  chmod 755 "$TOOL"
fi
# Extract-and-run: build machines often have no FUSE.
ARCH=x86_64 "$TOOL" --appimage-extract-and-run --no-appstream "$WORK/AppDir" "$OUT/MapsToTom-x86_64.AppImage"
rm -rf "$WORK/AppDir"
echo "$OUT/MapsToTom-x86_64.AppImage"
