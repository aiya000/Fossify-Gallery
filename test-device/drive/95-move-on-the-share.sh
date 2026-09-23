#!/usr/bin/env bash
# #28: a medium can be moved with the share on either side -- into another folder of the share,
# off the share onto this device, and from this device onto the share.
#
# The two are not the same operation wearing different clothes, which is why both are here.
#
# Inside the share nothing is read and nothing is written: the share puts the file in another
# folder itself, with the one request a rename uses, and the app carries its own rows across. So
# what has to be checked is that the rows really did travel -- the destination folder shows the
# medium without anything having walked the share, which is minutes the app does not spend.
#
# Off the share it is a copy followed by a delete, and the order of those two is the whole thing:
# the original may only go once the new one is whole. A build that deleted first, or deleted after
# a copy that failed, loses the file outright -- a file that never reached the destination is not
# in any recycle bin either.
#
# Nothing in the fixture's counts may be moved -- 10-scan-whole-share.sh asserts on them -- so this
# script brings its own two media and takes back whatever is left of them, at either end, on the
# way in and on the way out.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

moved_from="$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_FILE"
moved_to="$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_DESTINATION/$FIXTURE_MOVE_FILE"
away_from="$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_AWAY_FILE"
away_to="$FIXTURE_LOCAL_DESTINATION_DIR/$FIXTURE_MOVE_AWAY_FILE"
onto_from="$FIXTURE_DEVICE_SOURCE_DIR/$FIXTURE_MOVE_ONTO_FILE"
onto_to="$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_ONTO_FILE"

clean_the_share() {
    rm -f "$moved_from" "$moved_to" "$away_from" "$onto_to"
    "${ADB[@]}" shell "rm -f '$away_to' '$onto_from'" > /dev/null 2>&1 || true
}

trap clean_the_share EXIT
clean_the_share

# Back out to the folder list, however deep the screen happens to be.
#
# One KEYCODE_BACK is not always enough: a selection that is still on swallows the first press,
# and whether it is still on depends on what the move did to the list -- which is the very thing
# under test. Pressing until the folder list is there keeps a navigation detail from being
# reported as the assertion that follows it
back_to_the_folder_list() {
    local wanted="$1" name="$2"
    local attempt
    for attempt in 1 2 3; do
        if ui_wait_exact_text "$wanted" 6 "$name-$attempt"; then
            return 0
        fi

        "${ADB[@]}" shell input keyevent KEYCODE_BACK
        sleep 3
    done

    return 1
}

device_file_exists() {
    "${ADB[@]}" shell "[ -f '$1' ] && echo yes" 2> /dev/null | tr -d '\r' | rg -q yes
}

step "putting two media on the share for the app to move"
cp "$seed_image" "$moved_from"
# a few bytes past the end of each JPEG, which every decoder ignores and no checksum does. Two
# media that are byte-for-byte copies of each other would let "the right one arrived" pass on a
# build that moved the wrong one
printf 'the one that stays on the share' >> "$moved_from"
cp "$seed_image" "$away_from"
printf 'the one that comes to the device' >> "$away_from"
moved_md5="$(md5sum < "$moved_from")"
away_md5="$(md5sum < "$away_from")"
note "$FIXTURE_MOVE_FILE and $FIXTURE_MOVE_AWAY_FILE are in $FIXTURE_MOVE_FOLDER on the share"

step "making sure the device has a folder for the second one to land in"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
# one image, so MediaStore has the folder and the picker lists it
"${ADB[@]}" push "$seed_image" "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null 2>&1 || true

step "and putting a file of the device where the third move starts from"
onto_on_host="$RUN_DIR/$FIXTURE_MOVE_ONTO_FILE"
cp "$seed_image" "$onto_on_host"
printf 'the one that goes to the share' >> "$onto_on_host"
onto_md5="$(md5sum < "$onto_on_host")"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_DEVICE_SOURCE_DIR'"
"${ADB[@]}" push "$onto_on_host" "$onto_from" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$onto_from" > /dev/null 2>&1 || true
note "$onto_from is on the device"

step "seeding, and scanning the share so both have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "95-menu"
if ! wait_for_log "Walked the share:" 900 "95-scan"; then
    fail "the share was never scanned, so there is nothing to move"
    screenshot "95-no-scan"
    finish
fi

step "opening $FIXTURE_MOVE_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_MOVE_FOLDER" 60 "95-list"; then
    fail "$FIXTURE_MOVE_FOLDER is not in the folder list"
    screenshot "95-no-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_MOVE_FOLDER" "95-open-folder"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it is drawn
dump="$(ui_dump "95-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

########################################################################################
step "moving $FIXTURE_MOVE_FILE into $FIXTURE_MOVE_DESTINATION, another folder of the share"
########################################################################################

if ! ui_wait_text "$FIXTURE_MOVE_FILE" 60 "95-grid"; then
    fail "$FIXTURE_MOVE_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "95-no-file"
    finish
fi

if ! select_row "$FIXTURE_MOVE_FILE" "95-select"; then
    screenshot "95-not-selected"
    finish
fi

