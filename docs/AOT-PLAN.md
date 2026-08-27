# AOT plan for Vintage Story on Android

Companion to `docs/PERFORMANCE.md`. Everything marked **verified** came from a
command quoted next to it.

---

## 1. What the supplied assemblies are — verified

Read with `tools/dotnet_meta.py`:

| File | Machine | PE | TFM | ILONLY |
|---|---|---|---|---|
| `Vintagestory.dll` (94 KB) | x64 | PE32+ | .NETCoreApp v8.0 | **yes** |
| `VintagestoryAPI.dll` (2.0 MB) | x86/AnyCPU | PE32 | .NETCoreApp v8.0 | **yes** |
| `VintagestoryLib.dll` (2.9 MB) | x86/AnyCPU | PE32 | .NETCoreApp v8.0 | **yes** |

All three are IL-only, so they can be AOT-compiled for arm64. TFM `net8.0`
matches the bundled runtime `8.0.22`. `Vintagestory.dll` being PE32+/x64 does
not block this — `32BITREQUIRED` is false — and the game already runs on device.

The tool is self-checking: the `#~` count array must end exactly where the row
data begins (`header_bytes == 24 + 4 * ntables`), and it correctly rejects
native ELF (`libSkiaSharp.so`, the `Vintagestory` apphost) as not managed.

**Scope note.** An earlier attempt also walked the `AssemblyRef` table to derive
the dependency closure. It was removed: the `#~` valid mask on .NET 8 assemblies
sets table ids above `0x23`, which the published ECMA-335 schema does not
describe, and no self-check could confirm a row-size table against a known-good
assembly. Rather than ship a plausible-looking but wrong dependency list, the
tool reports only what it can validate. Use `Vintagestory.deps.json` for the
closure — it is strictly more information.

---

## 2. The dependency closure — verified from `Vintagestory.deps.json`

`libraries` lists **81** entries. Notable ones:

```
OpenTK.* 4.9.4            (Core, Graphics, Mathematics, Windowing.*, Audio.OpenAL, Compute, Input)
Newtonsoft.Json 13.0.3    SkiaSharp 3.116.1        protobuf-net 2.4.9
SharpZipLib 1.4.2         Microsoft.Data.Sqlite 8.0.13
Microsoft.CodeAnalysis.CSharp 4.12.0               Mono.Cecil 0.11.6
MonoMod.* (Core 1.2.3, Utils 25.0.8, Backports, ILHelpers, Iced)
Lib.Harmony.Thin 2.3.6    GtkSharp/AtkSharp/GdkSharp/GioSharp/GLibSharp/PangoSharp/CairoSharp 3.24.24.95
Eto.Forms 2.9.0           SharpAvi 3.2.0-vs.1      System.Text.Json 8.0.5
```

---

## 3. Blocker: `--full-aot` will not work — verified

`VintagestoryLib.dll` pulls in `Microsoft.CodeAnalysis.CSharp 4.12.0` — Vintage
Story compiles C# mods at runtime with Roslyn. Roslyn depends on reflection and
dynamic code emission that AOT cannot represent. The same applies to
`0Harmony` / `MonoMod.*` / `Mono.Cecil.*`, which patch IL at runtime.

So: **plain AOT with the JIT left enabled.** Expect partial coverage; the win is
concentrated in the client render/tick path, not in 100% of methods.

---

## 4. Two hard constraints — verified in `libcoreclr.so`

```
$ strings libcoreclr.so | grep -F "info->version"
info->version == MONO_AOT_FILE_VERSION
$ strings libcoreclr.so | grep -F "unusable"
AOT: module %s is unusable (GUID of dependent assembly %s doesn't match (expected '%s', got '%s')).
```

1. The first is an **assertion**. Images built by a different Mono revision are
   not merely slower — they are rejected, or the runtime aborts. The
   cross-compiler must come from the same `runtime-8.0` tree this
   `libcoreclr.so` was built from (source paths inside it read
   `/home/maks/dotnet/runtime-8.0/src/mono`).
