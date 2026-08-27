# Performance: what actually limits frame rate here

Everything marked **verified** was produced by a command quoted next to it.
Everything else is labelled as a hypothesis.

---

## 1. The bundled runtime is Mono, not CoreCLR — verified

`app/src/main/assets/dotnet-runtime.tgz` contains
`shared/Microsoft.NETCore.App/8.0.22/libcoreclr.so` (AArch64 ELF). Despite the
filename it is a **Mono** build:

```
$ strings libcoreclr.so | grep -oE "mono_(gc|aot|interp)_[a-z_]*" | sort -u
mono_aot_register_module    mono_gc_collect       mono_interp_exec_method
mono_gc_alloc_obj           mono_gc_is_moving     mono_interp_tiering_enabled
...
$ strings libcoreclr.so | grep -cE "ICorJitCompiler|WKS::gc_heap|TieredCompilation|OnStackReplacement"
0
$ strings libcoreclr.so | grep -m1 GC_MAJOR
GC_MAJOR%s: (%s) time %.2fms, %s los size: ...        # SGen collector
$ strings libcoreclr.so | grep -m1 /home/maks
/home/maks/dotnet/runtime-8.0/src/mono/mono/mini/aot-compiler.c
```

**Why this matters:** `DotnetStarter` used to set roughly twenty `DOTNET_GC*`,
`DOTNET_Tiered*`, `DOTNET_TC*`, `DOTNET_EnableHWIntrinsic`,
`DOTNET_JitEnableArm64Simd` and `COMPlus_*` variables. Mono reads none of them —
it reads `MONO_ENV_OPTIONS`. The only `DOTNET_`/`COMPlus_` names present in this
binary are the diagnostics ones:

```
$ strings libcoreclr.so | grep -E "^(DOTNET|COMPlus)_" | sort -u
COMPlus_EnableDiagnostics    DOTNET_EnableEventPipe    DOTNET_EventPipeOutputStreaming
DOTNET_DefaultDiagnosticPortSuspend    DOTNET_DiagnosticPorts
DOTNET_EnableDiagnostics     DOTNET_EventPipeCircularMB  ...
```

`DotnetStarter` now sets `MONO_ENV_OPTIONS` instead. Every token used was
confirmed present in that exact binary:

```
--gc=[sgen,boehm]
--gc-params= nursery-size=N | minor=[simple,split]
             major=[marksweep,marksweep-conc,marksweep-par]
--optimize=  inline cfold deadce consprop copyprop fcmov leaf loop
             float32 simd abcrem ssapre
MONO_THREADS_SUSPEND (coop | hybrid | preemptive)
```

None of this has been benchmarked. Treat the values as a starting point and
measure — see §6.

---

## 2. The LLVM backend is compiled out — verified

```
$ nm -D --defined-only libcoreclr.so | grep -i llvm
00000000000dfbb4 T mono_set_use_llvm
000000000035b438 B mono_use_llvm
$ nm -D libcoreclr.so | grep -cE "LLVMInitialize|LLVMContextCreate"
0
$ strings libcoreclr.so | grep "requires a runtime compiled with llvm"
--aot=llvm requires a runtime compiled with llvm support.
```

No `LLVMInitialize*` symbols, so `--llvm` cannot work and is not passed. The
game runs on Mono's baseline JIT. Rebuilding the runtime with LLVM enabled is
the largest CPU-side lever available and cannot be done from this repository.

What *is* compiled in: the baseline JIT, the interpreter with tiering
(`mono_interp_tiering_enabled`), SGen, and AOT *loading*
(`--aot-path=`, `mono_aot_register_module`, `%s.aotdata`).

---

## 3. The launcher was capping frame rate itself — verified in source

`ThermalManager` called `setSwapInterval(2)` at `THERMAL_STATUS_MODERATE` and
`(3)` at `CRITICAL`. Escalation was immediate; de-escalation waited 15 s.
`eglSwapInterval(n)` means *refresh rate / n*:

| Panel | interval 1 | interval 2 | interval 3 |
|---|---|---|---|
| 60 Hz | 60 | **30** | 20 |
| 90 Hz | 90 | **45** | 30 |
| 120 Hz | 120 | **60** | 40 |

