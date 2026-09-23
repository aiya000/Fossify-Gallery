#!/usr/bin/env bash
# #107: two places where the share was left out of a rule the other storages already had.
#
# - A video of the share tapped in the search results opens in the app's own player, the way
#   it does from a folder. The search screen used to send only a pCloud video there and hand
#   everything else to whatever the video player setting says -- and the setting's default
#   after the first run is the system player, which is given the share's pseudo path and can
#   do nothing with it. The grid had the rule right; the search screen was written before the
#   share existed
# - A selection of folders mixing this device and the share is offered no "Move to", the same
#   as a selection with pCloud in it and the same as every other action that goes through a
#   storage. It used to be offered, and the move would have gone through the first folder's
#   storage for all of them
#
# Worth driving rather than reading, because both are about what the screen offers and where a
# tap lands: the search screen's rule can only be seen by tapping, and the menu's by opening it
# on a selection that mixes storages, which no unit test can make.
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

# the video the search is for: the fixture names them video-<n>.mp4 in every folder that has
# any, so the first folder's first one is enough to search by
searched_video="video-1.mp4"
searched_text="video-1"

focused_window() {
    "${ADB[@]}" shell dumpsys window | tr -d '\r' | rg -o 'mCurrentFocus=.*' | head -n 1 || true
}

wait_for_window() {
    local pattern="$1" seconds="${2:-30}"
    local waited=0 focus
    while [ "$waited" -lt "$seconds" ]; do
        focus="$(focused_window)"
        if printf '%s\n' "$focus" | rg -q -- "$pattern"; then
            printf '%s\n' "$focus"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done

    printf '%s\n' "$focus"
    return 1
}

step "making a folder on the device, so that the folder list has one to mix with the share's"
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
"${ADB[@]}" push "$seed_image" "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null 2>&1 || true

step "seeding, and scanning the share so it has rows to search and folders to select"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "83-menu"
if ! wait_for_log "Walked the share:" 900 "83-scan"; then
    fail "the share was never scanned, so there is nothing to search"
    screenshot "83-no-scan"
    finish
fi

step "searching for a video of the share, and tapping it"
# the search box on the folder list's toolbar searches folders; the button that turns it into
# a file search across everything appears once something has been typed into it. The file
# search opens with an empty box of its own, so the text is typed twice
ui_tap_text "Search" "83-search-icon" || finish
sleep 2
"${ADB[@]}" shell input text "$searched_text"
sleep 2
if ! ui_wait_text "Switch to file search" 10 "83-switch"; then
    fail "the folder list's search did not offer to switch to a file search"
    screenshot "83-no-switch"
    finish
fi

ui_tap_text "Switch to file search" "83-tap-switch" || finish
sleep 3
"${ADB[@]}" shell input text "$searched_text"
sleep 4

# the filenames, so that the result can be tapped by name; the toggle sits on the search
# screen's own toolbar
dump="$(ui_dump "83-search-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
fi

if ! ui_wait_text "$searched_video" 30 "83-results"; then
    fail "$searched_video is not among the search results"
    screenshot "83-no-results"
    finish
fi

screenshot "83-results"
logcat_reset
ui_tap_text "$searched_video" "83-tap-result" || finish

# the app's own player is the fullscreen viewer; the system player would be another app's
# window, or, given a pseudo path it cannot open, no window at all and a toast
if focus="$(wait_for_window 'ViewPagerActivity' 20)"; then
    pass "the video of the share opened in the app's own player: $focus"
else
    fail "the video did not open in the app's own player; the focus is on $focus"
fi

capture_log "83-search"
refute_log "FileNotFoundException" "nothing went looking for a file that does not exist"
sleep 2
screenshot "83-playing"

# back out of the player and the search, to the folder list
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

step "a selection of folders mixing this device and the share"
switch_storage_to "All storages" "83-all-storages" || finish
sleep 3
if ! ui_wait_exact_text "$FIXTURE_LOCAL_DESTINATION_NAME" 30 "83-list-device-folder"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not in the folder list under all storages"
    screenshot "83-no-device-folder"
    finish
fi

if ! ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 30 "83-list-share-folder"; then
    fail "$FIXTURE_COPY_SOURCE_FOLDER is not in the folder list under all storages"
    screenshot "83-no-share-folder"
    finish
fi

if ! select_row "$FIXTURE_LOCAL_DESTINATION_NAME" "83-select-device-folder"; then
    screenshot "83-not-selected"
    finish
fi

ui_tap_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" "83-add-share-folder" || finish
sleep 1
if ! in_selection_mode "83-mixed-check"; then
    fail "the selection was lost when the share's folder was added to it"
    screenshot "83-selection-lost"
    finish
fi

open_overflow_menu
sleep 1
mixed="$(ui_dump "83-mixed-menu")"
screenshot "83-mixed-menu"
if python3 "$DRIVE_DIR/ui.py" "$mixed" --text "Move to" --exact > /dev/null; then
    fail "a selection mixing this device and the share is still offered Move to (view tree in $mixed)"
    python3 "$DRIVE_DIR/ui.py" "$mixed" --list | sed 's/^/     /'
else
    pass "a selection mixing this device and the share is offered no Move to"
fi

if python3 "$DRIVE_DIR/ui.py" "$mixed" --text "Copy to" --exact > /dev/null; then
    fail "and it is still offered Copy to (view tree in $mixed)"
else
    pass "and no Copy to either, like every other action that goes through a storage"
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

# the control: the same folder of the share on its own is offered the move, so the absence
# above is about the mix and not about the share
step "and the share's folder on its own, which must still be offered it"
if ! select_row "$FIXTURE_COPY_SOURCE_FOLDER" "83-select-share-folder"; then
    screenshot "83-not-selected-share"
    finish
fi

open_overflow_menu
sleep 1
single="$(ui_dump "83-single-menu")"
screenshot "83-single-menu"
if python3 "$DRIVE_DIR/ui.py" "$single" --text "Move to" --exact > /dev/null; then
    pass "a folder of the share on its own is offered Move to"
else
    fail "a folder of the share on its own is not offered Move to (view tree in $single)"
    python3 "$DRIVE_DIR/ui.py" "$single" --list | sed 's/^/     /'
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1

screenshot "83-done"
finish
