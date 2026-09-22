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

storage_label_of() {
    case "$1" in
        2) echo "pCloud" ;;
        3) echo "All storages" ;;
        4) echo "Network share" ;;
        *) echo "This device" ;;
    esac
}

# Starts the app and leaves it on the storage the fixture asks for.
#
# The folder list always opens on this device, whatever storage it was left on, so seeding
# storage_filter no longer decides where a script begins: the storage it wants is somewhere it
# has to go, the way the user goes there. The switch is driven through the chip rather than
# written into the preferences, so what a script starts from is a state the app can actually
# reach -- and the arrival is the app's own, with whatever scan the settings attach to it
app_start() {
    "${ADB[@]}" shell monkey -p "$FIXTURE_PACKAGE" -c android.intent.category.LAUNCHER 1 > /dev/null
    if [ "${FIXTURE_STORAGE_FILTER:-1}" = "1" ]; then
        return 0
    fi

    # the chip is drawn with the toolbar, which is not there the instant monkey returns
    sleep 4
    switch_storage_to "$(storage_label_of "$FIXTURE_STORAGE_FILTER")" "start-storage"
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

# Takes the log once and asserts against that copy from then on.
#
# A walk of the share logs steadily, and the device's buffer is not large: a line found by
# wait_for_log was gone again by the time the next question was asked of it, which read as the app
# never having said it. Everything a case wants to know is in one snapshot, and the snapshot is
# what is kept in the run directory
capture_log() {
    LOG_SNAPSHOT="$(logcat_dump "$1")"
    note "log kept at $LOG_SNAPSHOT"
}

captured_log() {
    if [ -n "${LOG_SNAPSHOT:-}" ]; then
        cat "$LOG_SNAPSHOT"
    else
        app_log
    fi
}

expect_log() {
    local pattern="$1" label="$2"
    if captured_log | rg -q -- "$pattern"; then
        pass "$label"
    else
        fail "$label (nothing in the log matched: $pattern)"
    fi
}

refute_log() {
    local pattern="$1" label="$2"
    if captured_log | rg -q -- "$pattern"; then
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

# A long press, held with motionevent rather than made out of a swipe of no distance. The swipe
# form is what the first version used and the app never saw it: `input swipe` with the same point
# twice does not last long enough to be a press at all
ui_long_press_text() {
    local text="$1" name="${2:-longpress}"
    local dump point
    dump="$(ui_dump "$name")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text")"; then
        fail "nothing on screen says '$text' (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input motionevent DOWN $point
    sleep 1
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input motionevent UP $point
    sleep 1
}

# The same, but the text has to be the whole label.
#
# A folder row named Camera is otherwise found in the toolbar's "Open camera" button, which is
# earlier in the tree -- so ui_tap_text "Camera" opens the camera app and leaves the gallery
# behind, and everything after it reads as the gallery having lost its mind. Tap a folder row by
# its whole name
ui_tap_exact_text() {
    local text="$1" name="${2:-tap}"
    local dump point
    dump="$(ui_dump "$name")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact)"; then
        fail "nothing on screen is exactly '$text' (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
}

# Whether the folder list is in its selection mode. The toolbar counts what is picked there --
# "1 / 2003" -- and that count is the only thing on screen that says so
in_selection_mode() {
    local dump
    dump="$(ui_dump "${1:-selection}")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --list | rg -q '^[0-9]+ / [0-9]+\s'
}

# Picks a row, and makes sure it took. A long press that lands while the list is still rebinding
# after a scan is swallowed, and the next tap then opens the ordinary menu instead of the
# selection's -- which reads as a missing menu item rather than as a press that never happened
select_row() {
    local text="$1" name="${2:-select}"
    local attempt
    for attempt in 1 2 3; do
        ui_long_press_text "$text" "$name-$attempt" || return 1
        if in_selection_mode "$name-$attempt-check"; then
            return 0
        fi
        sleep 2
    done

    fail "holding '$text' did not start a selection"
    return 1
}

ui_wait_exact_text() {
    local text="$1" seconds="${2:-60}" name="${3:-wait}"
    local waited=0 dump
    while [ "$waited" -lt "$seconds" ]; do
        dump="$(ui_dump "$name")"
        if python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact > /dev/null; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
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

# Changing storage, through the toolbar's Storage chip.
#
# The gesture a user makes for this is a sideways drag of the folder list, and that is the one
# worth worrying about -- it is the easiest thing in the app to do by accident, which is why #59
# ranked a swipe below a scan asked for by hand. It is not what these scripts make, though:
# `input swipe` synthesises a handful of move events and the list's drag detection does not take
# them, so a swipe here does nothing at all and a test built on one passes by accident.
#
# The chip is the same code path from switchStorage() down -- leftBehind() and the rescan policy
# both live there -- so what the ranks do is still what is being checked.

# Opens the storage menu and leaves it open, which is how a script reads what is on it rather
# than only picking from it
open_storage_menu() {
    local name="${1:-storage}"
    local dump point
    dump="$(ui_dump "$name-chip")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "storage_filter")"; then
        fail "the storage chip is not on screen (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 1
}

switch_storage_to() {
    local label="$1" name="${2:-storage}"
    open_storage_menu "$name" || return 1
    ui_tap_text "$label" "$name-menu"
    sleep 1
}

# Which storage the menu marks as the one on screen, with the menu opened and closed again so a
# script can ask between steps without moving the list. The mark is what says where the list is:
# until something has been scanned, both storages draw the same empty grid
storage_marked_in_menu() {
    local name="${1:-storage-marked}"
    local dump
    open_storage_menu "$name" || return 1
    dump="$(ui_dump "$name-menu")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --checked || echo "nothing"
    "${ADB[@]}" shell input keyevent KEYCODE_BACK > /dev/null
    sleep 1
}

# Puts a name into the field of a dialog that opened with one already in it.
#
# The rename dialog is handed the old name and selects part of it, so typing alone would land
# beside what is there rather than replace it -- and what part is selected is the dialog's
# business, not something a script should be reading. So the field is emptied key by key first,
# from its end, and only then typed into.
#
# `input text` cannot type a space; every name these scripts rename to is one word for that reason
replace_text_field() {
    local text="$1" old_length="${2:-64}"
    local keys="" i
    "${ADB[@]}" shell input keyevent KEYCODE_MOVE_END
    for ((i = 0; i < old_length; i++)); do
        keys="$keys KEYCODE_DEL"
    done

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input keyevent $keys
    "${ADB[@]}" shell input text "$text"
}

open_overflow_menu() {
    local dump point
    dump="$(ui_dump "overflow")"
    # the last of them: while a selection is on, its toolbar is drawn over the ordinary one and
    # both are in the tree. The three dots that open the selection's menu are the later pair
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "More options" --last)"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        return 0
    fi

    "${ADB[@]}" shell input keyevent KEYCODE_MENU
}

# Presses a menu item wherever it happens to be: on the toolbar, or in its overflow.
#
# `showAsAction="ifRoom"` means exactly that -- an icon on the toolbar when there is room for
# one, an entry in the overflow when there is not -- and which of the two it gets depends on the
# screen, the locale and whatever else is on that toolbar. uiautomator hangs the title on the
# icon as a content description, so both forms are reachable; a script that assumes one of them
# simply cannot see the other, and the failure then reads as "the app does not offer this" when
# the app offers it perfectly well. That has cost a lap twice now, see
# agents/tests/the-delete-icon-is-not-in-the-overflow.md
tap_action() {
    local text="$1" name="${2:-action}"
    local dump point
    dump="$(ui_dump "$name-toolbar")"
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact)"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        return 0
    fi

    open_overflow_menu
    sleep 1
    dump="$(ui_dump "$name-overflow")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact)"; then
        fail "nothing offers '$text', on the toolbar or in its overflow (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
}
