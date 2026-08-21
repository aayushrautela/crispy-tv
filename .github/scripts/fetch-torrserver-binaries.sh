#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JNI_LIBS_DIR="$ROOT_DIR/android/native-engine/src/main/jniLibs"

TAG="MatriX.143"
BASE_URL="https://github.com/YouROK/TorrServer/releases/download/${TAG}"

ABIS=(arm64-v8a armeabi-v7a x86_64)

declare -A FILES=(
  [arm64-v8a]="TorrServer-android-arm64"
  [armeabi-v7a]="TorrServer-android-arm7"
  [x86_64]="TorrServer-android-amd64"
)

declare -A SHA256=(
  [arm64-v8a]="23cea145c38e948f1a967c7fdbcb9c71506cd21a2fe7b3723903e233a323465b"
  [armeabi-v7a]="9bab078a0976b86ff392c9eee756194643f4e939ee2c9504dfd4ab7094ef9490"
  [x86_64]="03657ceb430d9f72475598525a2cc6fd90e8732615f615673e9635a024c3b454"
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
