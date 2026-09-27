#!/usr/bin/env bash
# A folder of the folder list, copied or moved with the destination picker's OK: at the top of
# the share, or inside a group, the folder itself goes there.
#
# The picker's OK used to pick nothing in "Copy to". At the top it said to tap a folder, and
# inside a group it said that a group holds folders, not files -- about a folder, which is the one
# thing a group does hold. "Move to" took the OK as "put the folder in this group" and left it on
# the device whatever storage the chips were showing.
#
# What is driven, starting from Carried, a folder of the device inside the group Trips:
#
# - Copy to, the share's chip, OK at the top: the share gets a folder Carried with the media in it
# - Copy to, the share's chip, the group Trips opened, OK: the share gets Carried (1) -- a copy
#   never pours into a folder that is already there -- and that folder is in Trips
# - Move to, the same way into Trips: the share gets Carried (2) in Trips, and the device's
#   Carried is emptied
#
# The share is read off fixture/share, the groups off the app's own preferences with run-as.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed_file_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_file_on_host" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

folder="$FIXTURE_CARRIED_FOLDER_NAME"
device_dir="$FIXTURE_CARRIED_FOLDER_DIR"
device_file="$device_dir/$FIXTURE_CARRIED_FOLDER_FILE"
# the path the app knows the folder by, which is what its group membership is written under
app_path="/storage/emulated/0/${device_dir#/sdcard/}"

first_on_host="$FIXTURE_SHARE_DIR/$folder"
second_on_host="$FIXTURE_SHARE_DIR/$folder (1)"
third_on_host="$FIXTURE_SHARE_DIR/$folder (2)"

# nothing this script writes may outlive it, the counts of manifest.env being asserted on elsewhere
clean_the_share() {
    rm -rf "$first_on_host" "$second_on_host" "$third_on_host"
}

trap clean_the_share EXIT
clean_the_share

# the folder members as the app has stored them, from its preferences
group_members() {
    "${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" cat shared_prefs/Prefs.xml | tr -d '\r' | rg -o 'name="folder_group_members">[^<]*' || true
}

# opens "Copy to" or "Move to" for the selection, switched to the share's chip
open_picker_on_the_share() {
    local action="$1" name="$2"
    tap_action "$action" "$name-action" || return 1
    sleep 2
    if ! ui_wait_exact_text "Network share" 30 "$name-picker"; then
        fail "the destination picker has no network share chip"
        screenshot "$name-no-share-chip"
        return 1
    fi

    ui_tap_exact_text "Network share" "$name-share-chip" || return 1
    sleep 2
}

# the folder list is inside the group Trips, with Carried selected
select_carried_in_the_group() {
    local name="$1"
    if ! ui_wait_exact_text "$folder" 60 "$name-list"; then
        fail "$folder is not in the group $FIXTURE_GROUP_PARENT_NAME"
        screenshot "$name-no-folder"
        return 1
    fi

    if ! in_selection_mode "$name-selected"; then
        select_row "$folder" "$name-select" || return 1
    fi
}

step "putting a folder on the device, in the group $FIXTURE_GROUP_PARENT_NAME"
"${ADB[@]}" shell "rm -rf '$device_dir'"
"${ADB[@]}" shell "mkdir -p '$device_dir'"
"${ADB[@]}" push "$seed_file_on_host" "$device_file" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$device_file" > /dev/null 2>&1 || true

FIXTURE_GROUP_EXTRA_MEMBER="$app_path" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

# the picker shows a group on the share's chip only when a folder of the share is in it, and the
# share's folders have rows only once it has been walked
step "scanning the share, so that the group has folders of the share in it"
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "72-menu"
if ! wait_for_log "Walked the share:" 900 "72-scan"; then
    fail "the share was never scanned"
    screenshot "72-no-scan"
    finish
fi

switch_storage_to "This device" "72-storage-local"
sleep 3
if ! ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 60 "72-list"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the folder list of this device"
    screenshot "72-no-group"
    finish
fi

ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "72-open-group" || finish
sleep 3

########################################################################################
step "Copy to, the share, OK at the top"
########################################################################################

select_carried_in_the_group "72-copy-top" || finish
open_picker_on_the_share "Copy to" "72-copy-top" || finish
screenshot "72-copy-top-picker"
logcat_reset
ui_tap_exact_text "OK" "72-copy-top-ok" || finish

if ! wait_for_log "Copied 1 of 1 onto the share to smb:/$folder," 300 "72-copy-top-done"; then
    fail "nothing was copied onto the share after OK at the top"
    screenshot "72-copy-top-not-copied"
    finish
