# VS-Mobile: Tweaked

A fork of [artdeell's dnbootstrap](https://github.com/VSMobile/dnbootstrap) with additional contributions from [Temder](https://github.com/Temder) and performance work by [Solo].

> ⚠️ **This fork is still under active testing and may be unstable. Use at your own risk.**
> For the stable upstream, see the [original dnbootstrap repo](https://github.com/VSMobile/dnbootstrap) and the [VS-Mobile root org](https://github.com/VSMobile).

---

## What's New in This Fork

### 🖱️ Mouse Scroll Wheel Buttons
Added `SP_MOUSE_SCROLL_UP` and `SP_MOUSE_SCROLL_DOWN` as assignable button actions in the HUD layout editor.

Previously, selecting hotbar slots required placing 10 individual number buttons (0–9) in the layout. Now two scroll buttons cover all hotbar slots, freeing up significant screen space.

### 🕹️ Joystick Support *(by Temder)*
A virtual joystick control is now available in the HUD layout editor, along with other editor improvements contributed by Temder.

### ⚡ Performance Work
Changes to reduce CPU heat and improve frame pacing:

- **Fixed bitmask bug** in the native GLFW layer (`>>` → `<<`). `FLAG_APP_FOCUS` was `(1 >> 1)`, i.e. `0`, so it could never be set in `update_flags`
- **Removed spurious pipe write** that fired on every event loop cycle with no effect
- **Coalesced cursor redraws** — rapid touch-move events no longer flood the UI thread with individual invalidate calls
- **Runtime tuning corrected.** The bundled `libcoreclr.so` is actually a **Mono** build, so the ~20 `DOTNET_GC*` / `DOTNET_Tiered*` / `DOTNET_TC*` variables set previously were read by nothing. They have been replaced with `MONO_ENV_OPTIONS` tokens each verified against the shipped binary. See [docs/PERFORMANCE.md](docs/PERFORMANCE.md).
- **Thermal frame-rate capping is now opt-in.** It previously clamped the swap interval to 2 (and 3 at critical) as soon as the device hit `THERMAL_STATUS_MODERATE`, silently pinning FPS at refresh/2 — 60 on a 120 Hz panel, 30 on a 60 Hz one — no matter what the game asked for.

### 🐛 Bug Fixes
- **Scroll buttons crashed.** `GLFW.sendScrollEvent` was declared native in Java but never implemented in the GLFW port, and its call site has no catch — so pressing an `SP_MOUSE_SCROLL_UP`/`DOWN` button raised an uncaught `UnsatisfiedLinkError`. Implemented end to end.
- **`ThermalManager.setSwapInterval` was unimplemented** in the GLFW port too, so thermal throttling silently no-op'd.
- **Extraction could silently abort.** A null progress callback threw an NPE that unwound through `runCatching()`, stopping the install with no error shown.
- **`dnbootstrap.c`** continued into `dlopen()` on an undefined buffer when the hostfxr lookup failed, and leaked JNI strings.
- **Tar extraction** now rejects entries that resolve outside the install directory.

> ⚠️ **Correction:** an earlier revision of this README claimed a measured
> "40–50% FPS gain" from the runtime tuning flags. Those flags were inert — this
> runtime ignores `DOTNET_*` — so that figure was not attributable to them and
> has been withdrawn. The current changes are not yet benchmarked; see
> [docs/PERFORMANCE.md §6](docs/PERFORMANCE.md) for how to measure them.

---

## Installation

### Requirements
- A legitimate purchased [Vintage Story](https://www.vintagestory.at/) account
- An Android device running Android 5.0+ (arm64-v8a)

### Steps

1. Log in to the [Vintage Story account manager](https://account.vintagestory.at/) and download:
   **Vintage Story — Linux x64 Tar.gz, version 1.21.6**

2. Download `dnbootstrap.apk` from the [Releases](../../releases) page and install it.

3. Launch the app. When prompted, select the downloaded `.tar.gz` file from your file manager.

4. The app will extract the game files. This is a **one-time step** — subsequent launches go straight to the game.

5. Log in and play.

---

## Recommended Settings

For the best performance on mobile, apply these settings after first launch:

**Graphics → Presets**
- Select the **Lowest** preset as a starting point.

**Graphics → Show Additional Options**
- Set **Render Resolution** to **50%** (or 0.5×).
  Resolution scaling below 100% has minimal visible impact at typical phone viewing distances but cuts GPU load significantly.

**Graphics**
- Enable **VSync**
- Set **Max FPS** to `30` (or `60` if your device handles it without throttling)

**World**
- Reduce **View Distance** — values around `96`–`128` are a good balance on mobile

### Runtime tuning files

Two optional files in the app's files dir
(`/data/data/git.artdeell.dnbootstrap/files/`, reachable through the in-app file
manager) change runtime behaviour without a rebuild:

| File | Effect |
|---|---|
| `mono-env.txt` | First non-empty, non-`#` line replaces the Mono runtime options. A single blank line disables runtime tuning. |
| `thermal-throttle.txt` | `on` re-enables thermal frame-rate capping (off by default). |

Note that with vsync on, frame rate can only ever be `refresh rate / swap
interval` — a target above your panel's refresh rate is not reachable. See
[docs/PERFORMANCE.md](docs/PERFORMANCE.md) for the full analysis, and
[docs/AOT-PLAN.md](docs/AOT-PLAN.md) for the AOT path.

---

## Credits

| Contributor | Work |
|---|---|
| [artdeell](https://github.com/artdeell) | Original dnbootstrap / VS-Mobile launcher |
| [Temder](https://github.com/Temder) | Joystick support, HUD editor improvements |
| Solo | Scroll wheel buttons, performance optimisations, bug fixes |

---

## License

This project is a fork of open-source work. Modifications in this repository are provided as-is with no warranty.

Original project copyright © artdeell. Fork modifications copyright © 2026 Solo. All rights reserved.

Vintage Story is the property of Tyron Madlener / Anego Studios. This project is not affiliated with or endorsed by the Vintage Story developers.