2. Each image is locked to the **identity of the assemblies it was compiled
   against**. AOT must run over the exact DLL files that ship on the device; a
   patched, re-signed or re-downloaded copy invalidates the images built against
   the original.

**Could not verify:** the actual `MONO_AOT_FILE_VERSION` this runtime requires.
`mono_aot_version` is not an exported symbol and the `.so` is stripped. The
practical check is to build one assembly, run it, and read the rejection message
— the runtime names both versions.

---

## 5. What to compile

`tools/aot/classify.py` sorts an install by PE/CLI header. On the full tree
(game root + `Lib/`, 99 files):

```
managed   63 files   33.4 MB
native    10 files   12.4 MB
other     26 files    3.9 MB
```

`tools/aot/aot-targets.txt` narrows that to **20** client-hot-path assemblies.
Excluded, with reasons recorded in the file:

- **Roslyn, Harmony/MonoMod/Cecil** — `Reflection.Emit`; AOT images would be
  ignored and the JIT is required for them to work at all (§3).
- **GtkSharp / AtkSharp / GdkSharp / GioSharp / GLibSharp / PangoSharp / Eto\***
  — desktop GTK UI stack, unused under the Android launcher.
- **VintagestoryServer / VSCrashReporter\* / ModMaker\*** — never loaded by the
  client. The four extensionless files (`Vintagestory`, `VintagestoryServer`,
  `ModMaker`, `VSCrashReporter`) are ELF linux-x64 apphosts; the launcher starts
  the runtime itself through `libhostfxr`.
- **System.ServiceModel\*** — WCF, not on the client path.

Excluded assemblies still JIT normally. The list only decides where AOT effort
and image size go.

Safe to delete outright: the 26 "other" files (3.9 MB) — all `.pdb`,
`VintagestoryAPI.xml` (2 MB), `*.deps.json`, `*.runtimeconfig.json`, the three
`.desktop` files, the shell scripts, `fonts.conf`. Keep the six `.so` files in
`Lib/`; those are real runtime dependencies.

---

## 6. Build recipe

The cross-compiler is **not** in the bundled runtime — verified:
`dotnet-runtime.tgz` holds 7 `.so` files and no compiler:

```
$ tar tzf dotnet-runtime.tgz | grep -icE "crosscompiler|mono-aot|aot-compiler"
0
```

Get it from the .NET-for-Android workload on an x86_64 Linux box (a Codespace is
fine):

```
dotnet workload install android
```

then build **only** the AOT cross-compiler for android-arm64 from the same
`runtime-8.0` tree, and run:

```
MONO_AOT_CROSS=/path/to/mono-aot-cross ./tools/aot/build-aot.sh <game-dir> ./aot-out
```

The script discovers the compiler, filters non-managed PEs by CLI header, runs
the per-assembly loop and verifies each output is real AArch64 ELF. Set
`AOT_TARGETS=all` to compile every managed assembly instead of the 20.

Ship the resulting `.so` files into a folder named `aot` next to the game
assemblies. `DotnetStarter` appends `--aot-path=<game>/aot` automatically when
that directory holds `.so` files — no code change needed.

Confirm the images are used: a version or GUID mismatch appears in the log as
`AOT: module ... is unusable (GUID of dependent assembly ...)`. Grep for
`unusable` before assuming the images were not found.

---

## 7. The other big lever: rebuild Mono with LLVM

The bundled `libcoreclr.so` has no LLVM linked (see `PERFORMANCE.md` §2), so
`--llvm` is unusable today. Rebuilding the runtime with LLVM enabled and passing
`--llvm` in `MONO_ENV_OPTIONS` is typically a large win on CPU-bound .NET, and
composes with AOT. Do it in the same build as §6 so one toolchain produces both.

---

## 8. Shaders

91 files: 42 `.vsh` + 42 `.fsh` under `game/`, 3 `.vsh` + 4 `.fsh` under
`survival/`. ANGLE translates each to SPIR-V and caches the result, so the first
run after a shader change pays a one-off compile cost that is not representative
of steady-state frame rate. Warm the cache before measuring.
