#!/bin/sh
# Builds out/ttlink.apk for Android 4.0+ without Gradle: Go binary -> lib/armeabi/libttlink.so, javac, d8, aapt,
# zipalign, apksigner (v1 signature, which Android 4.0 needs). Signing key: android/ttlink-debug.keystore (made once).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
TOP=$HERE/..
T=/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/dev-toolchain
SDK=$T/android-sdk
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$T/jdk17 PATH=$T/jdk17/bin:$PATH
B=$TOP/out/apk
rm -rf "$B" && mkdir -p "$B/classes" "$B/lib/armeabi"

. "$TOP/env.sh"
(cd "$TOP" && CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=5 go build -trimpath -ldflags="-s -w" -o "$B/lib/armeabi/libttlink.so" ./cmd/ttlink)

javac -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" -d "$B/classes" -Xlint:-options \
    $(find "$HERE/src" -name '*.java')
"$BT/d8" --min-api 15 --lib "$JAR" --output "$B" $(find "$B/classes" -name '*.class')

"$BT/aapt" package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$JAR" -F "$B/unsigned.apk"
(cd "$B" && zip -q unsigned.apk classes.dex lib/armeabi/libttlink.so)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

KS=$HERE/ttlink-debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass ttlink -keypass ttlink -alias ttlink \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TT Link" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:ttlink --ks-key-alias ttlink --min-sdk-version 15 \
    --out "$TOP/out/ttlink.apk" "$B/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 15 "$TOP/out/ttlink.apk"
ls -l "$TOP/out/ttlink.apk"
