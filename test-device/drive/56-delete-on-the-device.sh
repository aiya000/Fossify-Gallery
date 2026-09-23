#!/usr/bin/env bash
# #106: a medium of this device, and a folder of it, are deleted through the app's recycle bin --
# and the device is the only witness worth asking.
#
# The delete on the device is the one the app was born with, and it had no script of its own:
# every driving script so far was about the share or pCloud, because that is where the new code
# was. Once deleting became an operation of MediaStorage (#106), the device's half of it moved as
# well, and a move with no witness is a move nobody can vouch for.
#
# So what is driven here is what the device shows afterwards, not what the app believes:
#
# - the file leaves the folder it was in, read with `adb shell` off /sdcard
# - and turns up in the app's recycle bin, which is the app's own files directory with the file
#   kept under its full original path, read with run-as. A delete that skipped the bin would pass
#   the first check and fail this one
# - the file next to it stays, byte for byte. A delete that reached too far would pass every check
#   that only looks at what was asked for
# - the confirmation offers the bin: it says "recycle bin", and the "skip the recycle bin"
#   checkbox is on it -- the one the share's confirmation must not have (55)
# - a whole folder goes the same way, its medium into the bin, and the folder above it stays
#
# It brings its own file and its own folder, and takes them away on the way in and on the way
# out. The bin needs no cleaning: seed-app.sh wipes the app's data, and the bin lives in it.
set -euo pipefail

# the folder list opens on this device, so nothing has to be switched to
export FIXTURE_STORAGE_FILTER=1

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# an image the fixture already has, pushed into place. What is in it does not matter; what
# matters is that it is a medium MediaStore lists, so the app can show it and the bin can hold it
seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

doomed_on_device="$FIXTURE_DEVICE_SOURCE_DIR/$FIXTURE_DEVICE_DELETE_FILE"
# the one that must survive: it sits in the same folder, and it is 70's own file
neighbour_on_device="$FIXTURE_DEVICE_SOURCE_DIR/$FIXTURE_DEVICE_SOURCE_FILE"
doomed_folder_on_device="$(dirname "$FIXTURE_DEVICE_SOURCE_DIR")/$FIXTURE_DEVICE_DELETE_FOLDER"
doomed_folder_file_on_device="$doomed_folder_on_device/$FIXTURE_DEVICE_DELETE_FOLDER_FILE"

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# a folder of this script's left on the device would sit in the folder list of every later run
clean_the_device() {
    "${ADB[@]}" shell "rm -f '$doomed_on_device'"
    "${ADB[@]}" shell "rm -rf '$doomed_folder_on_device'"
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$doomed_on_device" > /dev/null 2>&1 || true
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$doomed_folder_file_on_device" > /dev/null 2>&1 || true
}

trap clean_the_device EXIT
clean_the_device

# what the app's bin holds of a name, read with run-as. The bin is the app's own files
# directory, and a file in it is kept under its full original path -- files/storage/emulated/0/
# and so on -- so the name is looked for anywhere under there
in_the_bin() {
    "${ADB[@]}" shell "run-as $FIXTURE_PACKAGE sh -c 'find files -name \"$1\" 2>/dev/null'" | tr -d '\r' | rg -q .
}