So a device reaching MODERATE within a few minutes of play was silently pinned
at refresh/2 regardless of settings. Throttling is now opt-in
(`thermal-throttle.txt` containing `on` in the app files dir), and never goes
below interval 2 when enabled.

Two bugs fixed alongside:

- `stop()` passed a **fresh** `this::onThermalStatus` to
  `removeThermalStatusListener`, which matches by identity and so removed
  nothing. Every `onCreate` added another listener.
- `lastEscalationTime` was never reset on de-escalation, so hysteresis was lost
  after the first cool-down.

---

## 3b. `glfw34/` was empty in git — verified

`app/src/main/cpp/CMakeLists.txt:25` does `add_subdirectory(glfw34)`, but the
committed tree had an **empty** `glfw34/` directory — so the native build could
not be reproduced from a clean clone. It is now vendored from
<https://github.com/VSMobile/glfw> (branch `glfw34`, `cbfd2b0`), 177 files.

Three things were wrong once the real source was in place:

- **`ThermalManager.setSwapInterval` was implemented in neither branch** of that
  repo (`git grep` on `glfw34` and `master`), though the symbol exists in the
  previously committed `app/.cxx/.../android_window.c.o` — a local patch that
  was never pushed. A clean build raised `UnsatisfiedLinkError`, caught and
  logged, so throttling silently did nothing. Added as a wrapper over
  `glfwSwapInterval` (`glfw3.h:6211`, `context.c:665`).
- **`GLFW.sendScrollEvent` was also unimplemented**, and unlike
  `setSwapInterval` its call site in `ControlButton.executeKeyEvent` has no
  catch — so pressing an `SP_MOUSE_SCROLL_UP`/`DOWN` button (a headline feature
  of this fork) raised an uncaught `UnsatisfiedLinkError`. Implemented end to
  end: new `GLFW_ANDROID_EVENT_TYPE_SCROLL`, a union member, a dispatch case
  calling `_glfwInputScroll` (`internal.h:931`), and the JNI export.
- **`FLAG_APP_FOCUS` was `(1 >> 1)`, i.e. 0**, so it could never be set in
  `update_flags`. Both flags changed to `<<`.
- Also fixed an upstream wrong `sizeof`: `malloc(length * sizeof(codepoints))`
  used `sizeof(pointer)` (8) instead of `sizeof(jchar)` (2).

All ten Java `native` declarations now have matching C exports.

---

## 4. Frame rate ceiling: read this before chasing a number

`eglSwapInterval(0)` is the only setting not locked to the panel. With vsync on,
FPS can only ever be `refresh / interval`. **A target above the panel's refresh
rate is physically unreachable for a vsync'd app.**

For a 120 Hz panel the vsync steps are **120, 60, 40, 30, 24, 20**. Neither 80
nor 90 is one of them, so "at least 80" or "above 90" means either *120 locked*
or *uncapped* (with tearing). Uncapped raises power draw and heat, which pushes
the SoC into the throttling that then costs more FPS than it gained.

---

## 5. Where the remaining cost is (ranked)

1. **Mono JIT quality.** CPU-heavy .NET on the baseline JIT with no LLVM. Two
   attacks, both outside this repo: AOT (see `docs/AOT-PLAN.md`) and an LLVM
   runtime rebuild.
2. **ANGLE translation.** `LIBGL_EGL=libEGL_angle.so`, and
   `libGLESv2_angle.so` contains `src/libANGLE/renderer/vulkan/*` — the path is
   OpenGL ES → **Vulkan** via ANGLE, so every GL call is translated.
   Unavoidable without changing the game's renderer.
3. **Fill rate.** Render-resolution scaling is the cheapest GPU win and is a
   game setting, not a launcher one.
4. **Thermals.** Sustained clocks matter more than peak. See §3.

---

## 6. Tuning without rebuilding

Two optional files in the app's files dir
(`/data/data/git.artdeell.dnbootstrap/files/`, reachable through the in-app
file manager):

| File | Effect |
|---|---|
| `mono-env.txt` | First non-empty, non-`#` line replaces `MONO_ENV_OPTIONS`. A single blank line disables runtime tuning. |
| `thermal-throttle.txt` | `on` re-enables thermal frame-rate capping (off by default). |

To A/B a change: record in-game FPS, edit the file, restart, record again.
Change one token at a time — both `--gc-params` and `--optimize` take
comma-separated lists, so a bad token is not always obvious.