fi

if cmp -s "$seed_file_on_host" "$first_on_host/$FIXTURE_CARRIED_FOLDER_FILE"; then
    pass "the share has $folder/$FIXTURE_CARRIED_FOLDER_FILE, byte for byte"
else
    fail "the share has no $folder/$FIXTURE_CARRIED_FOLDER_FILE"
    ls -la "$FIXTURE_SHARE_DIR" | sed 's/^/     /'
fi

if group_members | rg -qF "smb:/$folder&quot;"; then
    fail "smb:/$folder was put into a group, OK at the top names none"
else
    pass "and it is in no group"
fi

########################################################################################
step "Copy to, the share, OK inside the group $FIXTURE_GROUP_PARENT_NAME"
########################################################################################

select_carried_in_the_group "72-copy-group" || finish
open_picker_on_the_share "Copy to" "72-copy-group" || finish
if ! ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 30 "72-copy-group-picker"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the picker on the share's chip"
    screenshot "72-copy-group-no-group"
    finish
fi

ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "72-copy-group-open" || finish
sleep 2
screenshot "72-copy-group-picker"
logcat_reset
ui_tap_exact_text "OK" "72-copy-group-ok" || finish

if ! wait_for_log "Copied 1 of 1 onto the share to smb:/$folder \\(1\\)," 300 "72-copy-group-done"; then
    fail "nothing was copied onto the share after OK inside the group"
    screenshot "72-copy-group-not-copied"
    finish
fi

if cmp -s "$seed_file_on_host" "$second_on_host/$FIXTURE_CARRIED_FOLDER_FILE"; then
    pass "the share has '$folder (1)', not a second file poured into $folder"
else
    fail "the share has no '$folder (1)/$FIXTURE_CARRIED_FOLDER_FILE'"
    ls -la "$FIXTURE_SHARE_DIR" | sed 's/^/     /'
fi

if group_members | rg -qF "smb:/$folder (1)&quot;:$FIXTURE_GROUP_PARENT_ID"; then
    pass "and '$folder (1)' is in $FIXTURE_GROUP_PARENT_NAME"
else
    fail "'$folder (1)' is not in $FIXTURE_GROUP_PARENT_NAME: $(group_members)"
fi

if "${ADB[@]}" shell "[ -f '$device_file' ] && echo yes" | tr -d '\r' | rg -q yes; then
    pass "the device still has $folder after two copies"
else
    fail "the device's $folder lost its file to a copy"
fi

########################################################################################
step "Move to, the share, OK inside the group $FIXTURE_GROUP_PARENT_NAME"
########################################################################################

select_carried_in_the_group "72-move-group" || finish
open_picker_on_the_share "Move to" "72-move-group" || finish
if ! ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 30 "72-move-group-picker"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the picker on the share's chip"
    screenshot "72-move-group-no-group"
    finish
fi

ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "72-move-group-open" || finish
sleep 2
screenshot "72-move-group-picker"
logcat_reset
ui_tap_exact_text "OK" "72-move-group-ok" || finish

if ! wait_for_log "Moved 1 of 1 onto the share to smb:/$folder \\(2\\)," 300 "72-move-group-done"; then
    fail "nothing was moved onto the share after OK inside the group"
    screenshot "72-move-group-not-moved"
    finish
fi

if cmp -s "$seed_file_on_host" "$third_on_host/$FIXTURE_CARRIED_FOLDER_FILE"; then
    pass "the share has '$folder (2)'"
else
    fail "the share has no '$folder (2)/$FIXTURE_CARRIED_FOLDER_FILE'"
    ls -la "$FIXTURE_SHARE_DIR" | sed 's/^/     /'
fi

if group_members | rg -qF "smb:/$folder (2)&quot;:$FIXTURE_GROUP_PARENT_ID"; then
    pass "and '$folder (2)' is in $FIXTURE_GROUP_PARENT_NAME"
else
    fail "'$folder (2)' is not in $FIXTURE_GROUP_PARENT_NAME: $(group_members)"
fi

gone=0
for _ in $(seq 1 15); do
    if ! "${ADB[@]}" shell "[ -f '$device_file' ] && echo yes" | tr -d '\r' | rg -q yes; then
        gone=1
        break
    fi
    sleep 2
done

if [ "$gone" = "1" ]; then
    pass "and the device's $folder gave its file up"
else
    fail "the device still has $device_file after a move"
fi

screenshot "72-done"
finish
