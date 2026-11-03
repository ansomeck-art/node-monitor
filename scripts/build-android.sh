#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
ANDROID_SDK="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
ANDROID_NDK="${ANDROID_NDK:-$PROJECT_ROOT/.tools/android/android-ndk-r28c}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
BT="$ANDROID_SDK/build-tools/36.0.0"
JAR="$ANDROID_SDK/platforms/android-36/android.jar"
NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for required in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR" "$NDK_BIN/aarch64-linux-android26-clang" "$JAVA_HOME/bin/javac"; do
  if [[ ! -e "$required" ]]; then printf 'Missing required Android build tool: %s\n' "$required" >&2; exit 1; fi
done
WORK="$PROJECT_ROOT/.cache/android-build"
mkdir -p "$WORK" "$PROJECT_ROOT/build/android" "$PROJECT_ROOT/Android/app/src/main/jniLibs/arm64-v8a"
ANDROID_LINK_FLAGS=(-ldflags "")
if diskutil info "$(df -P "$PROJECT_ROOT" | awk 'NR==2 {print $1}')" 2>/dev/null | rg -qi 'ExFAT'; then
  "$PROJECT_ROOT/scripts/prepare-build.sh"
  mkdir -p "$PROJECT_ROOT/.tools/android-linker"
  if [[ ! -x "$PROJECT_ROOT/.tools/android-linker/lld" ]] || [[ "$NDK_BIN/lld" -nt "$PROJECT_ROOT/.tools/android-linker/lld" ]]; then
    cp "$NDK_BIN/lld" "$PROJECT_ROOT/.tools/android-linker/lld"
    codesign --force --sign - "$PROJECT_ROOT/.tools/android-linker/lld"
  fi
  cat > "$PROJECT_ROOT/.tools/android-linker/ld.lld" <<'WRAPPER'
#!/bin/bash
set -euo pipefail
BASE="$(cd "$(dirname "$0")/../.." && pwd)"
export DYLD_INSERT_LIBRARIES="$BASE/.tools/linker-msync.dylib"
exec -a ld.lld "$BASE/.tools/android-linker/lld" "$@"
WRAPPER
  chmod +x "$PROJECT_ROOT/.tools/android-linker/ld.lld"
  ANDROID_LINK_FLAGS=(-ldflags "-extldflags=-fuse-ld=$PROJECT_ROOT/.tools/android-linker/ld.lld")
fi
(
 cd "$PROJECT_ROOT/Agent"
 GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$NDK_BIN/aarch64-linux-android26-clang" "$GO_BIN" build "${GO_TOOL_FLAGS[@]}" "${ANDROID_LINK_FLAGS[@]}" -trimpath -buildmode=c-shared -o "$PROJECT_ROOT/Android/app/src/main/jniLibs/arm64-v8a/libnodemon.so" ./cmd/nodemon-android
)
"$NDK_BIN/llvm-readelf" --dyn-syms "$PROJECT_ROOT/Android/app/src/main/jniLibs/arm64-v8a/libnodemon.so" | rg -q 'Java_cn_nodemon_app_TailnetBridge_requestNative' || { printf 'Android native library failed integrity check\n' >&2; exit 1; }
rm -rf "$WORK/generated" "$WORK/classes" "$WORK/dex" "$WORK/apk-root" "$WORK/res.zip" "$WORK/resources.ap_" "$WORK/unsigned.apk" "$WORK/aligned.apk"
mkdir -p "$WORK/generated" "$WORK/classes" "$WORK/dex" "$WORK/apk-root/lib/arm64-v8a"
"$BT/aapt2" compile --dir "$PROJECT_ROOT/Android/app/src/main/res" -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$PROJECT_ROOT/Android/app/src/main/AndroidManifest.xml" --java "$WORK/generated" --min-sdk-version 26 --target-sdk-version 35 --version-code 2 --version-name 0.2.0-dev.1 -o "$WORK/resources.ap_" "$WORK/res.zip"
find "$PROJECT_ROOT/Android/app/src/main/java" "$WORK/generated" -name '*.java' ! -name '._*' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"
rm -f "$WORK/classes.jar"
(cd "$WORK/classes" && find . -name '*.class' ! -name '._*' | COPYFILE_DISABLE=1 zip -q "$WORK/classes.jar" -@)
"$BT/d8" --min-api 26 --lib "$JAR" --output "$WORK/dex" "$WORK/classes.jar"
cp "$WORK/resources.ap_" "$WORK/unsigned.apk"
cp "$PROJECT_ROOT/Android/app/src/main/jniLibs/arm64-v8a/libnodemon.so" "$WORK/apk-root/lib/arm64-v8a/"
cp "$WORK/dex/classes.dex" "$WORK/apk-root/"
(cd "$WORK/apk-root" && COPYFILE_DISABLE=1 zip -q -r "$WORK/unsigned.apk" classes.dex lib -x '*/._*')
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
# The transient development key lives only in memory and cannot sign updates.
# Use a separately managed release key for distribution.
python3 "$PROJECT_ROOT/scripts/sign-android-development.py" "$BT/apksigner" "$WORK/aligned.apk" "$PROJECT_ROOT/build/android/NodeMonitor-development-arm64.apk"
"$BT/apksigner" verify "$PROJECT_ROOT/build/android/NodeMonitor-development-arm64.apk"
printf 'Android development APK: %s\n' "$PROJECT_ROOT/build/android/NodeMonitor-development-arm64.apk"
