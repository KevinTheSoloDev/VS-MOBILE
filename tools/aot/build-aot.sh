#!/usr/bin/env bash
# Produce Mono AOT images for Vintage Story's managed assemblies, targeting
# android-arm64, so the launcher can load them via --aot-path=.
#
# Run on Linux (a GitHub Codespace is fine). Does NOT build the APK.
#
#   ./tools/aot/build-aot.sh <GAME_DIR> [OUT_DIR]
#
#   GAME_DIR  extracted Vintage Story install root (holds Vintagestory.dll and
#             the Lib/ folder). Must be byte-identical to what runs on device.
#   OUT_DIR   where images are written (default ./aot-out)
#
# Env:
#   MONO_AOT_CROSS  path to the aarch64 cross-compiler (else autodetected)
#   AOT_TARGETS     targets file (default tools/aot/aot-targets.txt next to
#                   this script); set to "all" to compile every managed PE
#
# ── Why the inputs must match exactly ─────────────────────────────────────
# strings on the bundled libcoreclr.so turns up:
#   info->version == MONO_AOT_FILE_VERSION
#   AOT: module %s is unusable (GUID of dependent assembly %s doesn't match
#       (expected '%s', got '%s')).
# The first is an assertion: images from a different Mono revision are rejected.
# The second locks each image to the identity of the assemblies it was compiled
# against. So the cross-compiler must come from the same runtime-8.0 tree this
# libcoreclr.so was built from, and AOT must run over the exact shipped DLLs.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME_DIR="${1:-}"
OUT_DIR="${2:-./aot-out}"
TARGETS="${AOT_TARGETS:-$HERE/aot-targets.txt}"

if [[ -z "$GAME_DIR" ]]; then
  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
fi
if [[ ! -f "$GAME_DIR/Vintagestory.dll" ]]; then
  echo "error: $GAME_DIR/Vintagestory.dll not found -- is that the game root?" >&2
  exit 2
fi

# ── locate the cross compiler ────────────────────────────────────────────
# It is NOT in the bundled runtime: dotnet-runtime.tgz holds 7 .so files and no
# compiler. The .NET-for-Android workload ships it.
CROSS=""
for c in "${MONO_AOT_CROSS:-}" aarch64-linux-android-mono-sgen \
         mono-aot-crosscompiler-aarch64 mono-aot-cross; do
  [[ -n "$c" ]] || continue
  if [[ -x "$c" ]] || command -v "$c" >/dev/null 2>&1; then CROSS="$c"; break; fi
done
if [[ -z "$CROSS" ]]; then
  while IFS= read -r f; do CROSS="$f"; break; done < <(
    find "$HOME/.nuget" "$HOME/.dotnet" /usr/lib/mono /usr/share/dotnet \
      -type f \( -name '*aarch64*android*mono*' -o -name 'mono-aot-cross*' \) \
      -perm -u+x 2>/dev/null || true)
fi
if [[ -z "$CROSS" ]]; then
  cat >&2 <<'MSG'
error: no Mono AOT cross-compiler for aarch64-linux-android found.

    dotnet workload install android

then put it on PATH, or:

    export MONO_AOT_CROSS=/path/to/mono-aot-cross

Build it from the SAME runtime-8.0 tree the bundled libcoreclr.so came from --
an image from a different revision is rejected by the version assertion.
MSG
  exit 3
fi
echo "cross-compiler: $CROSS"

# ── build the assembly list ──────────────────────────────────────────────
ASMS=()
if [[ "$TARGETS" == "all" ]]; then
  while IFS= read -r f; do ASMS+=("$f"); done < <(
    find "$GAME_DIR" -name '*.dll' -type f | sort)
  echo "targets: all managed assemblies (${#ASMS[@]} found)"
else
  [[ -f "$TARGETS" ]] || { echo "error: targets file not found: $TARGETS" >&2; exit 2; }
  missing=0
  while IFS= read -r line; do
    line="${line%%#*}"; line="$(echo "$line" | xargs || true)"
    [[ -n "$line" ]] || continue
    if [[ -f "$GAME_DIR/$line" ]]; then ASMS+=("$GAME_DIR/$line");
    else echo "  WARN  listed but absent: $line" >&2; missing=$((missing+1)); fi
  done < "$TARGETS"
  echo "targets: $(basename "$TARGETS") -> ${#ASMS[@]} assemblies ($missing listed but missing)"
fi

mkdir -p "$OUT_DIR"

# NB: do not put outfile= in AOT_OPTS -- it is appended per assembly below and
# Mono does not tolerate the option appearing twice.
AOT_OPTS="${AOT_OPTS:-direct-icalls,dwarfdebug,nodebug}"
MTRIPLE="${MTRIPLE:-aarch64-linux-android}"

ok=0; skipped=0; failed=()
for asm in "${ASMS[@]}"; do
  name="$(basename "$asm" .dll)"
  # skip anything without a CLI header (a native .so renamed .dll, say)
  if ! python3 -c "
import struct,sys
d=open(sys.argv[1],'rb').read()
assert d[:2]==b'MZ'
pe=struct.unpack_from('<I',d,0x3C)[0]
assert d[pe:pe+4]==b'PE\0\0'
m=struct.unpack_from('<H',d,pe+24)[0]
assert m in (0x10b,0x20b)
sys.exit(0 if struct.unpack_from('<I',d,pe+24+(112 if m==0x20b else 96)+14*8)[0] else 1)
" "$asm" 2>/dev/null; then
    echo "  skip  $name (no CLI header)"; skipped=$((skipped+1)); continue
  fi
  echo "  aot   $name"
  if "$CROSS" --aot="${AOT_OPTS},mtriple=${MTRIPLE},outdir=${OUT_DIR},outfile=${OUT_DIR}/libaot-${name}.so" \
       "$asm" >"${OUT_DIR}/${name}.log" 2>&1; then
    ok=$((ok+1))
  else
    failed+=("$name"); echo "        FAILED -- see ${OUT_DIR}/${name}.log"
  fi
done

echo; echo "compiled: $ok   skipped: $skipped   failed: ${#failed[@]}"
[[ ${#failed[@]} -eq 0 ]] || printf '  - %s\n' "${failed[@]}"

echo; echo "images produced:"
found=0
for so in "$OUT_DIR"/libaot-*.so; do
  [[ -e "$so" ]] || continue
  found=1
  arch="$(readelf -h "$so" 2>/dev/null | awk -F: '/Machine/{gsub(/^ +/,"",$2);print $2}')"
  printf '  %-44s %9s  %s\n' "$(basename "$so")" "$(du -h "$so"|cut -f1)" "${arch:-unknown}"
done
[[ $found -eq 1 ]] || { echo "  (none)"; exit 1; }

cat <<'MSG'

Next steps
  1. Push OUT_DIR/*.so into a folder named "aot" next to the game assemblies.
  2. DotnetStarter adds --aot-path=<game>/aot automatically when it holds .so
     files -- no code change needed.
  3. Verify the images are used. A version or GUID mismatch shows in the log as
        AOT: module ... is unusable (GUID of dependent assembly ...)
     grep the app log for "unusable" before assuming images were not found.
MSG
