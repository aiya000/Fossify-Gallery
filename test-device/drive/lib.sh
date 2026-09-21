#!/usr/bin/env bash
# The bits every driving script needs: a device it is allowed to touch, a way to press things, and
# a way to say what it expected.
#
# The safety rail at the top is the point of the whole directory. These scripts call pm clear and
# they tap wherever they like, so a script that ran against the phone the maintainer is holding
# would wipe the app's settings and fight them for the screen. Every entry point asks
# require_emulator first.

set -euo pipefail

DRIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_DEVICE_DIR="$(dirname "$DRIVE_DIR")"
# shellcheck source=../fixture/manifest.env
source "$TEST_DEVICE_DIR/fixture/manifest.env"

# where screenshots and dumps of a run are kept, so a failure can be looked at afterwards
RUN_DIR="${RUN_DIR:-$TEST_DEVICE_DIR/runs/$(date +%Y-%m-%d-%H%M%S)}"
mkdir -p "$RUN_DIR"

ADB=(adb)
if [ -n "${ANDROID_SERIAL:-}" ]; then
    ADB=(adb -s "$ANDROID_SERIAL")
fi

failures=0

step() { echo; echo "== $*"; }
note() { echo "   $*"; }

pass() { echo "   ok: $*"; }

fail() {
    echo "   FAILED: $*" >&2
    failures=$((failures + 1))
}

finish() {
    echo
    if [ "$failures" -gt 0 ]; then
        echo "$failures check(s) failed; screenshots and dumps are in $RUN_DIR"
        exit 1
    fi
    echo "all checks passed; screenshots and dumps are in $RUN_DIR"
}

# An emulator, or a device the caller has said out loud is safe to wipe. Nothing below this line
# is safe to point at a phone somebody is using
require_emulator() {
    local serial
    serial="$("${ADB[@]}" get-serialno)"
    if [ "${FIXTURE_ALLOW_REAL_DEVICE:-0}" = "1" ]; then
        note "running against $serial because FIXTURE_ALLOW_REAL_DEVICE=1"
        return 0
    fi

    local is_emulator
    is_emulator="$("${ADB[@]}" shell getprop ro.build.characteristics | tr -d '\r')"
    case "$serial" in
        emulator-*) return 0 ;;
    esac
    case "$is_emulator" in
        *emulator*) return 0 ;;
    esac

    echo "refusing to drive $serial: it is not an emulator." >&2
    echo "These scripts wipe the app's data and take over the screen. Start an emulator, or set" >&2
    echo "FIXTURE_ALLOW_REAL_DEVICE=1 if this really is a device nobody is using." >&2
    exit 1
}

app_stop() { "${ADB[@]}" shell am force-stop "$FIXTURE_PACKAGE"; }

app_start() {
    "${ADB[@]}" shell monkey -p "$FIXTURE_PACKAGE" -c android.intent.category.LAUNCHER 1 > /dev/null
}

# The app's own log, from this moment on.
#
# Everything is taken by the app's pid rather than by a list of tags: the tags move (the scan
# service logs as RemoteScan, the walk as SmbScan, the fetching of videos as SmbVideo) and a
# missing tag would read as "the app never said it", which is how a passing test hides a failure.
logcat_reset() { "${ADB[@]}" logcat -c; }

app_pid() { "${ADB[@]}" shell pidof "$FIXTURE_PACKAGE" | tr -d '\r' | awk '{print $1}'; }

app_log() {
    local pid
    pid="$(app_pid)"
    if [ -n "$pid" ]; then
        "${ADB[@]}" logcat -d --pid="$pid" | tr -d '\r'
    else
        # the app is gone -- after a crash, say -- so fall back to what it logged under its tags
        "${ADB[@]}" logcat -d -s RemoteScan:* SmbScan:* SmbVideo:* PCloudTransfer:* AndroidRuntime:E | tr -d '\r'
    fi
}

logcat_dump() {
    app_log > "$RUN_DIR/$1.log"
    echo "$RUN_DIR/$1.log"
}

