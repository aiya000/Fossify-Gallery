---
name: debug-build
description: Build the foss debug APK of this Gallery app with gradle. Use when the user asks for a debug build, or before installing a debug build with the `debug-install` skill.
---

# debug-build

Build the `foss` flavor debug APK.

## Environment

- gradle needs a JDK. There is no `java` on `PATH`, use the Android Studio JBR:
    - `JAVA_HOME=~/bin/android-studio/jbr`
- gradle writes to `~/.gradle` (wrapper, caches) and may download SDK components, which the Bash sandbox forbids.
  Run the gradle command with `dangerouslyDisableSandbox: true`
- Outside the sandbox `$TMPDIR` is empty. Always give log files an **absolute** path inside the session scratchpad directory

## Behavior

1. Run the build in the background, logging to the scratchpad:

    ```bash
    export JAVA_HOME=$HOME/bin/android-studio/jbr
    ./gradlew :app:assembleFossDebug -q > <scratchpad>/debug-build.log 2>&1; echo "EXIT=$?" >> <scratchpad>/debug-build.log
    ```

    - A full build with a cold cache takes several minutes; an incremental one is much faster
    - Use `:app:compileFossDebugKotlin` instead when only a compile check is needed

2. When it finishes, check the log for `^e: `, `error:`, `FAILED` and the `EXIT=` line
3. Report the APK path. The version code comes from `VERSION_CODE` in `gradle.properties`:

    ```
    app/build/outputs/apk/foss/debug/gallery-<VERSION_CODE>-foss-debug.apk
    ```

## Notes

- The debug build uses the application id `io.github.aiya000.fossify.gallery.debug`, so it coexists with a release build or the official app
- **Putting the debug build on the user's phone needs asking**, since 2026-09-22: it is their emergency spare for
  when the release build is broken, so it is not replaced from under them. What they try day to day is the release
  build, and that one installs freely -- so a change ready to be tried goes through `release-build` and
  `release-install`, not through here. See `debug-install`
    - onto the **emulator** it goes freely; that is what `test-device/` drives all day
- If the user's machine was restarted or the session was resumed, a background build may have been killed silently:
  check the log and the APK timestamp before trusting an earlier build
