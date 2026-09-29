#!/usr/bin/env bash
# #72: a photo shared into the app from another app is put where the destination picker says, and
# the picker lists only the folders of the storage its chip shows -- the network share's chip
# lists the share's folders, not this device's and not pCloud's.
#
# The share sheet's entry is ReceiveSharedMediaActivity, started here with the intent another app
# would send it: ACTION_SEND with a photo of MediaStore. What it opens is the same destination
# picker a selection's "Copy to" opens, so what is asked of it is asked on every chip in turn,
# both of the list as it opens and of its search:
#
# - the share's chip: none of this device's folders, none of pCloud's, and no group that holds
#   only those; the share's own Camera is there
# - pCloud's chip and this device's: their own folder is there. This is the half that says the
#   first half is worth anything -- a check that a name is not on screen passes just as well on a
#   picker that lists nothing at all
#
# The folders this script brings start with a prefix that sorts them ahead of everything the
# share holds, so that one listed under the wrong chip is the first row, never below the fold.
# The share is walked once and pCloud listed once, so that their folders have rows.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed_file="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_file" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

prefix="$FIXTURE_SHARED_IN_PREFIX"
photo="$FIXTURE_SHARED_IN_FILE"
device_pictures="/sdcard/Pictures"
device_app_root="/storage/emulated/0/Pictures"
requests_log="$RUN_DIR/pcloud-requests.log"

device_folder="${prefix}OnTheDevice"
cloud_folder="${prefix}OnPCloud"
device_in_group="${prefix}DeviceInGroup"
cloud_in_group="${prefix}PCloudInGroup"
group_name="${prefix}Elsewhere"
# a folder the share has and nothing else does, which is what the share's chip must show
share_folder="Camera"

stub_pid=""
leave_things_as_they_were() {
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
    rm -rf "$FIXTURE_PCLOUD_DIR"
    "${ADB[@]}" shell "rm -rf $device_pictures/$prefix*" > /dev/null 2>&1 || true
}

trap leave_things_as_they_were EXIT
leave_things_as_they_were

# --- the storages, and the app ---------------------------------------------------------------

step "putting a folder on this device and on pCloud, and one of each into a group of their own"
for folder in "$device_folder" "$device_in_group"; do
    "${ADB[@]}" shell "mkdir -p '$device_pictures/$folder'"
    "${ADB[@]}" push "$seed_file" "$device_pictures/$folder/$photo" > /dev/null
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$device_pictures/$folder/$photo" > /dev/null 2>&1 || true
done

for folder in "$cloud_folder" "$cloud_in_group"; do
    mkdir -p "$FIXTURE_PCLOUD_DIR/$folder"
    cp "$seed_file" "$FIXTURE_PCLOUD_DIR/$folder/$photo"
done

python3 "$TEST_DEVICE_DIR/fixture/pcloud-stub.py" \
    --root "$FIXTURE_PCLOUD_DIR" \
    --port "$FIXTURE_PCLOUD_PORT" \
    --token "$FIXTURE_PCLOUD_TOKEN" \
    --log "$requests_log" \
    > "$RUN_DIR/pcloud-stub.log" 2>&1 &
stub_pid=$!

stub_is_up=0
for _ in $(seq 1 20); do
    if (exec 3<> "/dev/tcp/127.0.0.1/$FIXTURE_PCLOUD_PORT") 2> /dev/null; then
        exec 3<&- || true
        stub_is_up=1
        break
    fi
    sleep 1
done

if [ "$stub_is_up" != "1" ]; then
    fail "the pCloud stub never came up; its own log is at $RUN_DIR/pcloud-stub.log"
    finish
fi

env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    FIXTURE_OTHER_GROUP_NAME="$group_name" \
    FIXTURE_OTHER_GROUP_MEMBERS="$device_app_root/$device_in_group|pcloud:/$cloud_in_group" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4

step "walking the share, and listing pCloud, so that their folders have rows"
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "76-walk"
if ! wait_for_log "Walked the share:" 900 "76-walked"; then
    fail "the share was never walked"
    screenshot "76-no-walk"
    finish
