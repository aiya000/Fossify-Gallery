#!/usr/bin/env bash
# #76: what the app has to say is said on its own screen, not through an OS toast.
#
# A toast is black on white, two lines at most, and gone before a sentence has been read. Since
# #76 a message lands in the app's own window -- a bar along the bottom of the screen, laid above
# the viewer's actions -- and what a dialog has to say about one of its boxes is said under that
# box, where it stays until the box is edited.
#
# What is driven, with a photo of this device:
#
# - a held action of the viewer says its name on the app's own screen. The bar is up for seconds
#   and a dump takes seconds (agents/tests/a-toast-is-gone-before-the-dump.md), so the witness is
#   the line every message leaves in the log, which says where the message went
# - "Save as" with the name box emptied keeps the dialog up and complains under the box. That
#   stays on the screen, so the view tree is the witness there
#
# Not driven: a message a screen says as it closes is still a toast, on purpose, and nothing of
# this device's closes a screen with a message without a share to fail against
set -euo pipefail

# the folder list opens on this device, so nothing has to be switched to
export FIXTURE_STORAGE_FILTER=1

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

# A long press on the view with this id. By id rather than by text: the viewer's toolbar carries
# a Share of its own, and a hold on that one gets the system's tooltip, which says nothing in
# the app's log
hold_view() {
    local id="$1" name="$2"
    local dump point
    dump="$(ui_dump "$name")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "$id")"; then
        fail "nothing on screen has the id $id (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input motionevent DOWN $point
    sleep 1
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input motionevent UP $point
}

step "putting a photo of this device's own in $FIXTURE_LOCAL_DESTINATION_NAME"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
"${ADB[@]}" push "$seed_image" "$photo" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$photo" > /dev/null 2>&1 || true

step "seeding, and opening the photo in the viewer"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
app_start
sleep 4

if ! ui_wait_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" 30 "87-list"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not in the folder list of this device"
    screenshot "87-no-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" "87-open-folder" || finish
sleep 3

# the names are off by default; the toggle is one global setting, so it is tapped once and
# only when the file is not already named on screen
if ! ui_wait_text "$FIXTURE_SAVE_AS_DEVICE_FILE" 5 "87-grid-named"; then
    dump="$(ui_dump "87-toolbar")"
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        sleep 2
    else
        fail "the filename toggle is not on screen (view tree in $dump)"
    fi
fi

if ! ui_wait_text "$FIXTURE_SAVE_AS_DEVICE_FILE" 30 "87-grid"; then
    fail "$FIXTURE_SAVE_AS_DEVICE_FILE is not in the grid"
    screenshot "87-no-file"
    finish
fi

ui_tap_text "$FIXTURE_SAVE_AS_DEVICE_FILE" "87-open-photo"
sleep 4

step "a held action of the viewer says its name on the app's own screen"
logcat_reset
hold_view "bottom_share" "87-hold-share" || finish
# for the eye: the bar should be on this one, above the row of actions
screenshot "87-bar"

if wait_for_log 'InAppMessage.*Said in the app: Share' 10 "87-bar-log"; then
    pass "Share was said on the app's own screen"
else
    fail "a hold on Share said nothing on the app's own screen (see 87-bar.png)"
fi

capture_log "87-bar"
refute_log 'InAppMessage.*Said as a toast' "and nothing went through the OS as a toast"

step "a name the Save as dialog cannot take is said under its box"
# the viewer offers "Save as" once the photo has been turned, and not before
tap_action "Rotate" "87-rotate" || finish
sleep 1
ui_tap_exact_text "Rotate right" "87-rotate-right" || finish
sleep 2
tap_action "Save as" "87-save-as" || finish
sleep 3

dialog="$(ui_dump "87-dialog")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dialog" --resource-id "filename_value")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 1
else
    fail "the Save as dialog has no name box (view tree in $dialog)"
    screenshot "87-no-dialog"
    finish
fi

# the box is emptied key by key, the way replace_text_field does it, since the name proposed in
# it is the dialog's business
keys=""
for ((i = 0; i < 64; i++)); do
    keys="$keys KEYCODE_DEL"
done
"${ADB[@]}" shell input keyevent KEYCODE_MOVE_END
# shellcheck disable=SC2086
"${ADB[@]}" shell input keyevent $keys

# the keyboard up covers the buttons; the first back takes only the keyboard down
if keyboard_is_shown; then
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 1
fi

logcat_reset
ui_tap_exact_text "OK" "87-ok" || finish
sleep 2

dialog="$(ui_dump "87-field-error")"
screenshot "87-field-error"
if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "Filename cannot be empty" > /dev/null; then
    pass "the dialog stays up and says so under the name box"
else
    fail "nothing on the dialog says the name is empty (view tree in $dialog)"
fi

capture_log "87-field"
expect_log 'InAppMessage.*Said under the field: Filename cannot be empty' "and the log says where it was said"
refute_log 'InAppMessage.*Said as a toast' "and nothing went through the OS as a toast"

ui_tap_exact_text "Cancel" "87-cancel" || true
sleep 1

finish
