#!/usr/bin/env bash
# #60: the properties of a medium on the share open, and say where the medium is.
#
# What happened before: the (i) button answered with a toast saying the source file does not
# exist, and no dialog at all. `showProperties()` asked whether the path was a *pCloud* path
# rather than a *remote* one, so a share medium fell through to commons' PropertiesDialog, which
# checks the path against the device filesystem and gives up. The medium has no file on the
# device -- that is the whole idea of a remote storage.
#
# Worth driving rather than reading, because the interesting part is a dialog appearing at all,
# and what it puts in front of the user. A unit test can say which branch is taken; only the
# device can say that a dialog with the right lines in it is on the screen.
#
# Both ways in are driven -- the fullscreen viewer and the grid's selection -- because they are
# two separate `showProperties()`, and the bug was in both.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

source_file_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$source_file_on_host" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

step "seeding, and scanning the share"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "45-menu"
if ! wait_for_log "Walked the share:" 900 "45-scan"; then
    fail "the share was never scanned, so there is no medium to ask about"
    screenshot "45-no-scan"
    finish
fi

step "opening $FIXTURE_COPY_SOURCE_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 60 "45-list"; then
    fail "$FIXTURE_COPY_SOURCE_FOLDER is not in the folder list"
    screenshot "45-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_COPY_SOURCE_FOLDER" "45-open-folder"
sleep 3

dump="$(ui_dump "45-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "45-grid"; then
    fail "$FIXTURE_COPY_SOURCE_FILE is not in the grid"
    screenshot "45-no-file"
    finish
fi

# What the dialog has to show. The name and the size come out of the row the scan wrote, and the
# path is the one place the storage has to be named: the pseudo path the app keeps inside itself
# is "smb:/Screens", which is not a thing to put in front of anybody
expected_size_bytes="$(stat -c %s "$source_file_on_host")"
note "$FIXTURE_COPY_SOURCE_FILE is $expected_size_bytes bytes on the share"

check_properties_dialog() {
    local name="$1"
    local dump
    dump="$(ui_dump "$name")"
    screenshot "$name"

    # By the id of its rows, not by the word "Properties". The viewer's toolbar carries a
    # Properties icon whose content description is that same word, so matching on the text finds
    # the screen *behind* the dialog and passes whether a dialog opened or not -- which is how
    # the first version of this script went green on the build that still had the bug
    if python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "remote_property_value" > /dev/null; then
        pass "the properties dialog is on screen"
    else
        fail "no properties dialog appeared (view tree in $dump)"
        python3 "$DRIVE_DIR/ui.py" "$dump" --list | sed 's/^/     /'
        return 1
    fi

    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "$FIXTURE_COPY_SOURCE_FILE" > /dev/null; then
        pass "and it names the medium"
    else
        fail "the dialog does not name $FIXTURE_COPY_SOURCE_FILE"
    fi

    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "Network share" > /dev/null; then
        pass "and it says which storage the medium is on"
    else
        fail "the dialog does not name the network share"
    fi

    # the pseudo path is the app's own business; seeing it means the path was passed through raw
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "smb:" > /dev/null; then
        fail "the dialog shows the raw pseudo path"
    else
        pass "and it does not show the raw pseudo path"
    fi

    # the toast this issue is about. It is a window of its own, so it is in the tree while it is up
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "does not exist" > /dev/null; then
        fail "the app still says the source file does not exist"
    else
        pass "and nothing says the source file does not exist"
    fi
}

step "the properties of a medium of the share, from the fullscreen viewer"
ui_tap_text "$FIXTURE_COPY_SOURCE_FILE" "45-open-photo"
sleep 4

tap_action "Properties" "45-viewer-properties" || finish
sleep 2
check_properties_dialog "45-viewer-dialog"

ui_tap_text "OK" "45-viewer-dialog-ok"
sleep 2
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3

step "and from the grid's selection"
if ! ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "45-grid-again"; then
    fail "the grid did not come back"
    screenshot "45-no-grid"
    finish
fi

if ! select_row "$FIXTURE_COPY_SOURCE_FILE" "45-select"; then
    screenshot "45-not-selected"
    finish
fi

tap_action "Properties" "45-grid-properties" || finish
sleep 2
check_properties_dialog "45-grid-dialog"

screenshot "45-done"
finish
