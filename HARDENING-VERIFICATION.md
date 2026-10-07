# 0.5.3 hardening verification

## Build gates

Latest completed run: 126 unit tests; earlier device runs passed 12 primary native checks and one revoked-access check;
ktlint, detekt, debug/release lint and signed release assembly passed.

Signed candidate SHA-256: `eed0eec943a502dc2d8a188490a0cdf043aee023e592ba65cd021feebcff9c6a`.
The editor-isolation candidate is installed on the Pixel, preserving production data. The tablet retains an earlier candidate
(`c7148bf39e4a0fc82d0b06a7fbeab703f16735963dad59ee951dc455f525e3a0`).
Both candidates use version code 5003 and version name 0.5.3.

On 7 October, the editor was separated from the wallpaper/thumbnail allocation budget after manual
testing exposed crashing, transient rescaling and excessive downsampling. Its previous 2048-pixel source
and 1400-pixel preview limits are restored, with an independent renderer and no resolution-halving fallback,
forced collection or budget-message overlay. Encrypted input retains its 64 MiB bound.
The signed release build and Pixel installation succeeded. At the user's request, automated testing was
not repeated for this change; the automated results below describe preceding candidates.
Manual checks requested: drag/zoom/rotate Home and Lock, repeat with a hidden image, and compare the saved wallpaper.

The follow-up changes use a 15-minute scan default when no preference is saved, retain saved scan choices,
check cancellation before rotation and between preparation stages, and remove only the outer canvas clear.
The per-layer black fill remains inside crossfade compositing. All 122 unit tests, ktlint, detekt,
debug/release lint, signing, permission and release-diagnostics checks passed again.
The frame-pacing follow-up anchors deadlines to display timestamps, wakes before the target vsync,
and checks deadlines in the callback. It removes upward millisecond rounding and render-time drift.
Four new regression tests cover consecutive 60 FPS frames, 30 FPS on 60 Hz, 60 FPS on 120 Hz,
and adaptive slide deadlines. The complete 126-test suite and all build gates passed in a fresh,
single-worker Gradle run after an internal Kotlin FIR crash in lint's parallel test analysis.
On-device animation smoothness and visual Fit/background crossfade checks remain for release preparation.

Run from the project root, with Gradle's user home on D:

```powershell
.\gradlew.bat -g D:\gradle-home testDebugUnitTest ktlintCheck detekt lintDebug lintRelease assembleRelease
```

The implementation adds regression coverage for decode priority and cancellation, memory arithmetic,
blur aliasing, configuration validation and protected replacements, import recovery, playback commit recovery,
manual queue overflow, missed intervals, and combined unlock/timer events. Encryption tests cover v1 reading,
v2 header/body/terminal authentication, truncated/appended/reordered data, wrong keys/hashes, cancellation,
key cleanup, and streaming maintenance above the display-buffer limit.

## Isolated device checks

Production data must not be cleared or uninstalled. The instrumentation runner refuses to operate against
any package except `dev.backgrounded.hardeningprobe`. It creates disposable private images and uses a fixed
test PIN. Its public restoration files are confined to `Download/backgrounded-hardening-probe`.

```powershell
.\gradlew.bat -g D:\gradle-home '-PqaApplicationId=dev.backgrounded.hardeningprobe' assembleDebug assembleDebugAndroidTest
```

Install the debug and Android-test APKs using an explicit connected-device serial, then run:

```text
adb -s SERIAL shell am instrument -w dev.backgrounded.hardeningprobe.test/dev.backgrounded.HardeningInstrumentation
adb -s SERIAL shell appops set dev.backgrounded.hardeningprobe MANAGE_EXTERNAL_STORAGE ignore
adb -s SERIAL shell am instrument -w -e mode revoked dev.backgrounded.hardeningprobe.test/dev.backgrounded.HardeningInstrumentation
```

The main invocation tests encryption before publication, verified encryption, cleanup after publication,
verified decryption recovery, incomplete-copy cleanup, PIN locking/wrong PIN, corrupted-key failure caching,
failed-folder backoff, restore at copying/verified/published stages, and conflicting originals.
The second invocation tests revoked
access. Revocation is performed between invocations because changing that permission terminated the test
process on the Pixel; private copies and restoration metadata survived that termination.

The test interface, instrumentation classes, diagnostics receiver, and event/timing logs are excluded from
the release implementation.

## Observations on 6 October 2026

| Check | Pixel 10 Pro Fold | MovinkPad 14 |
| --- | --- | --- |
| Room 12 → 13 cold start with disposable albums/pairs/framing | Passed | Passed |
| Signed update over existing 0.5.2, preserving production data | Passed | Passed |
| Static wallpaper settles without continuing frames or decodes | Passed | Passed |
| Covered wallpaper defers preparation | Passed | Passed |
| Unchanged visibility return reuses its layer | Passed | Passed |
| Encrypted PNG rendering after authentication | Passed | Passed |
| PIN-only restart remains locked, without encrypted decoding | — | Passed |
| Linked-folder discovery, persisted duplicate identity, revoked folder grant | — | Passed |
| Native journal/key failure checks, including revoked access | Passed (13 checks) | Passed (13 checks) |

On the tablet, a slow slide produced approximately 15 draws/second, a fast slide approximately 20 at the
30 FPS ceiling, and approximately 30 at the 60 FPS ceiling. No additional image decodes occurred during
these samples. After visibility settled to hidden, a 20-second sample had zero new frames, draws, or decodes.

Peak budget charges in the disposable workloads were below 48 MiB on the Pixel and below 38 MiB on the
tablet. These charges include tracked bitmaps and temporary decode reservations; they are not total
process memory or a battery-life estimate. Native meminfo and counter samples are retained under
`app/build/verification`.

The Pixel's pending-alarm section contained no Backgrounded registrations after the update. Historical
old-version wakeup statistics remained in `dumpsys alarm`; cancellation history recorded both legacy paths.
No device rotation settings were changed. Both devices' Home wallpaper uses the normal Backgrounded service.
The tablet's final inspection reports its Lock wallpaper as the system image wallpaper; it was left untouched.

## Release checks and remaining measurement

The release certificate must match the existing release, with version code 5003 and version name 0.5.3.
Inspect the signed APK's manifest and dex files. Existing biometric/all-files permissions are retained;
no new platform permission or `INTERNET` permission is permitted. Debug log paths, snapshot actions,
diagnostics/test receivers, and the instrumentation interface must be absent from release dex files.

The observations above verify idle work suppression, frame ceilings, migration, and recoverable files.
Matched CPU/power profiling of static, slide, parallax, blur/crossfade, linked-folder, and encrypted workloads
on both devices would strengthen a quantified battery-saving claim. The agreed scope is now full Pixel
profiling with shorter tablet regression checks; profiling no longer blocks release preparation.
The devices' existing
brightness and panel conditions differed, so the samples are not a controlled power comparison.

No 0.5.3 GitHub release has been published by this hardening run.
