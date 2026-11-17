#!/bin/bash
# Manual APK assembly for Control.
#
# Gradle cannot run in this sandbox (the runtime garbles JVM IPv4
# loopback sockets, which kills the Gradle daemon handshake), so the
# APK is assembled with the Android SDK tools directly:
# aapt2 -> javac -> d8 -> zipalign -> apksigner. All inputs are local
# files: framework android.jar, this project's Java sources/resources,
# assets, and the single bundled ZXing core jar.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
ZXING="libs/zxing-core.jar"
# Bouncy Castle 1.84 (v1.2): the TLS 1.3 stack for ADB wireless
# pairing. Vendored from the Maven store (native-build/maven) with
# multi-release entries and jar signatures stripped for d8.
BCPROV="libs/bcprov-jdk18on.jar"
BCTLS="libs/bctls-jdk18on.jar"
BCUTIL="libs/bcutil-jdk18on.jar"
# Vosk 0.3.45 + JNA 5.19.1 (v1.11): on-device speech transcription
# for walkie low-data mode. vosk-classes.jar is the org/vosk Java
# API extracted from the Maven Central jar (desktop natives
# excluded); jna-classes.jar is the classes.jar from the JNA AAR.
# Native libs ship separately below (lib/ entries, stored).
VOSK="libs/vosk-classes.jar"
JNA="libs/jna-classes.jar"
B="build/manual"
rm -rf "$B"
mkdir -p "$B/flata" "$B/gen" "$B/classes" "$B/dex"

echo "== compile resources"
"$BT/aapt2" compile --dir res -o "$B/flata/resources.flata"

echo "== link resources + manifest + assets"
"$BT/aapt2" link -o "$B/control-unsigned.apk" -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java "$B/gen" \
  --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 49 --version-name "1.44" \
  -A assets "$B/flata/resources.flata"

echo "== javac"
find src "$B/gen" -name "*.java" > "$B/sources.txt"
javac -encoding UTF-8 --release 17 \
  -cp "$ANDROID_JAR:$ZXING:$BCPROV:$BCTLS:$BCUTIL:$VOSK:$JNA" -d "$B/classes" @"$B/sources.txt"

echo "== d8"
(cd "$B/classes" && jar --create --file ../classes.jar .)
# Dexer note (v1.2): build-tools 34's d8 (R8 8.2.2-dev) dies with an
# NPE on three Bouncy Castle classes (asn1 ASN1Sequence$2, oer
# OERDefinition$BaseType, jsse BCCryptoPrimitive) that we cannot
# prune (asn1 is load-bearing). R8 8.7.18 handles them; the jar
# lives in the durable toolchain dir (fetched from maven.google.com)
# and the output is a standard classes.dex. zipalign/apksigner stay
# on build-tools 34.
R8JAR="$HOME/workspace/native-build/r8/r8-8.7.18.jar"
"$JAVA_HOME/bin/java" -cp "$R8JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$ANDROID_JAR" --output "$B/dex" \
  "$B/classes.jar" "$ZXING" "$BCPROV" "$BCTLS" "$BCUTIL" "$VOSK" "$JNA"

echo "== package + align"
(cd "$B/dex" && zip -q "../control-unsigned.apk" classes*.dex)
# Native libs (v1.11): libvosk.so + libjnidispatch.so for arm64-v8a
# and armeabi-v7a, STORED uncompressed under lib/<abi>/ so the
# loader can map them directly; zipalign -p page-aligns them.
mkdir -p "$B/nativelibs/lib"
cp -r native-libs/arm64-v8a "$B/nativelibs/lib/"
cp -r native-libs/armeabi-v7a "$B/nativelibs/lib/"
(cd "$B/nativelibs" && zip -0 -q "../control-unsigned.apk" -r lib)
"$BT/zipalign" -f -p 4 "$B/control-unsigned.apk" "$B/control-aligned.apk"

echo "== signing key"
if [ ! -f control-release.keystore ]; then
  PASS=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
  keytool -genkeypair -keystore control-release.keystore -alias control \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Control Launcher, O=Control, C=US" > /dev/null 2>&1
  printf 'keystore: control-release.keystore\nalias: control\npassword: %s\n' "$PASS" \
    > keystore-credentials.txt
  chmod 600 keystore-credentials.txt control-release.keystore
  echo "new Control release keystore created"
fi
PASS=$(grep '^password:' keystore-credentials.txt | cut -d' ' -f2)
"$BT/apksigner" sign --ks control-release.keystore \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$B/control-launcher.apk" "$B/control-aligned.apk"
cp "$B/control-launcher.apk" ./control-launcher.apk

echo "== verify"
"$BT/apksigner" verify --print-certs ./control-launcher.apk > "$B/apksigner.txt"
sed -n '1,5p' "$B/apksigner.txt"
"$BT/aapt2" dump badging ./control-launcher.apk > "$B/badging.txt"
sed -n '1,12p' "$B/badging.txt"
ls -lh ./control-launcher.apk
echo BUILD-OK
