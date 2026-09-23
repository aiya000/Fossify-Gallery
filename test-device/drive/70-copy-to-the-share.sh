#!/usr/bin/env bash
# #28, the other direction: a file of the device can be copied onto the share, and only copied.
#
# 50-copy-off-share.sh drives the share as a source. This drives it as a destination, which is the
# half that had no code at all until now -- SmbClient only ever opened a file for reading, and the
# folder picker turned away every folder on the share with one toast. Both of those had to come
# down, and what is behind them is a write: a folder made if it is not there, a name picked so that
# nothing is written over, bytes streamed out, and a modification time put back on afterwards.
#
# Worth driving rather than reading, for the same reason as the other direction and one more: what
# the app believes it wrote is a row in its own cache, and the only honest witness is the share
# itself. The container serves fixture/share straight off this machine, so `stat` here is the
# share's own answer rather than the app's.
#
# The negative half comes first, and it is the one that would cost a file: a move onto the share
# would have to delete the original off the device once the copy landed, and nothing deletes
# anything yet. So the picker is made to refuse a move before it is asked to accept a copy -- with
# nothing yet on the share, "the share did not gain a file" is an assertion that cannot pass by
# accident.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# the file the fixture already has, sent to the device under a name of its own. Its bytes do not
# matter; what matters is that the same bytes can be found on the share afterwards
seed_file_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_file_on_host" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

device_source="$FIXTURE_DEVICE_SOURCE_DIR/$FIXTURE_DEVICE_SOURCE_FILE"

# "to-the-share.jpg" -> "to-the-share" and "jpg", which is how the app numbers a name already taken
copy_stem="${FIXTURE_DEVICE_SOURCE_FILE%.*}"
copy_extension="${FIXTURE_DEVICE_SOURCE_FILE##*.}"
destination_dir_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_SHARE_DESTINATION_FOLDER"
first_copy_on_host="$destination_dir_on_host/$FIXTURE_DEVICE_SOURCE_FILE"
second_copy_on_host="$destination_dir_on_host/$copy_stem (1).$copy_extension"

# The fixture's counts are what 10-scan-whole-share.sh asserts on, so nothing this script writes
# may outlive it -- not even when it fails halfway. Run on the way in as well, because a run that
# was killed outright never got here
clean_the_share() {
    rm -f "$first_copy_on_host" "$second_copy_on_host"
}

trap clean_the_share EXIT
clean_the_share

step "putting a file on the device for the app to copy"
"${ADB[@]}" shell "rm -rf '$FIXTURE_DEVICE_SOURCE_DIR'"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_DEVICE_SOURCE_DIR'"
"${ADB[@]}" push "$seed_file_on_host" "$device_source" > /dev/null
# A time the share's own clock could not have produced. `adb push` carries the host's mtime across
# on some versions and not on others, so it is set here rather than assumed
"${ADB[@]}" shell "touch -d '$FIXTURE_DEVICE_SOURCE_MODIFIED' '$device_source'"
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$device_source" > /dev/null 2>&1 || true

expected_size="$(stat -c %s "$seed_file_on_host")"
expected_modified="$(("$("${ADB[@]}" shell stat -c %Y "$device_source" | tr -d '\r')"))"
note "$device_source is $expected_size bytes, dated $expected_modified"

step "seeding, and scanning the share so its folders have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "70-menu"
if ! wait_for_log "Walked the share:" 900 "70-scan"; then
    fail "the share was never scanned, so it has no folder to copy into"
    screenshot "70-no-scan"
    finish
fi

step "going to the device and picking $FIXTURE_DEVICE_SOURCE_FILE"
switch_storage_to "This device" "70-storage-local"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_DEVICE_SOURCE_NAME" 60 "70-list"; then
    fail "$FIXTURE_DEVICE_SOURCE_NAME is not in the folder list (MediaStore may not have taken the seed image)"
    screenshot "70-no-source-folder"
    finish
fi

