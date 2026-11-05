#!/usr/bin/env bash
# Copies the Samsung NPU parts into app/src/npu for a GitHub release build (docs/RELEASE.md): the MediaPipe hand models
# compiled for the Exynos 2500's NPU, our LiteRT build and the Samsung dispatch. They are not in the public repository
# (their licence is not yet checked), so they come from the project's private repository at a pinned commit, and each
# file is checked against its SHA-256 below. app/src/npu is gitignored, and Play builds (-Ppocketxr.play=true) never
# take it. Needs the GitHub CLI (gh), logged in with read access to the private repository.
# Usage: tools/fetch-npu.sh            (run from the repository root)
set -euo pipefail

repo=mhaye20/flip-spaces
# The commit that moved the NPU parts to app/src/npu. The same four files as in the 1.1.12 GitHub release.
ref=443c6cb0ab9eba1d82d6e8555f7f9c7465e6bfda
dest=app/src/npu

files=(
  "assets/npu/hand_detector_e9955.tflite 04cc8963055b647437f00afc04fc759890baab3d1540280bf4635e4e914044e4"
  "assets/npu/hand_landmarks_e9955.tflite 3a3eff0ce29147ad8bfb1b9872c89f9e0b815b747bf5ac5c88c375fb57becb8d"
  "jniLibs/arm64-v8a/libLiteRt.so 2164acbab6dd5b6470db02b0372e64f39dff66da7ae47c8e27444c8e91893edd"
  "jniLibs/arm64-v8a/libLiteRtDispatch_Samsung.so 20d28259c42182c2750b13e6b4a8ebb6a5b935903d760f868975da81d1214d06"
)

[ -f settings.gradle.kts ] || { echo "fetch-npu: run from the repository root"; exit 2; }
command -v gh >/dev/null || { echo "fetch-npu: the GitHub CLI (gh) is needed"; exit 2; }

fail=0
for entry in "${files[@]}"; do
  path=${entry% *}
  want=${entry#* }
  out="$dest/$path"
  mkdir -p "$(dirname "$out")"
  gh api -H "Accept: application/vnd.github.raw" "repos/$repo/contents/app/src/npu/$path?ref=$ref" > "$out.part"
  got=$(sha256sum "$out.part" | cut -d' ' -f1)
  if [ "$got" = "$want" ]; then
    mv "$out.part" "$out"
    echo "ok   $out ($(wc -c < "$out") bytes)"
  else
    rm -f "$out.part"
    echo "FAIL $out: SHA-256 $got, expected $want"
    fail=1
  fi
done
[ "$fail" = 0 ] || { echo "fetch-npu: FAILED"; exit 1; }
echo "fetch-npu: OK (4 files in $dest)"