# waits until the log says something, or gives up. A scan of a share takes minutes, which is why
# nothing here is written as a fixed sleep
wait_for_log() {
    local pattern="$1" seconds="${2:-180}" label="${3:-wait}"
    local waited=0
    while [ "$waited" -lt "$seconds" ]; do
        if app_log | rg -q -- "$pattern"; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done

    logcat_dump "$label" > /dev/null
    return 1
}

expect_log() {
    local pattern="$1" label="$2"
    if app_log | rg -q -- "$pattern"; then
        pass "$label"
    else
        fail "$label (nothing in the log matched: $pattern)"
    fi
}

refute_log() {
    local pattern="$1" label="$2"
    if app_log | rg -q -- "$pattern"; then
        fail "$label (the log matched what it must not: $pattern)"
    else
        pass "$label"
    fi
}

screenshot() {
    local name="$1"
    "${ADB[@]}" exec-out screencap -p > "$RUN_DIR/$name.png"
}

# the view tree as the device sees it, saved beside the screenshots
ui_dump() {
    local name="${1:-dump}"
    "${ADB[@]}" shell uiautomator dump /sdcard/window_dump.xml > /dev/null
    "${ADB[@]}" pull /sdcard/window_dump.xml "$RUN_DIR/$name.xml" > /dev/null
    echo "$RUN_DIR/$name.xml"
}

# Presses whatever shows this text. Finding it by text rather than by coordinates is what keeps
# these scripts alive across a layout change
ui_tap_text() {
    local text="$1" name="${2:-tap}"
    local dump point
    dump="$(ui_dump "$name")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text")"; then
        fail "nothing on screen says '$text' (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
}

ui_long_press_text() {
    local text="$1" name="${2:-longpress}"
    local dump point
    dump="$(ui_dump "$name")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text")"; then
        fail "nothing on screen says '$text' (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input swipe $point $point 800
}

ui_wait_text() {
    local text="$1" seconds="${2:-60}" name="${3:-wait}"
    local waited=0 dump
    while [ "$waited" -lt "$seconds" ]; do
        dump="$(ui_dump "$name")"
        if python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" > /dev/null; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# Whether a service of the app is up. This is how "the scan is running now" is known: the scan
# holds a foreground service for as long as it walks, and waiting for it beats sleeping for a
# guessed number of seconds
service_running() {
    "${ADB[@]}" shell dumpsys activity services "$FIXTURE_PACKAGE" | tr -d '\r' | rg -q -- "$1"
}

wait_for_service() {
    local name="$1" seconds="${2:-60}"
    local waited=0
    while [ "$waited" -lt "$seconds" ]; do
        if service_running "$name"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# the screen, so a swipe does not have to guess where the edges are
screen_width() { "${ADB[@]}" shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical size/ {print $3}'; }
screen_height() { "${ADB[@]}" shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical size/ {print $4}'; }

# The sideways drag between storages: rule 1 of #59, and the gesture easiest to make by accident.
# It has to be past a fifth of the width to count, so it goes most of the way across
swipe_storage_forward() {
    local w h
    w="$(screen_width)"
    h="$(screen_height)"
    "${ADB[@]}" shell input swipe $((w * 85 / 100)) $((h / 2)) $((w * 15 / 100)) $((h / 2)) 200
}

swipe_storage_back() {
    local w h
    w="$(screen_width)"
    h="$(screen_height)"
    "${ADB[@]}" shell input swipe $((w * 15 / 100)) $((h / 2)) $((w * 85 / 100)) $((h / 2)) 200
}

# The three dots of the toolbar. It is a content description rather than a text, and the keyevent
# below is the fallback for a screen whose menu is not in a toolbar
open_overflow_menu() {
    local dump point
    dump="$(ui_dump "overflow")"
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "More options")"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        return 0
    fi

    "${ADB[@]}" shell input keyevent KEYCODE_MENU
}
