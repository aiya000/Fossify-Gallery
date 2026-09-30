#!/usr/bin/env bash
# #155: two connections to a share are two storages. Each is walked on its own, each has a row of
# its own in the storage menu, the folder list on one shows its folders and not the other's, and
# a copy from one share onto the other is turned away with the picker saying why.
#
# The two connections are the one fixture share with two roots: the first is pointed at Trips,
# the second at a folder this script brings (AbSecond, holding Left and Right). A connection is a
# host, a share and a root, so that is two storages as much as two machines would be -- and both
# roots are small, so each is walked in a second where the whole share takes five minutes.
#
# The first connection is left without a name and the second is given one, so that the menu shows
# both ways a share is called: by its name, and, once there is more than one share to tell apart,
# by its address.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

second_root="${FIXTURE_MEDIUM_PREFIX}Second"
second_on_host="$FIXTURE_SHARE_DIR/$second_root"
first_label="\\\\$FIXTURE_SMB_HOST\\$FIXTURE_SHARE_NAME\\$FIXTURE_GROUP_PARENT_NAME"
second_label="$FIXTURE_SECOND_SMB_NAME"
source_folder="Osaka"
source_file="video-1.mp4"

clean_up() {
    rm -rf "$second_on_host"
}

trap clean_up EXIT
clean_up

step "putting a second root on the share: $second_root, with Left and Right in it"
mkdir -p "$second_on_host/Left" "$second_on_host/Right"
cp "$FIXTURE_SHARE_DIR/Screens/image-1.jpg" "$second_on_host/Left/left.jpg"
cp "$FIXTURE_SHARE_DIR/Screens/image-2.jpg" "$second_on_host/Right/right.jpg"

step "seeding two connections: the first at $FIXTURE_GROUP_PARENT_NAME, the second, $second_label, at $second_root"
FIXTURE_SMB_ROOT_PATH="$FIXTURE_GROUP_PARENT_NAME" \
    FIXTURE_SECOND_SMB_ROOT_PATH="$second_root" \
    FIXTURE_STORAGE_FILTER=1 \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

app_stop
logcat_reset
FIXTURE_STORAGE_FILTER=1 app_start
sleep 4

########################################################################################
step "a rescan walks each share on its own"
########################################################################################

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "77-rescan"
if ! wait_for_log 'Walked the share: .* \(smb:2\)' 120 "77-walk-second" || ! wait_for_log 'Walked the share: .* \(smb:\)' 120 "77-walk-first"; then
    fail "the two shares were not both walked; see $(logcat_dump 77-walk-timeout)"
    finish
fi

capture_log "77-walks"
expect_log 'Walked the share: [0-9]+ folders, 2 files, 0 folders skipped \(smb:2\)' "the second share was walked, and found its two photos"
expect_log 'Walked the share: [0-9]+ folders, 4 files, 0 folders skipped \(smb:\)' "the first share was walked, and found the four videos of Trips"

########################################################################################
step "the storage menu has a row for each share"
########################################################################################

open_storage_menu "77-menu" || finish
menu="$(python3 "$DRIVE_DIR/ui.py" "$(ui_dump "77-menu-rows")" --list)"
for label in "This device" "All storages" "$second_label" "$first_label"; do
    if printf '%s\n' "$menu" | rg -q -F -- "$label"; then
        pass "the menu offers '$label'"
    else
        fail "the menu does not offer '$label' (it has: $(printf '%s' "$menu" | tr '\n' '|'))"
    fi
done

"${ADB[@]}" shell input keyevent KEYCODE_BACK > /dev/null
sleep 1

########################################################################################
step "each share's folder list is its own"
########################################################################################

switch_storage_to "$second_label" "77-to-second"
if ui_wait_exact_text "Left" 30 "77-second-left" && ui_wait_exact_text "Right" 10 "77-second-right"; then
    pass "$second_label shows Left and Right"
else
    fail "$second_label does not show its folders"
fi

if ui_wait_exact_text "$source_folder" 3 "77-second-no-osaka"; then
    fail "$second_label shows $source_folder, a folder of the other share"
else
    pass "and not $source_folder, which is the other share's"
fi

switch_storage_to "$first_label" "77-to-first"
if ui_wait_exact_text "$source_folder" 30 "77-first-osaka"; then
    pass "the first share shows $source_folder"
else
    fail "the first share does not show $source_folder"
fi

if ui_wait_exact_text "Left" 3 "77-first-no-left"; then
    fail "the first share shows Left, a folder of $second_label"
else
    pass "and not Left, which is $second_label's"
fi

switch_storage_to "All storages" "77-to-all"
if ui_wait_exact_text "$source_folder" 30 "77-all-osaka" && ui_wait_exact_text "Left" 10 "77-all-left"; then
    pass "All storages shows the folders of both shares"
else
    fail "All storages does not show both shares' folders"
fi

########################################################################################
step "a copy from one share onto the other is turned away"
########################################################################################

switch_storage_to "$first_label" "77-back-to-first"
ui_wait_exact_text "$source_folder" 30 "77-first-again" || true
ui_tap_exact_text "$source_folder" "77-open-source"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it is drawn
dump="$(ui_dump "77-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
fi

if ! ui_wait_text "$source_file" 30 "77-grid" || ! select_row "$source_file" "77-select"; then
    fail "$source_file could not be picked in $source_folder"
    finish
fi

tap_action "Copy to" "77-copy" || finish
sleep 2
# The chips are a row that scrolls sideways, and the first share's, called by its address, is
# wide enough to push the last chip off the screen. The row is scrolled to its end first
dump="$(ui_dump "77-picker-chips")"
if bounds="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "All storages" --bounds)"; then
    read -r _ top _ bottom <<< "$bounds"
    row_y=$(((top + bottom) / 2))
    "${ADB[@]}" shell input swipe 950 "$row_y" 150 "$row_y" 400
    sleep 1
fi

ui_tap_text "$second_label" "77-picker-chip" || finish
if ! ui_wait_exact_text "Left" 30 "77-picker-left"; then
    fail "the picker's $second_label chip does not list Left"
    finish
fi

pass "the picker's $second_label chip lists Left"
ui_tap_exact_text "Left" "77-pick-left"
sleep 5
if ui_wait_exact_text "Left" 5 "77-picker-still-up"; then
    pass "the picker stays up rather than taking Left as the copy's destination"
else
    fail "the picker went away, as if the copy was handed over"
    screenshot "77-picker-gone"
fi

if [ ! -e "$second_on_host/Left/$source_file" ] && [ -f "$FIXTURE_SHARE_DIR/$FIXTURE_GROUP_PARENT_NAME/$source_folder/$source_file" ]; then
    pass "and nothing arrived in Left, with $source_file still where it was"
else
    fail "the copy was carried out, or the source moved"
fi

finish