open_overflow_menu
sleep 1
selection="$(ui_dump "95-selected")"
if python3 "$DRIVE_DIR/ui.py" "$selection" --text "Move to" --exact > /dev/null; then
    pass "'Move to' is offered for a medium of the share"
else
    fail "'Move to' is not offered for a medium of the share (view tree in $selection)"
    python3 "$DRIVE_DIR/ui.py" "$selection" --list | sed 's/^/     /'
    finish
fi

logcat_reset
ui_tap_text "Move to" "95-tap-move"
sleep 2

# the picker opens on the storage the folder list was on, which is the share, so the destination
# folder is right there. Its chips are what would take it elsewhere
if ! ui_wait_exact_text "$FIXTURE_MOVE_DESTINATION" 30 "95-picker"; then
    fail "$FIXTURE_MOVE_DESTINATION is not in the picker"
    screenshot "95-no-picker-folder"
    finish
fi

screenshot "95-picker"
ui_tap_exact_text "$FIXTURE_MOVE_DESTINATION" "95-pick-share-folder"

if ! wait_for_log "Moved 1 of 1 within the share" 180 "95-move-within"; then
    fail "the move within the share never finished"
    screenshot "95-no-move-within"
    logcat_dump "95-move-within" > /dev/null
    finish
fi

capture_log "95-move-within"
refute_log "A write to the share failed" "the share took the move"
# the point of doing it inside the share at all: the file is put in another folder by the share
# itself, so nothing is read and nothing is written back
refute_log "Copied . of . " "nothing was copied for a move inside the share"

step "what the share has now"
if [ -f "$moved_to" ]; then
    pass "$FIXTURE_MOVE_DESTINATION/$FIXTURE_MOVE_FILE is on the share"
else
    fail "$FIXTURE_MOVE_DESTINATION/$FIXTURE_MOVE_FILE is not on the share"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_DESTINATION" | sed 's/^/     /'
    finish
fi

if [ -f "$moved_from" ]; then
    fail "$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_FILE is still there too; it was copied rather than moved"
else
    pass "and it is gone from $FIXTURE_MOVE_FOLDER"
fi

if [ "$(md5sum < "$moved_to")" = "$moved_md5" ]; then
    pass "and it is byte for byte the medium that left"
else
    fail "what arrived in $FIXTURE_MOVE_DESTINATION is not the medium that left $FIXTURE_MOVE_FOLDER"
fi

# the other seeded medium sits in the folder it left, and a move that took the wrong row would
# have taken that one
if [ -f "$away_from" ] && [ "$(md5sum < "$away_from")" = "$away_md5" ]; then
    pass "and $FIXTURE_MOVE_AWAY_FILE, which was next to it, has not moved"
else
    fail "$FIXTURE_MOVE_AWAY_FILE is gone or changed; the move landed on the wrong medium"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_FOLDER" | sed 's/^/     /'
fi

step "and the app shows it where it now is, without walking the share again"
if ! back_to_the_folder_list "$FIXTURE_MOVE_DESTINATION" "95-folder-list-after"; then
    fail "$FIXTURE_MOVE_DESTINATION is not in the folder list"
    screenshot "95-no-destination-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_MOVE_DESTINATION" "95-open-destination"
sleep 3
if ui_wait_text "$FIXTURE_MOVE_FILE" 30 "95-destination-grid"; then
    pass "$FIXTURE_MOVE_DESTINATION shows $FIXTURE_MOVE_FILE"
else
    fail "$FIXTURE_MOVE_DESTINATION does not show $FIXTURE_MOVE_FILE, though the share has it there"
    screenshot "95-destination-stale"
fi

capture_log "95-move-within-rows"
refute_log "Walked the share:" "no walk of the share was needed for the rows to follow"

########################################################################################
step "moving $FIXTURE_MOVE_AWAY_FILE off the share, onto this device"
########################################################################################

if ! back_to_the_folder_list "$FIXTURE_MOVE_FOLDER" "95-list-again"; then
    fail "$FIXTURE_MOVE_FOLDER is not in the folder list"
    screenshot "95-no-folder-again"
    finish
fi

ui_tap_exact_text "$FIXTURE_MOVE_FOLDER" "95-open-folder-again"
sleep 3

if ! ui_wait_text "$FIXTURE_MOVE_AWAY_FILE" 60 "95-grid-again"; then
    fail "$FIXTURE_MOVE_AWAY_FILE is not in the grid"
    screenshot "95-no-away-file"
    finish
fi

if ! select_row "$FIXTURE_MOVE_AWAY_FILE" "95-select-away"; then
    screenshot "95-away-not-selected"
    finish
fi

open_overflow_menu
sleep 1
logcat_reset
ui_tap_text "Move to" "95-tap-move-away"
sleep 2

if ! ui_wait_text "This device" 30 "95-picker-away"; then
    fail "the folder picker has no storage chips; a destination on the device cannot be reached"
    screenshot "95-no-chips"
    finish
fi

ui_tap_text "This device" "95-pick-local"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" 30 "95-picker-local"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not in the picker (MediaStore may not have taken the seed image)"
    screenshot "95-no-local-destination"
    finish
fi

