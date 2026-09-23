#!/usr/bin/env bash
# #105: the editor's "Save as" asks where and under what name on every storage, and sends the
# edit there -- for a photo of the share as much as for one of this device.
#
# Until now the editor offered a photo of pCloud or of the share one save only, "Overwrite
# original", because the copy it edits lives in this app's cache and a "Save as" of that copy
# would have put the edit under another name inside the cache, where nothing the user has can
# reach it. Now "Save as" opens on the folder the photo lives in, offers every storage that is
# set up, and routes the result by destination: a folder of the share takes it through the
# share, a folder of this device straight.
#
# Two ways out are driven, from the same photo of the share, each edited and then saved under a
# new name somewhere else:
#
# - into another folder of the share. What says it arrived is the share itself, read off
#   fixture/share; what says the edit went there and not back over the photo is the photo
#   being byte for byte what it was, with nothing left under the stash name an overwrite uses
# - onto this device. Read with `adb shell` off /sdcard, the original untouched the same way
#
# Both check that the screen that opened the editor did not take the save for one that went
# astray: it used to warn that "the editor saved elsewhere" whenever the copy came back
# untouched, which is now exactly what a "Save as" leaves behind.
#
# Nothing in the fixture's counts may be edited -- 10-scan-whole-share.sh asserts on them -- so
# this script brings its own file and takes it away again on the way in and on the way out.
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

original_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_EDITOR_SAVE_AS_FOLDER/$FIXTURE_EDITOR_SAVE_AS_FILE"
stash_on_host="$original_on_host.$FIXTURE_OVERWRITE_STASH_SUFFIX"
extension="${FIXTURE_EDITOR_SAVE_AS_FILE##*.}"
saved_on_share="$FIXTURE_SHARE_DIR/$FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION/$FIXTURE_EDITOR_SAVE_AS_SHARE_NAME.$extension"
saved_on_device="$FIXTURE_LOCAL_DESTINATION_DIR/$FIXTURE_EDITOR_SAVE_AS_DEVICE_NAME.$extension"

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# a file of this script's left on the share would be counted by the next run of
# 10-scan-whole-share.sh
clean_the_share() {
    rm -f "$original_on_host" "$stash_on_host" "$saved_on_share"
}

trap clean_the_share EXIT
clean_the_share

device_file_exists() {
    "${ADB[@]}" shell "[ -f '$1' ] && echo yes" 2> /dev/null | tr -d '\r' | rg -q yes
}

# Looks for a row in the folder picker, scrolling down when it is not on screen; the same as
# 75-save-as-storages.sh, for the same reason: a row below the fold is not in the tree
find_in_picker() {
    local text="$1" name="${2:-picker}"
    local attempt
    for attempt in 1 2 3 4 5; do
        if ui_wait_exact_text "$text" 4 "$name-$attempt"; then
            return 0
        fi

        "${ADB[@]}" shell input swipe 540 1600 540 900 300
        sleep 1
    done

    return 1
}

# The way into the editor, the same as 97-edit-on-the-share.sh: the photo full screen, the
# pencil, the editor up on it, one rotation so that there is an edit to save
open_the_editor_and_rotate() {
    local name="$1"
    ui_tap_text "$FIXTURE_EDITOR_SAVE_AS_FILE" "$name-open-photo"
    sleep 4

    tap_action "Edit" "$name-edit" || return 1
    if ! ui_wait_text "Overwrite original" 90 "$name-editor-wait"; then
        if ! ui_wait_text "Transform" 30 "$name-editor-wait-2"; then
            fail "the editor never came up after the pencil"
            screenshot "$name-no-editor"
            logcat_dump "$name-editor" > /dev/null
            return 1
        fi
    fi

    sleep 2
    local editor
    editor="$(ui_dump "$name-editor")"
    if ! python3 "$DRIVE_DIR/ui.py" "$editor" --text "Rotate" --exact > /dev/null; then
        ui_tap_exact_text "Transform" "$name-transform" || return 1
        sleep 2
    fi

    ui_tap_exact_text "Rotate" "$name-rotate" || return 1
    sleep 2
    screenshot "$name-after-rotate"
}

# "Save as" out of the editor, and the dialog it opens: on the share's folder the photo is in,
# said by name rather than as a raw pseudo path glued to this device's label.
#
# The item is `ifRoom` with an icon, so on this screen it is the tick on the toolbar and not a
# line in the overflow; tap_action looks in both places, which is the whole of the lesson in
# agents/tests/the-delete-icon-is-not-in-the-overflow.md
open_save_as() {
    local name="$1"
    if tap_action "Save as" "$name-save-as"; then
        pass "the editor offers Save as for a photo of the share"
    else
        return 1
    fi

    sleep 3
    local dialog
    dialog="$(ui_dump "$name-dialog")"
    screenshot "$name-dialog"

    if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "smb:" > /dev/null; then
        fail "the Save as dialog shows the raw pseudo path (view tree in $dialog)"
        python3 "$DRIVE_DIR/ui.py" "$dialog" --list | sed 's/^/     /'
    else
        pass "the dialog does not show the raw pseudo path"
    fi

    # the cache the copy lives in must not be what the dialog opens on
    if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "smb-edit" > /dev/null; then
        fail "the Save as dialog opened on the cache the copy lives in (view tree in $dialog)"
    else
        pass "and it does not open on the cache"
    fi

    if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "Network share" > /dev/null; then
        pass "and it opens on the share, where the photo lives"
    else
        fail "the dialog does not name the network share (view tree in $dialog)"
        python3 "$DRIVE_DIR/ui.py" "$dialog" --list | sed 's/^/     /'
    fi
}

