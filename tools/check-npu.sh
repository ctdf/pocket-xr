#!/usr/bin/env bash
# Checks a GitHub release APK (docs/RELEASE.md) has the Samsung NPU parts that tools/fetch-npu.sh brings in: both hand
# models compiled for the Exynos 2500, the Samsung dispatch, and our LiteRT build rather than the LiteRT AAR's. Lists the
# APK's lib and assets first.
# Usage: tools/check-npu.sh path/to/pocketxr-x.y.z.apk
set -euo pipefail

apk=${1:?usage: tools/check-npu.sh path/to/app.apk}
[ -f "$apk" ] || { echo "check-npu: no such file: $apk"; exit 2; }

listing=$(unzip -Zl "$apk" | awk '$NF ~ /^(lib|assets)\// { print $4, $NF }' | sort -k2)
echo "lib and assets in $apk (bytes, path):"
echo "$listing"
echo

fail=0
for want in assets/npu/hand_detector_e9955.tflite assets/npu/hand_landmarks_e9955.tflite \
    lib/arm64-v8a/libLiteRtDispatch_Samsung.so; do
  if echo "$listing" | awk '{ print $2 }' | grep -qxF "$want"; then
    echo "ok   $want"
  else
    echo "FAIL $want is missing"
    fail=1
  fi
done
# Our NPU LiteRT build is 14325056 bytes; the LiteRT AAR's own libLiteRt.so is much smaller and cannot load the dispatch.
size=$(echo "$listing" | awk '$2 == "lib/arm64-v8a/libLiteRt.so" { print $1 }')
if [ "$size" = 14325056 ]; then
  echo "ok   lib/arm64-v8a/libLiteRt.so is our NPU LiteRT build ($size bytes)"
else
  echo "FAIL lib/arm64-v8a/libLiteRt.so is ${size:-missing} bytes, not our NPU LiteRT build (14325056)"
  fail=1
fi

[ "$fail" = 0 ] || { echo "check-npu: FAILED"; exit 1; }
echo "check-npu: OK (NPU hand tracking parts are in)"
