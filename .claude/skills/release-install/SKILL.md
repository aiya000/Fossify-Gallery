---
name: release-install
description: Install the signed foss release APK of this Gallery app on the connected device with adb. Use whenever a release build should reach the device -- the user asks for it, or a change is ready for them to try -- without asking permission first; build it first with the `release-build` skill if needed.
---

# release-install

Install the signed `foss` release APK on the device connected via adb.

## Environment

- The device is usually connected with **wireless adb**. The address (`<ip>:<port>`) changes between sessions and is
  not stored in the repository
- Inside the Bash sandbox adb cannot reach the device. Run adb commands with `dangerouslyDisableSandbox: true`

## Behavior

1. Make sure the signed APK exists and is fresh (see the `release-build` skill):

    ```
    app/build/outputs/apk/foss/release/gallery-<VERSION_CODE>-foss-release-signed.apk
    ```

    If it is missing or older than the latest source change, run the `release-build` skill first

2. Check the device with `adb devices`; if none is listed, `adb connect <ip>:<port>` (ask the user for the address
   when it is not known from the conversation)
3. Install:

    ```bash
    adb install -r app/build/outputs/apk/foss/release/gallery-<VERSION_CODE>-foss-release-signed.apk
    ```

4. Report `Success` or the adb error verbatim

## Notes

- **No permission is needed to install it.** The release build is the app the user actually uses, so a change that
  is ready for them belongs on the device without being asked about first: install it and say so afterwards.
  `adb install` draws nothing on the screen, so it cannot interrupt them either
    - the **debug** build is the one that waits to be asked now, and for the opposite reason -- see `debug-install`
- Never install while the user has asked to wait ("インストールは待って") — build only
- The release build uses the application id `io.github.aiya000.fossify.gallery`, which is this fork's own id.
  The official Fossify Gallery (`org.fossify.gallery`) is a separate app and can stay installed next to it
- An app built from this repository before the id change is also a separate app now. It keeps its own settings,
  so anything worth carrying over has to be exported from it and imported into the new one
- Updating an earlier personal release build works only when it was signed with the same key
  (the Android debug keystore, see `release-build`)
- The debug build (`io.github.aiya000.fossify.gallery.debug`) is a separate app and is not affected