# the name typed into the dialog's filename box, which opens focused with the old name in it
type_the_name() {
    local text="$1" name="$2"
    local dialog point
    dialog="$(ui_dump "$name-name-box")"
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dialog" --resource-id "filename_value")"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        sleep 1
        replace_text_field "$text" 64
        sleep 1
        return 0
    fi

    fail "the filename box is not in the dialog (view tree in $dialog)"
    return 1
}

step "putting a photo on the share for the app to edit"
cp "$seed_image" "$original_on_host"
# a few bytes past the end of the JPEG, which every decoder ignores and no checksum does, so
# that this file is not byte for byte its neighbour
printf 'not the neighbour' >> "$original_on_host"
original_md5="$(md5sum < "$original_on_host")"
note "$FIXTURE_EDITOR_SAVE_AS_FOLDER/$FIXTURE_EDITOR_SAVE_AS_FILE is on the share"

step "making a folder on the device for the second save to land in"
"${ADB[@]}" shell "rm -f '$saved_on_device'" || true
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
# one image, so that MediaStore has the folder and the picker lists it
"${ADB[@]}" push "$seed_image" "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null
for path in "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" "$saved_on_device"; do
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$path" > /dev/null 2>&1 || true
done

step "seeding, and scanning the share so it has a row"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "98-menu"
if ! wait_for_log "Walked the share:" 900 "98-scan"; then
    fail "the share was never scanned, so there is nothing to edit"
    screenshot "98-no-scan"
    finish
fi

step "opening $FIXTURE_EDITOR_SAVE_AS_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_EDITOR_SAVE_AS_FOLDER" 60 "98-list"; then
    fail "$FIXTURE_EDITOR_SAVE_AS_FOLDER is not in the folder list"
    screenshot "98-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_EDITOR_SAVE_AS_FOLDER" "98-open-folder"
sleep 3

dump="$(ui_dump "98-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_EDITOR_SAVE_AS_FILE" 60 "98-grid"; then
    fail "$FIXTURE_EDITOR_SAVE_AS_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "98-no-file"
    finish
fi

step "editing it, and saving under a new name into $FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION on the share"
open_the_editor_and_rotate "98-share" || finish
open_save_as "98-share" || finish

# the folder box opens the app's own picker, on the folder the photo is in. The destination is
# another folder of the share, so the share's chip is tapped first to go to its root, where
# the picker walks the share live for its folders
ui_tap_text "Network share" "98-share-tap-folder"
sleep 3
if ! ui_wait_exact_text "Network share" 30 "98-share-picker-chip"; then
    fail "the picker has no network share chip"
    screenshot "98-share-no-chip"
    finish
fi

ui_tap_exact_text "Network share" "98-share-tap-chip"
sleep 3
if ! ui_wait_exact_text "$FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION" 30 "98-share-picker"; then
    fail "$FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION is not in the picker"
    screenshot "98-share-no-destination"
    finish
fi

screenshot "98-share-picker"
ui_tap_exact_text "$FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION" "98-share-pick-destination" || finish
sleep 2
# a folder of the share is a destination as soon as it is tapped; the OK is only there when
# the picker is still walking
picker="$(ui_dump "98-share-picker-after")"
if python3 "$DRIVE_DIR/ui.py" "$picker" --text "OK" --exact > /dev/null; then
    ui_tap_exact_text "OK" "98-share-picker-ok"
    sleep 2
fi

type_the_name "$FIXTURE_EDITOR_SAVE_AS_SHARE_NAME" "98-share" || finish
screenshot "98-share-typed"
logcat_reset
ui_tap_exact_text "OK" "98-share-save" || finish

# the write goes up through the share and the folder is walked again afterwards; the file on
# the share is the assertion, so it is waited for rather than a log line
for _ in $(seq 1 45); do
    if [ -f "$saved_on_share" ]; then
        break
    fi
    sleep 2
done

sleep 3
screenshot "98-share-saved"
capture_log "98-share-save"

if [ -f "$saved_on_share" ]; then
    pass "$FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION/$FIXTURE_EDITOR_SAVE_AS_SHARE_NAME.$extension arrived on the share"
else
    fail "nothing arrived in $FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION on the share; the folder holds:"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_EDITOR_SAVE_AS_SHARE_DESTINATION" | sed 's/^/     /'
    finish
fi

if ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$saved_on_share" > /dev/null; then
    pass "and it decodes as an image"
else
    fail "what arrived on the share does not decode"
fi

