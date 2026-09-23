#!/usr/bin/env bash
# #107: a folder can be made on the share from inside a folder of it, the way one can be made
# on this device and on pCloud.
#
# "Create new folder" was kept off the grid's menu for a folder of the share -- "that is the
# write step", said the comment -- and stayed off after the write step had landed, while the
# folder picker had learnt to make one. Now every storage makes a folder the same way, through
# MediaStorage.createFolder(): the device with its own dialog, pCloud and the share with the
# name dialog they share, and the new folder is shown as the temporary tile at the top of the
# folder list until something is put in it.
#
# Worth driving rather than reading, because what says the folder exists is the share, read off
# fixture/share, and what says the app knows about it is the tile in the folder list -- a new
# folder has no row, since a scan writes none for an empty one.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

new_folder_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_NEW_FOLDER_PARENT/$FIXTURE_NEW_FOLDER_NAME"

# Run on the way in as well as on the way out. A folder of this script's left on the share
# would be one more folder for the next run of 10-scan-whole-share.sh to count
clean_the_share() {
    rm -rf "$new_folder_on_host"
}

trap clean_the_share EXIT
clean_the_share

step "seeding, and scanning the share so its folders have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "84-menu"
if ! wait_for_log "Walked the share:" 900 "84-scan"; then
    fail "the share was never scanned, so there is no folder to open"
    screenshot "84-no-scan"
    finish
fi

step "opening $FIXTURE_NEW_FOLDER_PARENT on the share"
if ! ui_wait_exact_text "$FIXTURE_NEW_FOLDER_PARENT" 60 "84-list"; then
    fail "$FIXTURE_NEW_FOLDER_PARENT is not in the folder list"
    screenshot "84-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_NEW_FOLDER_PARENT" "84-open-folder"
sleep 3

step "making a folder inside it"
open_overflow_menu
sleep 1
menu="$(ui_dump "84-grid-menu")"
if python3 "$DRIVE_DIR/ui.py" "$menu" --text "Create new folder" --exact > /dev/null; then
    pass "the grid of a folder of the share offers to make a new folder"
else
    fail "the grid of a folder of the share does not offer to make a new folder (view tree in $menu)"
    python3 "$DRIVE_DIR/ui.py" "$menu" --list | sed 's/^/     /'
    finish
fi

ui_tap_exact_text "Create new folder" "84-tap-create" || finish
sleep 2
"${ADB[@]}" shell input text "$FIXTURE_NEW_FOLDER_NAME"
sleep 1
screenshot "84-name-typed"
logcat_reset
ui_tap_exact_text "OK" "84-name-ok" || finish

# the folder is made over the share; the share is the assertion, so it is waited for
for _ in $(seq 1 20); do
    if [ -d "$new_folder_on_host" ]; then
        break
    fi
    sleep 2
done

if [ -d "$new_folder_on_host" ]; then
    pass "$FIXTURE_NEW_FOLDER_PARENT/$FIXTURE_NEW_FOLDER_NAME is on the share"
else
    fail "nothing was made on the share; $FIXTURE_NEW_FOLDER_PARENT holds:"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_NEW_FOLDER_PARENT" | sed 's/^/     /'
    capture_log "84-create"
    finish
fi

capture_log "84-create"
refute_log "A write to the share failed" "the share took the write"

step "and the folder list shows it, empty as it is"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3
if ui_wait_exact_text "$FIXTURE_NEW_FOLDER_NAME" 20 "84-list-new"; then
    pass "the new folder is in the folder list"
else
    fail "the new folder is not in the folder list (view tree in $RUN_DIR/84-list-new.xml)"
    screenshot "84-no-tile"
fi

# leaving the list takes the temporary tile away, and must leave the folder on the share: the
# app does not delete a folder on a remote storage on its own, empty or not
step "and leaving the app leaves the folder on the share"
"${ADB[@]}" shell input keyevent KEYCODE_HOME
sleep 3
app_stop
sleep 2
if [ -d "$new_folder_on_host" ]; then
    pass "$FIXTURE_NEW_FOLDER_PARENT/$FIXTURE_NEW_FOLDER_NAME is still on the share"
else
    fail "the folder was taken off the share when the app was left"
fi

screenshot "84-done"
finish