# waits for a file to leave the device and turn up in the bin, which is how the app says a
# delete into the bin is through: there is no log line for it
wait_for_bin() {
    local device_path="$1" name="$2" seconds="${3:-60}"
    local waited=0
    while [ "$waited" -lt "$seconds" ]; do
        if ! "${ADB[@]}" shell "test -f '$device_path'" && in_the_bin "$name"; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

step "putting a file and a folder on the device for the app to delete"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_DEVICE_SOURCE_DIR' '$doomed_folder_on_device'"
"${ADB[@]}" push "$seed_image" "$neighbour_on_device" > /dev/null
"${ADB[@]}" push "$seed_image" "$doomed_on_device" > /dev/null
"${ADB[@]}" push "$seed_image" "$doomed_folder_file_on_device" > /dev/null
for path in "$neighbour_on_device" "$doomed_on_device" "$doomed_folder_file_on_device"; do
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$path" > /dev/null 2>&1 || true
done
neighbour_before="$("${ADB[@]}" shell md5sum "$neighbour_on_device" | tr -d '\r' | awk '{print $1}')"
note "$doomed_on_device and $doomed_folder_on_device/ are on the device"

step "seeding, and opening $FIXTURE_DEVICE_SOURCE_NAME on the device"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

if ! ui_wait_exact_text "$FIXTURE_DEVICE_SOURCE_NAME" 60 "56-list"; then
    fail "$FIXTURE_DEVICE_SOURCE_NAME is not in the folder list (MediaStore may not have taken the seed images)"
    screenshot "56-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_DEVICE_SOURCE_NAME" "56-open"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "56-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

step "picking $FIXTURE_DEVICE_DELETE_FILE"
if ! ui_wait_text "$FIXTURE_DEVICE_DELETE_FILE" 60 "56-grid"; then
    fail "$FIXTURE_DEVICE_DELETE_FILE is not in the grid"
    screenshot "56-no-file"
    finish
fi

if ! select_row "$FIXTURE_DEVICE_DELETE_FILE" "56-select"; then
    screenshot "56-not-selected"
    finish
fi

step "what the confirmation says"
selection="$(ui_dump "56-selected")"
if ! python3 "$DRIVE_DIR/ui.py" "$selection" --text "Delete" --exact > /dev/null; then
    fail "'Delete' is not offered for a medium of the device (view tree in $selection)"
    finish
fi

ui_tap_text "Delete" "56-tap-delete"
sleep 2
confirmation="$(ui_dump "56-confirmation")"
screenshot "56-confirmation"

if python3 "$DRIVE_DIR/ui.py" "$confirmation" --text "recycle bin" > /dev/null; then
    pass "the confirmation offers the recycle bin"
else
    fail "the confirmation does not mention the recycle bin (view tree in $confirmation)"
    python3 "$DRIVE_DIR/ui.py" "$confirmation" --list | sed 's/^/     /'
fi

if python3 "$DRIVE_DIR/ui.py" "$confirmation" --resource-id "skip_the_recycle_bin_checkbox" > /dev/null; then
    pass "and it offers to skip it"
else
    fail "the confirmation has no 'skip the recycle bin' checkbox, and the device has a bin"
fi

step "deleting it"
ui_tap_text "Yes" "56-confirm"

if ! wait_for_bin "$doomed_on_device" "$FIXTURE_DEVICE_DELETE_FILE" 60; then
    fail "$FIXTURE_DEVICE_DELETE_FILE did not leave $FIXTURE_DEVICE_SOURCE_NAME for the bin"
    screenshot "56-no-delete"
    logcat_dump "56-delete" > /dev/null
    finish
fi

pass "$FIXTURE_DEVICE_DELETE_FILE is gone from $FIXTURE_DEVICE_SOURCE_NAME"
pass "and it is in the app's recycle bin"

step "what the device has now"
neighbour_after="$("${ADB[@]}" shell md5sum "$neighbour_on_device" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
if [ -n "$neighbour_after" ] && [ "$neighbour_after" = "$neighbour_before" ]; then
    pass "$FIXTURE_DEVICE_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$neighbour_on_device is gone or changed; the delete took the folder's other media with it"
fi

if ui_wait_text "$FIXTURE_DEVICE_DELETE_FILE" 10 "56-grid-after"; then
    fail "$FIXTURE_DEVICE_DELETE_FILE is still in the grid, though it is in the bin"
    screenshot "56-still-in-grid"
else
    pass "and it is out of the grid"
fi

step "back to the folder list, to delete a whole folder of the device"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3

if ! ui_wait_exact_text "$FIXTURE_DEVICE_DELETE_FOLDER" 60 "56-folder-list"; then
    fail "$FIXTURE_DEVICE_DELETE_FOLDER is not in the folder list"
    screenshot "56-no-doomed-folder"
    finish
fi

if ! select_row "$FIXTURE_DEVICE_DELETE_FOLDER" "56-select-folder"; then
    screenshot "56-folder-not-selected"
    finish
fi

folder_selection="$(ui_dump "56-folder-selected")"
if ! python3 "$DRIVE_DIR/ui.py" "$folder_selection" --text "Delete" --exact > /dev/null; then
    fail "'Delete' is not offered for a folder of the device (view tree in $folder_selection)"
    finish
fi

ui_tap_text "Delete" "56-tap-delete-folder"
sleep 2
folder_confirmation="$(ui_dump "56-folder-confirmation")"
screenshot "56-folder-confirmation"
if python3 "$DRIVE_DIR/ui.py" "$folder_confirmation" --text "recycle bin" > /dev/null; then
    pass "the folder's confirmation offers the recycle bin"
else
    fail "the folder's confirmation does not mention the recycle bin (view tree in $folder_confirmation)"
    python3 "$DRIVE_DIR/ui.py" "$folder_confirmation" --list | sed 's/^/     /'
fi

ui_tap_text "Yes" "56-confirm-folder"

if ! wait_for_bin "$doomed_folder_file_on_device" "$FIXTURE_DEVICE_DELETE_FOLDER_FILE" 60; then
    fail "$FIXTURE_DEVICE_DELETE_FOLDER_FILE did not leave $FIXTURE_DEVICE_DELETE_FOLDER for the bin"
    screenshot "56-no-folder-delete"
    logcat_dump "56-delete-folder" > /dev/null
    finish
fi

pass "$FIXTURE_DEVICE_DELETE_FOLDER_FILE is gone from $FIXTURE_DEVICE_DELETE_FOLDER"
pass "and it is in the app's recycle bin"

step "what the device has now"
# the folder beside it. A delete that started one level too high would take it, and 70's file with it
if "${ADB[@]}" shell "test -f '$neighbour_on_device'"; then
    pass "$FIXTURE_DEVICE_SOURCE_NAME/$FIXTURE_DEVICE_SOURCE_FILE is still there"
else
    fail "$neighbour_on_device is gone; the folder delete reached beside the folder it was given"
fi

if ui_wait_exact_text "$FIXTURE_DEVICE_DELETE_FOLDER" 10 "56-folder-list-after"; then
    fail "$FIXTURE_DEVICE_DELETE_FOLDER is still in the folder list, though its medium is in the bin"
    screenshot "56-folder-still-listed"
else
    pass "and $FIXTURE_DEVICE_DELETE_FOLDER is out of the folder list"
fi

# the rows were marked deleted, which is what puts the bin's own tile in the list
if ui_wait_exact_text "Recycle bin" 30 "56-bin-tile"; then
    pass "and the recycle bin is in the folder list, with what it holds"
else
    fail "the folder list has no recycle bin tile, though two media went into it"
    screenshot "56-no-bin-tile"
fi

screenshot "56-done"
finish
