#!/usr/bin/env bash
# From the project root after building and installing the matching debug-signed release APK:
# bash tools/qa/field-ocr/run.sh <originals-dir> <new-results-dir> <adb-serial>
set -euo pipefail
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <originals-dir> <new-results-dir> <adb-serial>" >&2
    exit 2
fi
project_root=$(cd "$(dirname "$0")/../../.." && pwd)
qa="$project_root/tools/qa/field-ocr"
review="$project_root/build/ui-review"
originals=$(cd "$1" && pwd)
mkdir -p "$2"
results=$(cd "$2" && pwd)
if [ -f "$results/run.json" ]; then
    echo "Choose a new directory to preserve existing evidence." >&2
    exit 2
fi
python3 - "$qa/samples.json" "$originals" <<'PY'
import hashlib, json, sys
from pathlib import Path
for name, meta in json.loads(Path(sys.argv[1]).read_text()).items():
    image = Path(sys.argv[2]) / (name + ".jpg")
    if hashlib.sha256(image.read_bytes()).hexdigest() != meta["sha256"]:
        sys.exit("Source checksum mismatch: " + str(image))
PY
sdk=${ANDROID_HOME:-"$review/sdk"}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
build_tools="$sdk/build-tools/36.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
app_classes="$project_root/app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
build=$(mktemp -d "$review/field-probe-XXXXXXXX")
adb=("$sdk/platform-tools/adb" -s "$3")
mkdir -p "$build/classes" "$build/dex"
"$JAVA_HOME/bin/javac" --release 8 -classpath "$android_jar:$app_classes" -d "$build/classes" "$qa/FieldRunner.java"
"$JAVA_HOME/bin/jar" cf "$build/probe.jar" -C "$build/classes" .
"$build_tools/d8" --lib "$android_jar" --min-api 28 --output "$build/dex" "$build/probe.jar"
"$build_tools/aapt" package -f -I "$android_jar" -M "$qa/AndroidManifest.xml" -F "$build/raw.apk"
(cd "$build/dex" && zip -q "$build/raw.apk" classes.dex)
"$build_tools/zipalign" -f 4 "$build/raw.apk" "$build/aligned.apk"
"$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$build/probe.apk" "$build/aligned.apk"
"${adb[@]}" install -r "$build/probe.apk"
device_dir=/sdcard/Android/data/com.scandesk.app/files/fieldstudy
"${adb[@]}" shell mkdir -p "$device_dir"
for name in menu bus forms; do
    "${adb[@]}" push "$originals/$name.jpg" "$device_dir/$name.jpg"
done
"${adb[@]}" shell am instrument -w com.scandesk.fieldcheck/com.scandesk.app.FieldRunner | tee "$results/instrumentation.txt"
grep -Fq 'INSTRUMENTATION_RESULT: result=done' "$results/instrumentation.txt"
"${adb[@]}" pull "$device_dir/run.json" "$results/run.json"
for name in menu bus forms; do
    for suffix in auto.docx manual.docx manual.pdf; do
        "${adb[@]}" pull "$device_dir/$name-$suffix" "$results/$name-$suffix"
    done
done
installed_version=$("${adb[@]}" shell dumpsys package com.scandesk.app | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
package_path=$("${adb[@]}" shell pm path com.scandesk.app | sed -n 's/^package://p' | head -1 | tr -d '\r')
app_hash=$("${adb[@]}" shell sha256sum "$package_path" | cut -d ' ' -f 1)
python3 "$qa/score.py" "$results" --version "$installed_version" --apk-sha256 "$app_hash" --output "$results/score.json"
