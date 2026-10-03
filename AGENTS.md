# Backgrounded — agent notes

FOSS live-wallpaper album rotator for GrapheneOS (Pixel 10 Pro Fold, Android 16 / API 36).
Architecture plan: `C:\Users\nestl\.local\share\kilo\plans\1791022805929-backgrounded-architecture.md`.

## Toolchain locations (this machine)

- JDK 21 (Temurin) on PATH; Android SDK at `D:\android-sdk` (`local.properties` points there).
- Gradle user home **must stay on D:** — always pass `-g D:\gradle-home`.
- adb: `cmd /c "D:\android-sdk\platform-tools\adb.exe …"` (not on PATH). An offline emulator
  transport may be present, so list devices and pass `-s <serial>` explicitly. Device: Pixel 10
  Pro Fold (`rango`).
- No emulator/AVD: build, install and instrumented tests run against the connected device.

## Commands

```
gradlew.bat -g D:\gradle-home assembleDebug
gradlew.bat -g D:\gradle-home installDebug
gradlew.bat -g D:\gradle-home testDebugUnitTest
gradlew.bat -g D:\gradle-home ktlintCheck detekt
gradlew.bat -g D:\gradle-home lintDebug
```

## Conventions

- Kotlin official style, 4-space indent, max line 120; ktlint + detekt must pass.
- Immutable UI state, `StateFlow` UDF, structured concurrency, no `GlobalScope`.
- Room schema changes: bump the DB version, add a `Migration`, and cold-start the installed app
  against the previous version; watch `adb logcat -b crash` — Room validates the schema strictly
  and an index/table mismatch crashes on open.
- Zero platform `uses-permission` in the merged manifest: `apkanalyzer manifest permissions` on
  the debug APK must list only the app-defined signature permission
  `dev.backgrounded.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (added by androidx.core for
  `RECEIVER_NOT_EXPORTED` registration). Any other entry, especially `INTERNET` or storage, is a
  regression.
- UI copy: functional only — no slogans, self-descriptions or conversational text.
- Commits: Conventional Commits, small steps; at most two branches (`main` + one topic branch).
