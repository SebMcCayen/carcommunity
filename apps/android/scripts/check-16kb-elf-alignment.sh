#!/usr/bin/env bash
set -euo pipefail

# Google Play evaluates every native shared library in the release. A single
# 4 KB-aligned LOAD segment makes the app incompatible with 16 KB-page devices.
# Check the merged release inputs so this fails before an APK/AAB is published.
native_lib_dir="${1:-app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib}"

if [[ ! -d "$native_lib_dir" ]]; then
  echo "ERROR: Native library directory not found: $native_lib_dir" >&2
  echo "Build it first with: ./gradlew :app:mergeReleaseNativeLibs" >&2
  exit 2
fi

abi_dirs=()
for abi in arm64-v8a x86_64; do
  if [[ -d "$native_lib_dir/$abi" ]]; then
    abi_dirs+=("$native_lib_dir/$abi")
  fi
done

# Android's 16 KB page-size devices are 64-bit. This matches Google's official
# check, which evaluates arm64-v8a and x86_64 and intentionally ignores the
# 32-bit armeabi-v7a/x86 fallback binaries.
if (( ${#abi_dirs[@]} == 0 )); then
  echo "No 64-bit native libraries found; nothing to check."
  exit 0
fi

mapfile -d '' libraries < <(find "${abi_dirs[@]}" -type f -name '*.so' -print0 | sort -z)
if (( ${#libraries[@]} == 0 )); then
  echo "No native libraries found; nothing to check."
  exit 0
fi

failed=0
for library in "${libraries[@]}"; do
  while read -r alignment; do
    # readelf prints hexadecimal p_align values. Arithmetic expansion handles
    # both 0x-prefixed hex and decimal without external conversion tools.
    if (( alignment < 0x4000 )); then
      printf 'ERROR: %s has LOAD alignment %s (requires at least 0x4000).\n' \
        "$library" "$alignment" >&2
      failed=1
      break
    fi
  done < <(readelf -lW "$library" | awk '$1 == "LOAD" { print $NF }')
done

if (( failed != 0 )); then
  echo "16 KB ELF alignment check failed." >&2
  exit 1
fi

printf '16 KB ELF alignment check passed for %d native libraries.\n' "${#libraries[@]}"
