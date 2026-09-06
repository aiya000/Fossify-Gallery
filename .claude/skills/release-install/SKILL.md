---
name: release-install
description: Install the signed foss release APK of this Gallery app on the connected device with adb. Use when the user asks to install or deploy the production / release build; build it first with the `release-build` skill if needed.
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

- The release build uses the application id `org.fossify.gallery`, the same as the official Fossify Gallery.
  If the official app (F-Droid, etc.) is installed, the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  because the signatures differ; the user has to uninstall the official app first (its settings are lost)
- Updating an earlier personal release build works only when it was signed with the same key
  (the Android debug keystore, see `release-build`)
- The debug build (`org.fossify.gallery.debug`) is a separate app and is not affected
