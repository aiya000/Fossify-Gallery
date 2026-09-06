---
name: release-build
description: Build a signed foss release APK of this Gallery app for personal use (gradle release build, zipalign, sign with the Android debug keystore). Use when the user asks for a production or release build, or before the `release-install` skill.
---

# release-build

Build the `foss` flavor release APK and sign it so it can be installed on the user's own device.

## Environment

- gradle and `apksigner` both need a JDK. There is no `java` on `PATH`; `JAVA_HOME` alone is not enough for
  `apksigner`, it also needs `java` on `PATH`:

    ```bash
    export JAVA_HOME=$HOME/bin/android-studio/jbr
    export PATH="$JAVA_HOME/bin:$PATH"
    ```

- Build tools live in `~/Android/Sdk/build-tools/<version>/` (`zipalign`, `apksigner`). Use the newest installed version
- gradle writes to `~/.gradle`, which the Bash sandbox forbids. Run with `dangerouslyDisableSandbox: true`
- Outside the sandbox `$TMPDIR` is empty. Give log files an **absolute** path inside the session scratchpad directory

## Signing

- Without `keystore.properties` in the project root, gradle logs
  `Warning: No signing config found. Build will be unsigned.` and produces an **unsigned** APK.
  The user chose to sign with the Android debug keystore instead of creating a personal key:
    - keystore: `~/.android/debug.keystore`
    - alias: `androiddebugkey`, store and key password: `android`
- If `keystore.properties` exists (see `keystore.properties_sample`), gradle signs the APK itself and the
  zipalign / apksigner steps must be skipped; the output is then `gallery-<VERSION_CODE>-foss-release.apk`
- Future updates must be signed with the **same** key, otherwise `adb install -r` fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

## Behavior

Run everything in the background with a scratchpad log (R8 minification makes this slower than a debug build):

```bash
export JAVA_HOME=$HOME/bin/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
OUT=app/build/outputs/apk/foss/release
BT=$HOME/Android/Sdk/build-tools/36.0.0
./gradlew :app:assembleFossRelease -q && echo BUILD_OK \
  && "$BT/zipalign" -f -p 4 "$OUT/gallery-<VERSION_CODE>-foss-release-unsigned.apk" "$OUT/gallery-<VERSION_CODE>-foss-release-aligned.apk" && echo ALIGN_OK \
  && "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
       --out "$OUT/gallery-<VERSION_CODE>-foss-release-signed.apk" "$OUT/gallery-<VERSION_CODE>-foss-release-aligned.apk" && echo SIGN_OK
```

- `<VERSION_CODE>` is `VERSION_CODE` in `gradle.properties`
- Afterwards verify the signature and report the path:

    ```bash
    "$BT/apksigner" verify --print-certs "$OUT/gallery-<VERSION_CODE>-foss-release-signed.apk"
    ```

    ```
    app/build/outputs/apk/foss/release/gallery-<VERSION_CODE>-foss-release-signed.apk
    ```

## Notes

- `proguard-rules.pro` keeps `org.fossify.**`, so Gson-serialized models (album covers, folder groups) survive R8
- Do not install automatically; that is the `release-install` skill
