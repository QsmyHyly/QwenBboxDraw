#!/usr/bin/env bash
# 运行纯逻辑层的 JVM 单元测试（仅 BboxParser，忠实移植 qwen3-vl-2d.py 解析逻辑）。
set -euo pipefail
cd "$(dirname "$0")"

JAR="$HOME/.cache/android-sdk/android-all-14-10818077.jar"
ROOT="app/src/main/java/com/dsh/qwenbbox"
TEST_SRC="tests/src"
OUT="build/tests"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/2] 编译（javac）..."
javac -cp "$JAR" -d "$OUT" \
  "$ROOT/log/AppLog.java" \
  "$ROOT/api/VisionApiClient.java" \
  "$ROOT/core/BboxParser.java" \
  "$ROOT/ProviderConfig.java" \
  "$TEST_SRC/TestRunner.java"

echo "[2/2] 运行（java）..."
java -cp "$JAR:$OUT" TestRunner
