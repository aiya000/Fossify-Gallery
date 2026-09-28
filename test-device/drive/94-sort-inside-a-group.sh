#!/usr/bin/env bash
# #137: the sorting dialog opened inside a group offers "Apply to this group only", checked, in
# place of "Apply to this storage only" -- a group belongs to no storage -- and unchecking it is
# asked about first, since it changes how every other group is sorted.
#
# What is driven, inside the group Trips with a folder of this device in it:
#
# - the dialog at the top of the list has no group checkbox, and the one inside Trips has it,
#   checked, and no storage checkbox
# - unchecking it asks, and No leaves it checked
# - OK with it checked stores a sorting for Trips alone: sort_folders_group_<id> in the app's
#   preferences, and the shared directory_sort_order untouched
# - opened again, the dialog shows Trips' own sorting; unchecking it, Yes, and OK takes Trips'
#   own away and writes the shared one instead, by last modified -- not the seeded name, so a
#   write that never happened cannot pass
#
# The witness is the app's preferences, read with run-as: what order the folders end up in is
# the sorting code the app has had all along, and what is new here is where the choice is kept.
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

folder_on_device="/sdcard/Pictures/$FIXTURE_SORTED_GROUP_FOLDER"
file_on_device="$folder_on_device/$FIXTURE_SORTED_GROUP_FILE"
# the path the app knows the folder by, which is what its group membership is written under
app_path="/storage/emulated/0/${folder_on_device#/sdcard/}"
group_key="sort_folders_group_$FIXTURE_GROUP_PARENT_ID"

clean_the_device() {
    "${ADB[@]}" shell "rm -rf '$folder_on_device'"
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$file_on_device" > /dev/null 2>&1 || true
}

trap clean_the_device EXIT
clean_the_device

# an int of the app's preferences, or nothing when it is not stored
stored_int() {
    "${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" cat shared_prefs/Prefs.xml | tr -d '\r' \
        | rg -o "<int name=\"$1\" value=\"[0-9-]+\"" | rg -o -- '-?[0-9]+"$' | tr -d '"' || true
}

open_sort_dialog() {
    local name="$1"
    tap_action "Sort by" "$name" || return 1
    sleep 2
}

group_box_checked() {
    local dump
    dump="$(ui_dump "$1")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --text "Apply to this group only" --exact --is-checked
}

on_screen() {
    local text="$1" name="$2"
    local dump
    dump="$(ui_dump "$name")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact > /dev/null
}

step "putting a folder on the device, in the group $FIXTURE_GROUP_PARENT_NAME"
"${ADB[@]}" shell "mkdir -p '$folder_on_device'"
"${ADB[@]}" push "$seed_image" "$file_on_device" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$file_on_device" > /dev/null 2>&1 || true

FIXTURE_GROUP_EXTRA_MEMBER="$app_path" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

if ! ui_wait_exact_text "$FIXTURE_GROUP_PARENT_NAME" 60 "94-list"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the folder list of this device"
    screenshot "94-no-group"
    finish
fi

shared_before="$(stored_int directory_sort_order)"
note "the shared sorting before: ${shared_before:-not stored}"

step "the dialog at the top of the list"
open_sort_dialog "94-top" || finish
if on_screen "Apply to this group only" "94-top-dialog"; then
    fail "the dialog at the top offers 'Apply to this group only', with no group open"
else
    pass "it has no group checkbox"
fi
ui_tap_exact_text "Cancel" "94-top-cancel"
sleep 1

step "the dialog inside $FIXTURE_GROUP_PARENT_NAME"
ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "94-open-group" || finish
sleep 3
open_sort_dialog "94-group" || finish
screenshot "94-group-dialog"

if group_box_checked "94-group-dialog"; then
    pass "it offers 'Apply to this group only', checked"
else
    fail "'Apply to this group only' is missing or not checked"
    finish
fi

if on_screen "Apply to this storage only" "94-group-dialog-storage"; then
    fail "it still offers 'Apply to this storage only', though a group belongs to no storage"
else
    pass "and no 'Apply to this storage only'"
fi

step "unchecking it, and answering No"
ui_tap_exact_text "Apply to this group only" "94-uncheck"
sleep 1
if on_screen "No" "94-confirmation"; then
    pass "unchecking it asks first"
    screenshot "94-confirmation"
else
    fail "unchecking it asked nothing"
    screenshot "94-no-confirmation"
    finish
fi

ui_tap_exact_text "No" "94-no"
sleep 1
if group_box_checked "94-after-no"; then
    pass "No left it checked"
else
    fail "No left it unchecked"
    finish
fi

step "sorting $FIXTURE_GROUP_PARENT_NAME by size, for the group only"
ui_tap_exact_text "Size" "94-size"
ui_tap_exact_text "OK" "94-ok-size"
sleep 2

group_sorting="$(stored_int "$group_key")"
if [ -n "$group_sorting" ] && (( group_sorting & 4 )); then
    pass "$FIXTURE_GROUP_PARENT_NAME has a sorting of its own, by size ($group_sorting)"
else
    fail "$group_key is ${group_sorting:-not stored}, not a sorting by size"
fi

shared_after="$(stored_int directory_sort_order)"
if [ "$shared_after" = "$shared_before" ]; then
    pass "and the shared sorting is untouched"
else
    fail "the shared sorting changed from ${shared_before:-not stored} to ${shared_after:-not stored}"
fi

step "opening it again, unchecking it with Yes, and sorting by last modified"
open_sort_dialog "94-again" || finish
dump="$(ui_dump "94-again-dialog")"
if python3 "$DRIVE_DIR/ui.py" "$dump" --text "Size" --exact --is-checked; then
    pass "the dialog shows $FIXTURE_GROUP_PARENT_NAME's own sorting"
else
    fail "the dialog does not show $FIXTURE_GROUP_PARENT_NAME's sorting by size"
fi

ui_tap_exact_text "Apply to this group only" "94-uncheck-again"
sleep 1
ui_tap_exact_text "Yes" "94-yes" || finish
sleep 1
if group_box_checked "94-after-yes"; then
    fail "Yes left it checked"
    finish
else
    pass "Yes unchecked it"
fi

ui_tap_exact_text "Last modified" "94-last-modified"
ui_tap_exact_text "OK" "94-ok-last-modified"
sleep 2

if [ -z "$(stored_int "$group_key")" ]; then
    pass "$FIXTURE_GROUP_PARENT_NAME's own sorting is gone"
else
    fail "$group_key is still stored"
fi

shared_after="$(stored_int directory_sort_order)"
if [ -n "$shared_after" ] && (( shared_after & 2 )) && ! (( shared_after & 4 )); then
    pass "and the shared sorting is by last modified now ($shared_after)"
else
    fail "the shared sorting is ${shared_after:-not stored}, not a sorting by last modified"
fi

screenshot "94-done"
finish
