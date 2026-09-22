---
name: debug-install
description: Install the built foss debug APK of this Gallery app on the connected device with adb. Use when the user asks for the debug build on the device -- ask first, it is the spare they fall back on when the release build is broken; build it first with the `debug-build` skill if needed. For the emulator this does not apply, see the skill body.
---

# debug-install

Install the `foss` debug APK on the device connected via adb.

## Environment

- The device is usually connected with **wireless adb**. The address (`<ip>:<port>`) changes between sessions, it is
  not stored anywhere in the repository
- Inside the Bash sandbox adb cannot reach the device (it starts its own daemon and sees no devices).
  Run the adb commands with `dangerouslyDisableSandbox: true`

## Behavior

1. Make sure the APK exists and is fresh (see the `debug-build` skill for its path):

    ```
    app/build/outputs/apk/foss/debug/gallery-<VERSION_CODE>-foss-debug.apk
    ```

    If it is missing or older than the latest source change, run the `debug-build` skill first

2. Check the device:

    ```bash
    adb devices
    ```

    - If no device is listed, run `adb connect <ip>:<port>` when the address is known from the conversation,
      otherwise ask the user to connect wireless adb and tell you the address
3. Install:

    ```bash
    adb install -r app/build/outputs/apk/foss/debug/gallery-<VERSION_CODE>-foss-debug.apk
    ```

4. Report `Success` or the adb error verbatim

## Notes

- The debug build is `io.github.aiya000.fossify.gallery.debug`, a separate app from the release build, with its own settings
  (folder groups, pins, etc. are not shared)
- **Ask before putting it on the user's phone.** Since 2026-09-22 the debug build is their **emergency spare**: the
  one they reach for when the release build turns out to be broken. Replacing it without asking would take that
  spare away at the moment it is most likely to be needed. What they try day to day is the release build, and
  that one installs freely (`release-install`) -- the two swapped places
- **The emulator is not the phone.** Nothing here applies to `ANDROID_SERIAL=emulator-5554`: the driving scripts of
  `test-device/` install the debug build onto it constantly, and that is what it is for
- Never install while the user has asked to wait ("インストールは待って") — build only
- Installing restarts the app, which kills any scan or transfer running in it, and changes its pid. When a `logcat`
  capture is pinned to the pid, or the device is in the middle of something being measured, finish that first