screenshot "95-picker-local"
ui_tap_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" "95-pick-local-destination"

if ! wait_for_log "Moved 1 of 1 off the share" 300 "95-move-away"; then
    fail "the move off the share never finished"
    screenshot "95-no-move-away"
    logcat_dump "95-move-away" > /dev/null
    finish
fi

capture_log "95-move-away"

step "what each side has now"
for _ in $(seq 1 15); do
    if device_file_exists "$away_to"; then
        break
    fi
    sleep 2
done

if device_file_exists "$away_to"; then
    pass "$away_to is on the device"
else
    fail "nothing arrived on the device; the folder holds:"
    "${ADB[@]}" shell "ls -l '$FIXTURE_LOCAL_DESTINATION_DIR'" | sed 's/^/     /'
    finish
fi

# the order these two are done in is the whole of a move: the original may only go once the copy
# is whole, and what arrived has to be the medium that left rather than an empty file of its name
device_md5="$("${ADB[@]}" shell md5sum "$away_to" | tr -d '\r' | awk '{print $1}')"
if [ "$device_md5" = "$(printf '%s' "$away_md5" | awk '{print $1}')" ]; then
    pass "and it is byte for byte the medium that left the share"
else
    fail "what arrived on the device is not the medium that left the share"
fi

if [ -f "$away_from" ]; then
    fail "$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_AWAY_FILE is still on the share; it was copied rather than moved"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_FOLDER" | sed 's/^/     /'
else
    pass "and it is gone from the share"
fi

########################################################################################
step "moving $FIXTURE_MOVE_ONTO_FILE the other way, from this device onto the share"
########################################################################################

# the storage chip belongs to the folder list, not to a grid inside a folder
if ! back_to_the_folder_list "$FIXTURE_MOVE_FOLDER" "95-list-before-device"; then
    fail "the folder list of the share did not come back"
    screenshot "95-no-folder-list-before-device"
    finish
fi

switch_storage_to "This device" "95-to-device"
sleep 3

if ! ui_wait_exact_text "$FIXTURE_DEVICE_SOURCE_NAME" 60 "95-device-list"; then
    fail "$FIXTURE_DEVICE_SOURCE_NAME is not in the folder list of this device"
    screenshot "95-no-device-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_DEVICE_SOURCE_NAME" "95-open-device-folder"
sleep 3

if ! ui_wait_text "$FIXTURE_MOVE_ONTO_FILE" 60 "95-device-grid"; then
    fail "$FIXTURE_MOVE_ONTO_FILE is not in the grid of $FIXTURE_DEVICE_SOURCE_NAME"
    screenshot "95-no-onto-file"
    finish
fi

if ! select_row "$FIXTURE_MOVE_ONTO_FILE" "95-select-onto"; then
    screenshot "95-onto-not-selected"
    finish
fi

open_overflow_menu
sleep 1
logcat_reset
ui_tap_text "Move to" "95-tap-move-onto"
sleep 2

if ! ui_wait_exact_text "Network share" 30 "95-picker-onto"; then
    fail "the folder picker has no network share chip, so the share cannot be reached"
    screenshot "95-no-share-chip"
    finish
fi

ui_tap_exact_text "Network share" "95-pick-share"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_MOVE_FOLDER" 30 "95-picker-share-folder"; then
    fail "$FIXTURE_MOVE_FOLDER is not in the picker; the share's folders have no rows"
    screenshot "95-no-share-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_MOVE_FOLDER" "95-pick-share-destination"

if ! wait_for_log "Moved 1 of 1 onto the share" 300 "95-move-onto"; then
    fail "the move onto the share never finished"
    screenshot "95-no-move-onto"
    logcat_dump "95-move-onto" > /dev/null
    finish
fi

capture_log "95-move-onto"
refute_log "0 of 1 onto the share" "the move onto the share did not report a failure"

step "what each side has now"
if [ -f "$onto_to" ]; then
    pass "$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_ONTO_FILE is on the share"
else
    fail "$FIXTURE_MOVE_FOLDER/$FIXTURE_MOVE_ONTO_FILE is not on the share"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_MOVE_FOLDER" | sed 's/^/     /'
    finish
fi

if [ "$(md5sum < "$onto_to")" = "$onto_md5" ]; then
    pass "and it is byte for byte the file that left the device"
else
    fail "what arrived on the share is not the file that left the device"
fi

# the half that costs something if it happens in the wrong order. The device file may only go
# once the share has the whole of it
for _ in $(seq 1 15); do
    if ! device_file_exists "$onto_from"; then
        break
    fi
    sleep 2
done

if device_file_exists "$onto_from"; then
    fail "$onto_from is still on the device; it was copied rather than moved"
    "${ADB[@]}" shell "ls -l '$FIXTURE_DEVICE_SOURCE_DIR'" | sed 's/^/     /'
else
    pass "and it is gone from the device"
fi

# the neighbour of the fixture's own, which nothing in this script may touch
if [ -f "$seed_image" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which the fixture counts, is still there"
else
    fail "$FIXTURE_COPY_SOURCE_FILE is gone from the share"
fi

screenshot "95-done"
finish
