#!/usr/bin/env bash
# The folder list's toolbar has a button that goes back to the top of the storage on screen, in
# place of the one that opened the camera: from inside a group, one tap leaves every group and
# subfolder that is open at once.
#
# What is driven, with a folder of this device in the group Trips:
#
# - at the top of the list the button is not offered, on the toolbar or in its overflow, and
#   neither is the camera
# - inside Trips it is, and tapping it lands on the top again: Trips is listed and the folder
#   inside it is not, and the button is gone again
# - inside a folder of Trips the button is offered too, and tapping it closes the folder and lands
#   on the top of the list, not back in Trips
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

go_to_top="Back to the top of this storage"
folder_on_device="/sdcard/Pictures/$FIXTURE_SORTED_GROUP_FOLDER"
file_on_device="$folder_on_device/$FIXTURE_SORTED_GROUP_FILE"
# the path the app knows the folder by, which is what its group membership is written under
app_path="/storage/emulated/0/${folder_on_device#/sdcard/}"

clean_the_device() {
    "${ADB[@]}" shell "rm -rf '$folder_on_device'"
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$file_on_device" > /dev/null 2>&1 || true
}

trap clean_the_device EXIT
clean_the_device

on_screen() {
    local text="$1" name="$2"
    local dump
    dump="$(ui_dump "$name")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact > /dev/null
}

# whether the toolbar offers $1, as an icon or in its overflow; the overflow is closed again
offered() {
    local text="$1" name="$2"
    if on_screen "$text" "$name-toolbar"; then
        return 0
    fi

    open_overflow_menu
    sleep 1
    local found=1
    if on_screen "$text" "$name-overflow"; then
        found=0
    fi
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 1
    return "$found"
}

step "putting a folder on the device, in the group $FIXTURE_GROUP_PARENT_NAME"
"${ADB[@]}" shell "mkdir -p '$folder_on_device'"
"${ADB[@]}" push "$seed_image" "$file_on_device" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$file_on_device" > /dev/null 2>&1 || true

FIXTURE_GROUP_EXTRA_MEMBER="$app_path" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

if ! ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 60 "79-list"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the folder list of this device"
    screenshot "79-no-group"
    finish
fi

step "at the top of the list"
if offered "$go_to_top" "79-top"; then
    fail "'$go_to_top' is offered at the top already"
else
    pass "'$go_to_top' is not offered"
fi

if offered "Open camera" "79-top-camera"; then
    fail "the camera is still offered"
else
    pass "nor is the camera"
fi

step "inside $FIXTURE_GROUP_PARENT_NAME"
ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "79-open-group" || finish
if ! ui_wait_exact_text "$FIXTURE_SORTED_GROUP_FOLDER" 20 "79-in-group"; then
    fail "opening $FIXTURE_GROUP_PARENT_NAME did not show $FIXTURE_SORTED_GROUP_FOLDER"
    screenshot "79-not-in-group"
    finish
fi
screenshot "79-in-group"

if offered "$go_to_top" "79-group"; then
    pass "'$go_to_top' is offered"
else
    fail "'$go_to_top' is not offered inside the group"
    finish
fi

step "tapping it"
tap_action "$go_to_top" "79-tap" || finish
if ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 20 "79-back"; then
    pass "the list is at the top again: $FIXTURE_GROUP_PARENT_NAME is listed"
else
    fail "$FIXTURE_GROUP_PARENT_NAME is not listed after the tap"
    screenshot "79-not-back"
    finish
fi
screenshot "79-back"

if on_screen "$FIXTURE_SORTED_GROUP_FOLDER" "79-back-folder"; then
    fail "$FIXTURE_SORTED_GROUP_FOLDER is still listed, though it is only inside the group"
else
    pass "and the folder inside the group is not"
fi

if offered "$go_to_top" "79-back-button"; then
    fail "'$go_to_top' is still offered at the top"
else
    pass "and the button is gone again"
fi

step "inside $FIXTURE_SORTED_GROUP_FOLDER, in $FIXTURE_GROUP_PARENT_NAME"
ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "79-open-group-again" || finish
ui_wait_exact_text "$FIXTURE_SORTED_GROUP_FOLDER" 20 "79-in-group-again" || finish
ui_tap_exact_text "$FIXTURE_SORTED_GROUP_FOLDER" "79-open-folder" || finish
sleep 3
screenshot "79-in-folder"

if offered "$go_to_top" "79-folder"; then
    pass "'$go_to_top' is offered in the folder"
else
    fail "'$go_to_top' is not offered in the folder"
    finish
fi

tap_action "$go_to_top" "79-tap-in-folder" || finish
if ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 20 "79-back-from-folder"; then
    pass "tapping it lands on the top of the folder list: $FIXTURE_GROUP_PARENT_NAME is listed"
else
    fail "$FIXTURE_GROUP_PARENT_NAME is not listed after the tap in the folder"
    screenshot "79-not-back-from-folder"
    finish
fi
screenshot "79-back-from-folder"

if on_screen "$FIXTURE_SORTED_GROUP_FOLDER" "79-back-from-folder-folder"; then
    fail "$FIXTURE_SORTED_GROUP_FOLDER is listed, so the list stayed inside the group"
else
    pass "and not inside the group"
fi

finish
