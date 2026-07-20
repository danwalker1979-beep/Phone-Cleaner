# Clean AF — native Android app

A native Android version of Clean AF that can do what a web page can't: scan the
whole phone for duplicate files, list every installed app with its real size,
and host a live Claude chat.

## Screens

- **Analyze** — one tap scans all photos, videos and audio on the device
  (via `MediaStore`), fingerprints them (size + head/tail SHA-256), groups the
  duplicates, and deletes the extras through the system's own confirm dialog.
- **Apps** — lists installed apps by size (`StorageStatsManager`, after you
  grant Usage Access), shows a bundled description / subscription flag / free
  alternative for known apps, and uninstalls through the OS dialog.
- **Assistant** — live Claude chat. Paste an Anthropic API key (stored only on
  the device); messages go straight from the phone to Anthropic on your account.

## Get the app without Android Studio

Every push to `claude/clean-af-android` runs the **Android build** GitHub Action,
which compiles a debug APK and uploads it as a build artifact
(`clean-af-debug-apk`). Download it from the workflow run, transfer it to your
phone, and install it (you'll need to allow "install unknown apps" for your
browser/file manager). Debug builds are for personal/sideload use.

## Build locally

Requires the Android SDK (via Android Studio) and JDK 17:

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Notes / honest limits

- Whole-filesystem document scanning beyond `MediaStore` (arbitrary PDFs/Word in
  any folder) needs the Storage Access Framework; this first version covers the
  media library. Coming next.
- Reading another app's size needs the one-time **Usage Access** toggle; the app
  prompts for it and still works (without sizes) if you decline.
- Uninstalling always goes through Android's own confirmation — the app never
  removes anything silently.
