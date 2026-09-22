#!/usr/bin/env bash
# #28: a medium of the share can be copied onto the device, and nothing on the share is touched.
#
# Worth driving rather than reading. The copy crosses everything this app keeps apart: a pseudo
# path with no file behind it, a menu that hides itself for remote media, a folder picker that
# turns away a destination on the share, a storage permission, and a foreground service that
# outlives the screen that asked for it. A unit test can say what `availableName()` answers; only
# the device can say whether a file arrives.
#
# The other half of the rule is what must NOT be offered. Copying off the share reads it; moving
# would have to delete from it, and nothing deletes on the share yet. So the absence of "Move to" is
# checked in the same breath as the presence of "Copy to" -- a menu that quietly grows the wrong
# item back is exactly the kind of regression nobody notices until a file is gone.
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

# "image-1.jpg" -> "image-1" and "jpg", which is how the app numbers a name already taken
copy_stem="${FIXTURE_COPY_SOURCE_FILE%.*}"
copy_extension="${FIXTURE_COPY_SOURCE_FILE##*.}"
first_copy="$FIXTURE_LOCAL_DESTINATION_DIR/$FIXTURE_COPY_SOURCE_FILE"
second_copy="$FIXTURE_LOCAL_DESTINATION_DIR/$copy_stem (1).$copy_extension"

device_file_size() {
    "${ADB[@]}" shell stat -c %s "$1" 2> /dev/null | tr -d '\r'
}

device_file_exists() {
    "${ADB[@]}" shell "[ -f '$1' ] && echo yes" 2> /dev/null | tr -d '\r' | rg -q yes
}

step "making a folder on the device for the copy to land in"
# Anything an earlier run copied goes first, or the very first copy of this run would be the one
# that gets numbered and every assertion below would be off by one
"${ADB[@]}" shell "rm -f '$first_copy' '$second_copy'" || true
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
# one image, so that MediaStore has the folder and the app's picker lists it. A folder the app
# does not know about cannot be chosen, and "Other folder" is a different dialog and a different
# test
"${ADB[@]}" push "$source_file_on_host" "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null
for path in "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" "$first_copy" "$second_copy"; do
    # the copies are scanned too, so that MediaStore forgets the ones just deleted
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$path" > /dev/null 2>&1 || true
done
note "the copies will land in $FIXTURE_LOCAL_DESTINATION_DIR"

step "seeding, and scanning the share so its folders have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "50-menu"
if ! wait_for_log "Walked the share:" 900 "50-scan"; then
    fail "the share was never scanned, so there is nothing to copy"
    screenshot "50-no-scan"
    finish
fi

step "opening $FIXTURE_COPY_SOURCE_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 60 "50-list"; then
    fail "$FIXTURE_COPY_SOURCE_FOLDER is not in the folder list"
    screenshot "50-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_COPY_SOURCE_FOLDER" "50-open"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it happens to be drawn
step "turning the filenames on"
dump="$(ui_dump "50-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

step "picking $FIXTURE_COPY_SOURCE_FILE"
if ! ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "50-grid"; then
    fail "$FIXTURE_COPY_SOURCE_FILE is not in the grid"
    screenshot "50-no-file"
    finish
fi

if ! select_row "$FIXTURE_COPY_SOURCE_FILE" "50-select"; then
    screenshot "50-not-selected"
    finish
fi
screenshot "50-selected"

step "what the selection's menu offers for a medium of the share"
open_overflow_menu
sleep 1
menu="$(ui_dump "50-menu-open")"
screenshot "50-menu-open"

if python3 "$DRIVE_DIR/ui.py" "$menu" --text "Copy to" --exact > /dev/null; then
    pass "'Copy to' is offered"
else
    fail "'Copy to' is not offered for a medium of the share (view tree in $menu)"
    finish
fi

# a move would have to delete the original off the share, and nothing deletes on it yet
if python3 "$DRIVE_DIR/ui.py" "$menu" --text "Move to" --exact > /dev/null; then
    fail "'Move to' is offered, and it would have to delete from the share"
else
    pass "'Move to' is kept away, nothing deletes on the share yet"
fi

step "copying it to the device"
ui_tap_text "Copy to" "50-tap-copy"
sleep 2