ui_tap_text "$FIXTURE_DEVICE_SOURCE_NAME" "70-open"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "70-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_DEVICE_SOURCE_FILE" 60 "70-grid"; then
    fail "$FIXTURE_DEVICE_SOURCE_FILE is not in the grid"
    screenshot "70-no-file"
    finish
fi

if ! select_row "$FIXTURE_DEVICE_SOURCE_FILE" "70-select"; then
    screenshot "70-not-selected"
    finish
fi

step "what the selection's menu offers for a medium of the device"
open_overflow_menu
sleep 1
menu="$(ui_dump "70-menu-open")"
screenshot "70-menu-open"

for item in "Copy to" "Move to"; do
    if python3 "$DRIVE_DIR/ui.py" "$menu" --text "$item" --exact > /dev/null; then
        pass "'$item' is offered"
    else
        fail "'$item' is not offered for a medium of the device (view tree in $menu)"
        finish
    fi
done

# What the move does with the same destination is 95-move-on-the-share.sh's; this script stops at
# the entry being offered. It used to drive a move here and assert that the picker refused it,
# which is no longer the app's answer -- the move runs, and it deletes the file this script is
# about to copy
step "copying it onto the share"
ui_tap_text "Copy to" "70-tap-copy"
sleep 2

if ! ui_wait_exact_text "Network share" 30 "70-picker-copy"; then
    fail "the folder picker has no network share chip"
    screenshot "70-no-share-chip-copy"
    finish
fi

ui_tap_text "Network share" "70-pick-share"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_SHARE_DESTINATION_FOLDER" 30 "70-picker-copy-share"; then
    fail "$FIXTURE_SHARE_DESTINATION_FOLDER is not in the picker"
    screenshot "70-no-destination"
    finish
fi

screenshot "70-picker-share"
logcat_reset
ui_tap_text "$FIXTURE_SHARE_DESTINATION_FOLDER" "70-copy-destination"

# said once per job, after the destination folder has been walked again, so the line means "it is
# on the share and the list would show it". Everything else the service logs is a failure
if ! wait_for_log "Copied 1 of 1 onto the share" 300 "70-copy"; then
    fail "the copy onto the share never finished"
    screenshot "70-no-copy"
    logcat_dump "70-copy" > /dev/null
    finish
fi

capture_log "70-copy"
refute_log "0 of 1 onto the share" "the copy did not report a failure"

step "what arrived on the share"
if [ -f "$first_copy_on_host" ]; then
    pass "$FIXTURE_SHARE_DESTINATION_FOLDER/$FIXTURE_DEVICE_SOURCE_FILE is there"
else
    fail "nothing was written; the folder holds:"
    ls -l "$destination_dir_on_host" | sed 's/^/     /'
    finish
fi

if cmp -s "$seed_file_on_host" "$first_copy_on_host"; then
    pass "and it is the file that was on the device, byte for byte"
else
    fail "what arrived is not the file that was sent ($(stat -c %s "$first_copy_on_host") bytes against $expected_size)"
fi

# The gallery sorts by the modification time, and the share stamps a file it has just been handed
# with its own clock. A copy that kept that stamp would sort to the top of the folder instead of
# where the original belongs
actual_modified="$(stat -c %Y "$first_copy_on_host")"
drift=$((actual_modified - expected_modified))
if [ "${drift#-}" -le 2 ]; then
    pass "and it kept the modification time it had on the device"
else
    fail "the copy is dated $actual_modified, the file on the device is dated $expected_modified"
fi

# What availableName() decides, seen through the whole path rather than in a unit test: the same
# file copied twice does not overwrite the first copy
step "copying the same file a second time"
if ! ui_wait_text "$FIXTURE_DEVICE_SOURCE_FILE" 60 "70-grid-again"; then
    fail "the grid did not come back after the copy"
    screenshot "70-no-grid-again"
    finish
fi

if ! in_selection_mode "70-still-selected-again"; then
    if ! select_row "$FIXTURE_DEVICE_SOURCE_FILE" "70-select-third"; then
        screenshot "70-not-selected-third"
        finish
    fi
fi

