#!/usr/bin/env bash
# Compiles the renderer package + demo against JVM android-stubs and runs
# the headless end-to-end test (loader -> skinning -> animation -> draw).
#
# Requires: kotlinc (https://github.com/JetBrains/kotlin/releases) and
# org.json (https://repo1.maven.org/maven2/org/json/json/)
#
# Usage:
#   KOTLINC=/path/to/kotlinc/bin/kotlinc \
#   JSON_JAR=/path/to/org.json.jar \
#   tools/headless-test/run.sh
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
KOTLINC="${KOTLINC:-$(command -v kotlinc || echo /tmp/kotlinc/bin/kotlinc)}"
JSON_JAR="${JSON_JAR:-/tmp/org.json.jar}"
OUT="$(mktemp -d)"
STDLIB="$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"

"$KOTLINC" \
  -classpath "$JSON_JAR" \
  -d "$OUT" \
  "$HERE/stubs" \
  "$ROOT"/native/app/src/main/java/com/aura/avatarstudio/renderer/*.kt \
  "$ROOT"/native/app/src/main/java/com/aura/avatarstudio/renderer/hd/*.kt \
  "$ROOT"/native/app/src/main/java/com/aura/avatarstudio/GltfAvatarView.kt \
  "$ROOT"/native/app/src/main/java/com/aura/avatarstudio/MainActivity.kt \
  "$ROOT"/android/app/src/main/java/ai/grokgirls/studio/NativeAvatarActivity.kt \
  "$ROOT"/android/app/src/main/java/ai/grokgirls/studio/AvatarStudioPlugin.kt \
  "$HERE/TestMain.kt"

java -cp "$OUT:$JSON_JAR:$STDLIB" TestMainKt
rm -rf "$OUT"
