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

### ⚡ Performance Optimisations
Several changes to reduce CPU heat and improve frame pacing:

- **Fixed bitmask bug** in the native GLFW layer (`>>` → `<<`) that caused input flags to be silently ignored
- **Removed spurious pipe write** that fired on every event loop cycle with no effect
- **Coalesced cursor redraws** — rapid touch-move events no longer flood the UI thread with individual invalidate calls
- **20 .NET runtime tuning flags** added at startup covering GC mode, heap limits, tiered JIT, ARM64 SIMD, thread pool sizing, and diagnostics suppression
- **Workstation GC mode enforced** — server GC spins dedicated background threads continuously and is unsuitable for mobile thermal budgets

Observed improvement on mid-to-high-end devices: roughly **40–50% FPS gain** and noticeably lower sustained temperatures.

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