refute_log "A write to the share failed" "the share took the write"
refute_log "Could not save" "nothing said the save failed"
refute_log "The editor reported a save but left" "the screen behind the editor did not take the save for one gone astray"
refute_log "Wrote over" "and nothing was written over"

step "and the photo it was made from is untouched"
if [ -f "$original_on_host" ] && [ "$(md5sum < "$original_on_host")" = "$original_md5" ]; then
    pass "$FIXTURE_EDITOR_SAVE_AS_FOLDER/$FIXTURE_EDITOR_SAVE_AS_FILE is byte for byte what it was"
else
    fail "the original on the share was changed or is gone; the save went over it"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_EDITOR_SAVE_AS_FOLDER" | sed 's/^/     /'
fi

if [ -e "$stash_on_host" ]; then
    fail "$FIXTURE_EDITOR_SAVE_AS_FILE.$FIXTURE_OVERWRITE_STASH_SUFFIX is on the share; an overwrite was started"
else
    pass "and nothing was stashed beside it"
fi

# the editor closes on a save, so the viewer is what is on screen now
if ! ui_wait_text "$FIXTURE_EDITOR_SAVE_AS_FILE" 20 "98-back-in-viewer"; then
    fail "the viewer did not come back after the save"
    screenshot "98-no-viewer"
    finish
fi

step "editing it again, and saving under a new name onto this device"
tap_action "Edit" "98-device-edit" || finish
if ! ui_wait_text "Overwrite original" 90 "98-device-editor-wait"; then
    if ! ui_wait_text "Transform" 30 "98-device-editor-wait-2"; then
        fail "the editor never came up the second time"
        screenshot "98-device-no-editor"
        finish
    fi
fi

sleep 2
editor="$(ui_dump "98-device-editor")"
if ! python3 "$DRIVE_DIR/ui.py" "$editor" --text "Rotate" --exact > /dev/null; then
    ui_tap_exact_text "Transform" "98-device-transform" || finish
    sleep 2
fi
ui_tap_exact_text "Rotate" "98-device-rotate" || finish
sleep 2

open_save_as "98-device" || finish

ui_tap_text "Network share" "98-device-tap-folder"
sleep 3
if ! ui_wait_exact_text "Internal" 30 "98-device-picker"; then
    fail "the picker does not offer this device"
    screenshot "98-device-no-internal"
    finish
fi

# this picker lists real directories of the device rather than what a scan found, so the walk
# down is the ordinary one: the storage root, then Pictures, then the folder seeded above
ui_tap_exact_text "Internal" "98-device-pick-internal"
sleep 3
if ! find_in_picker "Pictures" "98-device-picker-root"; then
    fail "the internal storage root does not list Pictures"
    screenshot "98-device-no-pictures"
    finish
fi

ui_tap_exact_text "Pictures" "98-device-open-pictures"
sleep 2
if ! find_in_picker "$FIXTURE_LOCAL_DESTINATION_NAME" "98-device-picker-pictures"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not under Pictures"
    screenshot "98-device-no-destination"
    finish
fi

ui_tap_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" "98-device-open-destination"
sleep 2
ui_tap_exact_text "OK" "98-device-picker-ok"
sleep 2

type_the_name "$FIXTURE_EDITOR_SAVE_AS_DEVICE_NAME" "98-device" || finish
screenshot "98-device-typed"
logcat_reset
ui_tap_exact_text "OK" "98-device-save" || finish

for _ in $(seq 1 20); do
    if device_file_exists "$saved_on_device"; then
        break
    fi
    sleep 2
done

sleep 3
screenshot "98-device-saved"
capture_log "98-device-save"

if device_file_exists "$saved_on_device"; then
    pass "$saved_on_device arrived on this device"
else
    fail "nothing arrived on this device; the folder holds:"
    "${ADB[@]}" shell "ls -l '$FIXTURE_LOCAL_DESTINATION_DIR'" | sed 's/^/     /'
    finish
fi

saved_size="$("${ADB[@]}" shell stat -c %s "$saved_on_device" | tr -d '\r')"
if [ "$saved_size" -gt 0 ]; then
    pass "and it has $saved_size bytes in it"
else
    fail "what was saved on this device is empty"
fi

refute_log "The editor reported a save but left" "the screen behind the editor did not take the save for one gone astray"
refute_log "Wrote over" "and nothing was written over on the share"
refute_log "FileNotFoundException" "nothing went looking for a file that does not exist"

step "and the photo on the share is still untouched"
if [ -f "$original_on_host" ] && [ "$(md5sum < "$original_on_host")" = "$original_md5" ]; then
    pass "$FIXTURE_EDITOR_SAVE_AS_FOLDER/$FIXTURE_EDITOR_SAVE_AS_FILE is byte for byte what it was"
else
    fail "the original on the share was changed or is gone; the save went over it"
fi

if [ -e "$stash_on_host" ]; then
    fail "$FIXTURE_EDITOR_SAVE_AS_FILE.$FIXTURE_OVERWRITE_STASH_SUFFIX is on the share; an overwrite was started"
else
    pass "and nothing was stashed beside it"
fi

screenshot "98-done"
finish
