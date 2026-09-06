#!/usr/bin/env bash
# 一键构建签名 APK（纯平台构建，无 Gradle）。
set -euo pipefail
cd "$(dirname "$0")"

JAR="$HOME/.cache/android-sdk/android-all-14-10818077.jar"

build-apk . \
  --manifest-path app/src/main/AndroidManifest.xml \
  --src-dir app/src/main/java \
  --res-dir app/src/main/res \
  --manifest-package com.dsh.qwenbbox \
  --android-jar "$JAR" \
  --min-sdk 21 --target-sdk 34 \
  --keystore ./debug.keystore \
  --out ./QwenBboxDraw.apk

echo
echo "== 构建后自检：dex 关键类是否齐全 =="
python3 - ./QwenBboxDraw.apk <<'PY'
import sys, zipfile
apk = sys.argv[1]
z = zipfile.ZipFile(apk)
dexs = [n for n in z.namelist() if n.endswith('.dex')]
data = b"".join(z.read(n) for n in dexs)
cls = [
  "com/dsh/qwenbbox/ui/MainActivity;",
  "com/dsh/qwenbbox/ui/LogViewActivity;",
  "com/dsh/qwenbbox/ui/ZoomableImageView;",
  "com/dsh/qwenbbox/api/VisionApiClient;",
  "com/dsh/qwenbbox/core/BboxParser;",
  "com/dsh/qwenbbox/log/AppLog;",
  "com/dsh/qwenbbox/ProviderConfig;",
  "com/dsh/qwenbbox/SettingsStore;",
]
miss = [c for c in cls if c.encode() not in data]
if miss:
    print("  [警告] dex 缺少类:")
    for c in miss: print("   -", c)
    sys.exit(2)
print("  [OK] 关键类齐全（共 %d 个 dex）" % len(dexs))
PY

echo
echo "✅ 构建完成: $(pwd)/QwenBboxDraw.apk"
