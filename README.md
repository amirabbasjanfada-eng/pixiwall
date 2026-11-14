# PixiWall

Import, preview and run PixiJS animations as Android live wallpapers, fully offline.
Kotlin + Jetpack Compose UI, Android WebView renderer, PixiJS bundled inside the APK.

## Build

1. `bash scripts/fetch-pixi.sh` (once; needs Node/npm and internet at build time). It stores the pinned
   PixiJS file in `app/src/main/assets/engine/`. Commit that file if you want the repo to build without it.
2. `gradle :app:assembleDebug` (Gradle 8.9, JDK 17, Android SDK 35)
   gives `app/build/outputs/apk/debug/app-debug.apk`.

The GitHub Actions workflow (`.github/workflows/build-debug-apk.yml`) does both steps and uploads the APK.
There is no `gradlew`/wrapper jar in this repo; run `gradle wrapper --gradle-version 8.9` once if you want one.

## Tests

- `node --test tests/runtime.test.mjs` – engine runtime: context, FPS cap, pause, resize, destroy, leaks, errors,
  the built-in preset, and a randomized lifecycle storm (never two animations alive, nothing left running).
- `python3 tests/browser_check.py` (optional; `pip install playwright && playwright install chromium`) – the same page in a
  real Chromium with real timers: the offline Content-Security-Policy really blocks other sites (fetch, WebSocket, images,
  scripts), the pixel-ratio override works, the fps cap holds under real `requestAnimationFrame` pacing, pause freezes frames,
  destroy really loses leftover GL contexts, and a 160-burst lifecycle storm leaves nothing running.
- `gradle :app:testDebugUnitTest` – Home/Lock selection logic, prompt, resource governor, rate limiter.
- `gradle :app:connectedDebugAndroidTest` (device or emulator) – `EngineSmokeTest`: PixiJS on WebGL in a WebView;
  `VirtualDisplayPipelineTest`: the wallpaper's exact pipeline (virtual display -> Presentation -> WebView -> PixiJS),
  including detach/re-attach of the output, resize and release.

## How the live wallpaper renders (and why)

WebGL only works in a WebView attached to a hardware-accelerated window, so each wallpaper engine owns one virtual
display whose output is the wallpaper surface, with a Presentation + WebView on it.

- The virtual display is created **once per engine** and reused (`resize()`, `setSurface()`). Creating and destroying
  virtual displays in a loop can hang SurfaceFlinger and the whole phone.
- Framework surface callbacks only record state; one debounced `reconcile()` builds everything.
- Hidden = paused and the display's output detached. Nothing renders while hidden; the WebView is freed after 30 s.
- Process-wide ceilings (virtual displays, pipelines, rebuilds per minute) stop any rebuild loop.
- Resource governor: wallpaper "Max" fps = 60; surfaces above 2.8 M pixels render at reduced scale; the system
  picker's preview runs at <= 30 fps and <= 75 % scale. The in-app preview is never capped.

## Diagnostics

Everything is logged under the tag `PixiWall`:

    adb logcat -s PixiWall
    adb shell run-as com.pixiwall cat files/diagnostics.log     (last 500 lines; survives a freeze or force-close)

Without adb, connect the phone to a PC over USB (file transfer) and copy
`Android/data/com.pixiwall/files/diagnostics.log`. The first line names the device, Android and WebView versions.

A healthy wallpaper start looks like this (preview in the system picker, then the real engines):

    engine#1 created preview=true ...
    engine#1 surfaceChanged 1080x2400 visible=true
    engine#1 virtual display #N created 1080x2400 @420dpi in .. ms
    engine#1 presentation + WebView built in .. ms
    engine#1 WebView attached (hardwareAccelerated=true)
    engine#1 page finished after .. ms (attached=true)
    engine#1 showing 'Engine test' on the home screen
    engine#1 status +1500ms: {"state":"running","renderer":"webgl","frames":NN,...}

What a problem looks like: `MAIN THREAD STALLED ... It is currently in:` (with the stack), `refusing to create another
virtual display`, `could not build the presentation` (with the exception), `WebView renderer died`,
`status ...: {"state":"error","error":"..."}`, `renderer":"canvas"` when WebGL was expected, `blocked a request to ...`.

## Layout

- `assets/engine/` – `index.html` + `runtime.js` (the single engine, used by preview and wallpaper) + `pixi.min.js`.
- `assets/builtin/preset.js` – the one built-in test animation.
- `engine/AnimationHost.kt` – runs the engine in a WebView. `wallpaper/AnimationWallpaperService.kt` – live wallpaper.
- `Diag.kt`, `Watchdog.kt` – logging and main-thread stall detection.
- `core/` – pure Kotlin: animation library, independent Home/Lock selections, settings, prompt text, governor.