fi

switch_storage_to "pCloud" "76-to-pcloud"
sleep 2
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "76-list-pcloud"
if ! ui_wait_exact_text "$cloud_folder" 120 "76-pcloud-listed"; then
    fail "pCloud's folders did not turn up after a rescan"
    screenshot "76-no-pcloud"
    finish
fi

# --- the share sheet -------------------------------------------------------------------------

step "sharing a photo of this device into the app, the way another app would"
shared_path="$device_app_root/$device_folder/$photo"
media_id="$("${ADB[@]}" shell "content query --uri content://media/external/images/media --projection _id --where \"_data='$shared_path'\"" | tr -d '\r' | rg -o '_id=[0-9]+' | cut -d= -f2 || true)"
if [ -z "$media_id" ]; then
    fail "MediaStore has no row for $shared_path, so there is nothing to share"
    finish
fi

note "sharing content://media/external/images/media/$media_id"
"${ADB[@]}" shell am start \
    -a android.intent.action.SEND \
    -t image/jpeg \
    --grant-read-uri-permission \
    --eu android.intent.extra.STREAM "content://media/external/images/media/$media_id" \
    -n "$FIXTURE_PACKAGE/org.fossify.gallery.activities.ReceiveSharedMediaActivity" > /dev/null

if ! ui_wait_exact_text "Network share" 30 "76-picker"; then
    fail "the destination picker did not open, or has no network share chip"
    screenshot "76-no-picker"
    finish
fi

# The rows of the picker, one name per line. Asked of the dump rather than of the screen: every
# name here sorts ahead of what the share holds, so the rows that matter are the first ones
picker_rows() {
    python3 "$DRIVE_DIR/ui.py" "$(ui_dump "$1")" --list | cut -f1
}

# Picks a chip, then asks that its own folder is listed and the other storages' are not.
# $1 the chip, $2 the folder that must be there, $3... the names that must not be
expect_the_chip() {
    local chip="$1" wanted="$2" name="$3"
    shift 3
    ui_tap_exact_text "$chip" "$name-chip" || return 1
    if ui_wait_exact_text "$wanted" 20 "$name-wanted"; then
        pass "$chip: $wanted is listed"
    else
        fail "$chip: $wanted is not listed"
        screenshot "$name-no-$wanted"
    fi

    local rows unwanted
    rows="$(picker_rows "$name-rows")"
    for unwanted in "$@"; do
        if echo "$rows" | rg -q -x -F -- "$unwanted"; then
            fail "$chip: $unwanted is listed, and it is not on this storage"
            screenshot "$name-has-$unwanted"
        else
            pass "$chip: $unwanted is not listed"
        fi
    done
}

step "the list the picker opens on, chip by chip"
expect_the_chip "Network share" "$share_folder" "76-share" \
    "$device_folder" "$cloud_folder" "$group_name"
expect_the_chip "pCloud" "$cloud_folder" "76-cloud" \
    "$device_folder" "$share_folder"
expect_the_chip "This device" "$device_folder" "76-device" \
    "$cloud_folder" "$share_folder"

step "and its search, chip by chip"
dump="$(ui_dump "76-search")"
if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "top_toolbar_search")"; then
    fail "there is no search box on the picker (view tree in $dump)"
    finish
fi

# shellcheck disable=SC2086
"${ADB[@]}" shell input tap $point
sleep 1
# the prefix and nothing more: it is in every name this script brought, and in none the share has.
# So under the share's chip the search finds nothing at all, and under the others their own folder
"${ADB[@]}" shell input text "$prefix"
sleep 2

# the picker's empty placeholder, commons' no_items_found
expect_the_chip "Network share" "No items found." "76-search-share" \
    "$device_folder" "$cloud_folder" "$device_in_group" "$cloud_in_group" "$group_name"
expect_the_chip "pCloud" "$cloud_folder" "76-search-cloud" \
    "$device_folder" "$device_in_group"
expect_the_chip "This device" "$device_folder" "76-search-device" \
    "$cloud_folder" "$cloud_in_group"

screenshot "76-done"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1

finish