open_overflow_menu
sleep 1
ui_tap_text "Copy to" "70-tap-copy-again"
sleep 2
# This time through "Other folder", the app's own folder picker: the share's chip, then the
# folder, then OK. A destination picked this way is the one the picker remembers as the last
# copy's, which the step after this one is about; a row tapped in the list above is not
ui_tap_text "Other folder" "70-other-folder-again" || finish
sleep 3
ui_tap_exact_text "Network share" "70-picker-share-chip" || finish
sleep 3
if ! ui_wait_exact_text "$FIXTURE_SHARE_DESTINATION_FOLDER" 30 "70-picker-share-root"; then
    fail "$FIXTURE_SHARE_DESTINATION_FOLDER is not in the folder picker's listing of the share"
    screenshot "70-no-destination-in-picker"
    finish
fi

ui_tap_exact_text "$FIXTURE_SHARE_DESTINATION_FOLDER" "70-picker-destination"
sleep 2
# the first copy's line is still in the buffer, and waiting for a line that is already there is no
# wait at all -- it would read the second copy as finished before it had started
logcat_reset
ui_tap_exact_text "OK" "70-picker-ok"

if ! wait_for_log "Copied 1 of 1 onto the share" 300 "70-copy-again"; then
    fail "the second copy never finished"
    screenshot "70-no-second-copy"
    finish
fi

if [ -f "$second_copy_on_host" ]; then
    pass "the second copy was numbered rather than written over the first"
else
    fail "$second_copy_on_host is not there; the second copy may have overwritten the first"
    ls -l "$destination_dir_on_host" | sed 's/^/     /'
fi

if [ "$(stat -c %s "$first_copy_on_host")" = "$expected_size" ]; then
    pass "and the first copy is still whole"
else
    fail "the first copy is no longer $expected_size bytes"
fi

step "and the device still has what it had"
# the point of copying rather than moving
if "${ADB[@]}" shell "[ -f '$device_source' ] && echo yes" 2> /dev/null | tr -d '\r' | rg -q yes; then
    pass "$device_source is untouched"
else
    fail "the file on the device is gone; a copy may not delete its source"
fi

# The last destination is gone back to (#107): "Other folder" opens the picker where the last
# copy went, which for a folder of the share it used to do only for one of pCloud. The picker
# names the folder in its breadcrumbs, and a picker opened on this device's default has no
# "Screens" anywhere in it
step "the next Copy to opens its picker where the last copy went"
if ! ui_wait_text "$FIXTURE_DEVICE_SOURCE_FILE" 60 "70-grid-third"; then
    fail "the grid did not come back after the second copy"
    screenshot "70-no-grid-third"
    finish
fi

if ! in_selection_mode "70-still-selected-third"; then
    if ! select_row "$FIXTURE_DEVICE_SOURCE_FILE" "70-select-fourth"; then
        screenshot "70-not-selected-fourth"
        finish
    fi
fi

open_overflow_menu
sleep 1
ui_tap_text "Copy to" "70-tap-copy-third"
sleep 2
# the destination dialog opens on the storage the folder list is on, and its "Other folder"
# opens the picker on that storage: at the last destination there when there is one, at the
# storage's root when there is not. So the share's chip first, then the button -- a picker
# opened on the share's root has the share in its breadcrumbs and nothing after it
ui_tap_exact_text "Network share" "70-destination-share-chip" || finish
sleep 2
ui_tap_text "Other folder" "70-other-folder" || finish
sleep 3
picker="$(ui_dump "70-picker-last")"
screenshot "70-picker-last"
if python3 "$DRIVE_DIR/ui.py" "$picker" --text "$FIXTURE_SHARE_DESTINATION_FOLDER" --exact > /dev/null; then
    pass "the picker opened on $FIXTURE_SHARE_DESTINATION_FOLDER of the share, where the last copy went"
else
    fail "the picker did not open on $FIXTURE_SHARE_DESTINATION_FOLDER of the share (view tree in $picker)"
    python3 "$DRIVE_DIR/ui.py" "$picker" --list | sed 's/^/     /'
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1

screenshot "70-done"
finish
