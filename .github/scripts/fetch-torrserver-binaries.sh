#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JNI_LIBS_DIR="$ROOT_DIR/android/native-engine/src/main/jniLibs"

TAG="MatriX.141"
BASE_URL="https://github.com/YouROK/TorrServer/releases/download/${TAG}"

ABIS=(arm64-v8a armeabi-v7a x86_64)

declare -A FILES=(
  [arm64-v8a]="TorrServer-android-arm64"
  [armeabi-v7a]="TorrServer-android-arm7"
  [x86_64]="TorrServer-android-amd64"
)

declare -A SHA256=(
  [arm64-v8a]="ff35f24ac57b0bc42137822118007ad9601cf14db616e4b33b11c0c3befd4e20"
  [armeabi-v7a]="72bd32c086ffa6b538ae3ae78bfad1da4fee92a38a44230da5204ac3848990f0"
  [x86_64]="c255d39e45e509a2e9458c69240b5e640140d68c40c9a35330e5ad3991a750d6"
)

mkdir -p "$JNI_LIBS_DIR"

for abi in "${ABIS[@]}"; do
  file_name="${FILES[$abi]}"
  target_dir="$JNI_LIBS_DIR/$abi"
  target_path="$target_dir/libtorrserver.so"
  tmp_path="$target_path.tmp"

  mkdir -p "$target_dir"
  echo "Downloading $file_name for ABI $abi"
  curl -fsSL "${BASE_URL}/${file_name}" -o "$tmp_path"

  actual_sha="$(sha256sum "$tmp_path" | awk '{print $1}')"
  expected_sha="${SHA256[$abi]}"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    echo "SHA256 mismatch for $file_name"
    echo "  expected: $expected_sha"
    echo "  actual:   $actual_sha"
    exit 1
  fi

  mv "$tmp_path" "$target_path"
  chmod +x "$target_path"
done

echo "TorrServer binaries downloaded and verified in $JNI_LIBS_DIR"
