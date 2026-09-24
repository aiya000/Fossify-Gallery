#!/usr/bin/env bash
# #125: the editor's "Save as" opens on "<name>_1" for a photo of this device, the same as it
# does for a photo of pCloud or of the share.
#
# The suffix is what makes "Save as" a save beside the original by default: without it, the OK
# that comes next asks about writing over the photo just edited, which is what "Overwrite
# original" is for. Since #105 the dialog asks the same question on every storage, and the
# proposed name was the one thing still answered differently on this device.
#
# Nothing is saved here. What is checked is the dialog as it opens -- the name box and the
# extension box -- and then it is cancelled. The photo is this script's own, seeded into the
# folder 50-copy-off-share.sh uses as a destination, so MediaStore knows the folder and the app
# lists it
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

photo="$FIXTURE_LOCAL_DESTINATION_DIR/$FIXTURE_SAVE_AS_DEVICE_FILE"
stem="${FIXTURE_SAVE_AS_DEVICE_FILE%.*}"
extension="${FIXTURE_SAVE_AS_DEVICE_FILE##*.}"

step "putting a photo of this device's own in $FIXTURE_LOCAL_DESTINATION_NAME"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
"${ADB[@]}" push "$seed_image" "$photo" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$photo" > /dev/null 2>&1 || true

step "seeding, and opening the folder on this device"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
FIXTURE_STORAGE_FILTER=1
app_start
sleep 4

if ! ui_wait_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" 30 "99-list"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not in the folder list of this device"
    screenshot "99-no-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" "99-open-folder" || finish
sleep 3

# the names are off by default; the toggle is one global setting, so it is tapped once and
# only when the file is not already named on screen
if ! ui_wait_text "$FIXTURE_SAVE_AS_DEVICE_FILE" 5 "99-grid-named"; then
    dump="$(ui_dump "99-toolbar")"
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        sleep 2
    else
        fail "the filename toggle is not on screen (view tree in $dump)"
    fi
fi

if ! ui_wait_text "$FIXTURE_SAVE_AS_DEVICE_FILE" 30 "99-grid"; then
    fail "$FIXTURE_SAVE_AS_DEVICE_FILE is not in the grid"
    screenshot "99-no-file"
    finish
fi

step "opening it, and the editor on it"
ui_tap_text "$FIXTURE_SAVE_AS_DEVICE_FILE" "99-open-photo"
sleep 4
tap_action "Edit" "99-edit" || finish

# a second editor on the device puts the system's "Edit with" sheet between the two screens,
# with the app's own editor proposed at the top and "Just once" / "Always" under it; it is
# taken for this once, so that the sheet is still there for the next run to drive
if ui_wait_text "Edit with" 5 "99-chooser"; then
    if ! ui_tap_exact_text "Just once" "99-pick-editor"; then
        ui_tap_text "Gallery" "99-pick-editor-by-name" || finish
    fi
    sleep 2
fi

if ! ui_wait_text "Overwrite original" 90 "99-editor-wait"; then
    if ! ui_wait_text "Transform" 30 "99-editor-wait-2"; then
        fail "the editor never came up after the pencil"
        screenshot "99-no-editor"
        logcat_dump "99-editor" > /dev/null
        finish
    fi
fi

sleep 2
editor="$(ui_dump "99-editor")"
if ! python3 "$DRIVE_DIR/ui.py" "$editor" --text "Rotate" --exact > /dev/null; then
    ui_tap_exact_text "Transform" "99-transform" || finish
    sleep 2
fi

ui_tap_exact_text "Rotate" "99-rotate" || finish
sleep 2

step "Save as, and the name it opens on"
# `ifRoom` with an icon: the tick on the toolbar here, not a line in the overflow
tap_action "Save as" "99-save-as" || finish
sleep 3
dialog="$(ui_dump "99-dialog")"
screenshot "99-dialog"

# the boxes are read by their ids, and what they hold is said either way, so a wrong name is
# in the output rather than only in the view tree
proposed="$(python3 "$DRIVE_DIR/ui.py" "$dialog" --resource-id "filename_value" --value || echo "<no name box>")"
proposed_extension="$(python3 "$DRIVE_DIR/ui.py" "$dialog" --resource-id "extension_value" --value || echo "<no extension box>")"
note "the dialog opened on \"$proposed\" . \"$proposed_extension\""

if [ "$proposed" = "${stem}_1" ]; then
    pass "the name box opens on ${stem}_1, beside the original"
else
    fail "the name box opens on \"$proposed\" rather than ${stem}_1 (view tree in $dialog)"
fi

if [ "$proposed_extension" = "$extension" ]; then
    pass "and the extension box on $extension"
else
    fail "the extension box holds \"$proposed_extension\" rather than $extension"
fi

if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "$FIXTURE_LOCAL_DESTINATION_NAME" > /dev/null; then
    pass "and the folder box on $FIXTURE_LOCAL_DESTINATION_NAME, where the photo lives"
else
    fail "the folder box does not name $FIXTURE_LOCAL_DESTINATION_NAME (view tree in $dialog)"
fi

# #132: the keyboard stays down until the name box is tapped. Up, it covers the extension box
# and the buttons, and what is usually done here -- take the proposed name, or pick a folder --
# needs no typing. Waited over rather than read once, so that a keyboard still sliding in is
# not taken for one that stayed down
if wait_for_keyboard 4; then
    fail "the on-screen keyboard came up with the dialog (see 99-dialog.png)"
else
    pass "and the on-screen keyboard stays down"
fi

step "the name box, tapped, still brings the keyboard up"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dialog" --resource-id "filename_value")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
else
    fail "the name box is not on screen to tap (view tree in $dialog)"
fi

if wait_for_keyboard 6; then
    pass "a tap on the name box brings the keyboard up"
else
    fail "the name box was tapped and no keyboard came up"
    screenshot "99-no-keyboard"
fi

# the keyboard up covers the buttons; the first back takes only the keyboard down
if keyboard_is_shown; then
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 1
fi

ui_tap_exact_text "Cancel" "99-cancel" || true
sleep 1

step "leaving the photo as it was"
if [ "$("${ADB[@]}" shell md5sum "$photo" | tr -d '\r' | awk '{print $1}')" = "$(md5sum < "$seed_image" | awk '{print $1}')" ]; then
    pass "$photo is byte for byte what was put there"
else
    fail "$photo was changed by a Save as that saved nothing"
fi

finish