# The picker opens on the storage the folder list was on, which is the share. Its own chips are
# what narrows it, and they are plain tappable labels rather than a menu
if ! ui_wait_text "This device" 30 "50-picker"; then
    fail "the folder picker has no storage chips; a destination on the device cannot be reached"
    screenshot "50-no-chips"
    finish
fi

ui_tap_text "This device" "50-pick-local"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" 30 "50-picker-local"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not in the picker (MediaStore may not have taken the seed image)"
    screenshot "50-no-destination"
    finish
fi

screenshot "50-picker-local"
ui_tap_text "$FIXTURE_LOCAL_DESTINATION_NAME" "50-pick-destination"

# The service says this once per job, after it has brought the destination up to date, so the
# line means "the file is there and the list would show it". Everything else it logs is a failure
if ! wait_for_log "Copied 1 of 1 off the share" 180 "50-copy"; then
    fail "the copy never finished"
    screenshot "50-no-copy"
    logcat_dump "50-copy" > /dev/null
    finish
fi

capture_log "50-copy"
refute_log "0 of 1 off the share" "the copy did not report a failure"

step "what arrived on the device"
if device_file_exists "$first_copy"; then
    pass "$first_copy is there"
else
    fail "$first_copy was never written"
    "${ADB[@]}" shell "ls -l '$FIXTURE_LOCAL_DESTINATION_DIR'" | sed 's/^/     /'
    finish
fi

expected_size="$(stat -c %s "$source_file_on_host")"
actual_size="$(device_file_size "$first_copy")"
if [ "$actual_size" = "$expected_size" ]; then
    pass "and it is all $expected_size bytes of it"
else
    fail "the copy is $actual_size bytes, the file on the share is $expected_size"
fi

# The gallery sorts by the modification time, so a copy that carried the time the device wrote it
# would sort to the top of the destination folder instead of where the original belongs
expected_modified="$(stat -c %Y "$source_file_on_host")"
actual_modified="$(("$("${ADB[@]}" shell stat -c %Y "$first_copy" | tr -d '\r')"))"
drift=$((actual_modified - expected_modified))
if [ "${drift#-}" -le 2 ]; then
    pass "and it kept the share's modification time"
else
    fail "the copy is dated $actual_modified, the file on the share is dated $expected_modified"
fi

# What availableName() decides, seen through the whole path rather than in a unit test: the same
# file copied twice does not overwrite the first copy
step "copying the same file a second time"
if ! ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "50-grid-again"; then
    fail "the grid did not come back after the copy"
    screenshot "50-no-grid"
    finish
fi

if ! select_row "$FIXTURE_COPY_SOURCE_FILE" "50-select-again"; then
    screenshot "50-not-selected-again"
    finish
fi

open_overflow_menu
sleep 1
ui_tap_text "Copy to" "50-tap-copy-again"
sleep 2
ui_tap_text "This device" "50-pick-local-again"
sleep 2
# the first copy's line is still in the buffer, and waiting for a line that is already there is
# no wait at all -- it would read the second copy as finished before it had started
logcat_reset
ui_tap_text "$FIXTURE_LOCAL_DESTINATION_NAME" "50-pick-destination-again"

if ! wait_for_log "Copied 1 of 1 off the share" 180 "50-copy-again"; then
    fail "the second copy never finished"
    screenshot "50-no-second-copy"
    finish
fi

if device_file_exists "$second_copy"; then
    pass "the second copy was numbered rather than written over the first"
else
    fail "$second_copy is not there; the second copy may have overwritten the first"
    "${ADB[@]}" shell "ls -l '$FIXTURE_LOCAL_DESTINATION_DIR'" | sed 's/^/     /'
fi

if [ "$(device_file_size "$first_copy")" = "$expected_size" ]; then
    pass "and the first copy is still whole"
else
    fail "the first copy is no longer $expected_size bytes"
fi

step "and the share still has what it had"
# the point of copying rather than moving. Read from the host side: the container serves this
# very directory, so what is here is what the share has
if [ -f "$source_file_on_host" ] && [ "$(stat -c %s "$source_file_on_host")" = "$expected_size" ]; then
    pass "$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE is untouched on the share"
else
    fail "the file on the share changed; nothing here may write to it"
fi

screenshot "50-done"
finish
