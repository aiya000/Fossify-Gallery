#!/usr/bin/env bash
# #28: a medium of the share, and a folder of the share, can be deleted -- and the share is the
# only witness worth asking.
#
# This is the first thing the app does that takes something away from the share for good. Every
# other write so far added: a copy landed, and a copy that went wrong left a file behind at
# worst. A delete that goes wrong takes a photograph with it, and there is no recycle bin on an
# SMB share to take it out of again -- the app's own bin is a folder on the storage it belongs
# to, and the share has none.
#
# So what is driven here is both halves of that:
#
# - the file really leaves the share. Read off fixture/share on this machine, which is the
#   container's own directory rather than anything the app believes
# - the file next to it does not. A delete that took a folder's other media with it would pass
#   every check that only looks at what was asked for
# - the confirmation says what it is. There is no undo, and the dialog is the last place that can
#   be said; the "skip the recycle bin" checkbox must not be on it, because there is no bin to
#   skip
#
# Nothing in the fixture's counts may be deleted -- 10-scan-whole-share.sh asserts on them -- so
# this script puts its own file and its own folder on the share first, and takes back whatever is
# left of them on the way out.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# an image the fixture already has, copied into place twice. What is in it does not matter; what
# matters is that it is a medium the app lists, so it can be picked out of a grid
seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

doomed_file_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE"
doomed_folder_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_DELETE_DOOMED_FOLDER"
doomed_folder_file_on_host="$doomed_folder_on_host/$FIXTURE_DELETE_DOOMED_FOLDER_FILE"
# the one that must survive: it sits in the same folder as the file being deleted
neighbour_on_host="$seed_image"

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# a doomed.jpg left on the share would be counted by the next run of 10-scan-whole-share.sh
clean_the_share() {
    rm -f "$doomed_file_on_host"
    rm -rf "$doomed_folder_on_host"
}

trap clean_the_share EXIT
clean_the_share

step "putting a file and a folder on the share for the app to delete"
cp "$seed_image" "$doomed_file_on_host"
mkdir -p "$doomed_folder_on_host"
cp "$seed_image" "$doomed_folder_file_on_host"
note "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE and $FIXTURE_DELETE_DOOMED_FOLDER/ are on the share"

step "seeding, and scanning the share so both have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "55-menu"
if ! wait_for_log "Walked the share:" 900 "55-scan"; then
    fail "the share was never scanned, so there is nothing to delete"
    screenshot "55-no-scan"
    finish
fi

step "opening $FIXTURE_DELETE_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_DELETE_FOLDER" 60 "55-list"; then
    fail "$FIXTURE_DELETE_FOLDER is not in the folder list"
    screenshot "55-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_DELETE_FOLDER" "55-open"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "55-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

step "picking $FIXTURE_DELETE_FILE"
if ! ui_wait_text "$FIXTURE_DELETE_FILE" 60 "55-grid"; then
    fail "$FIXTURE_DELETE_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "55-no-file"
    finish
fi

if ! select_row "$FIXTURE_DELETE_FILE" "55-select"; then
    screenshot "55-not-selected"
    finish
fi

# Delete is an icon on the selection's own toolbar rather than an item in its overflow, so this
# reads the screen as it stands. uiautomator gives the icon its title as a content description,
# which is what ui.py matches on
step "what the selection's toolbar offers for a medium of the share"
selection="$(ui_dump "55-selected")"
screenshot "55-selected"
if python3 "$DRIVE_DIR/ui.py" "$selection" --text "Delete" --exact > /dev/null; then
    pass "'Delete' is offered"
else
    fail "'Delete' is not offered for a medium of the share (view tree in $selection)"
    finish
fi

step "what the confirmation says"
ui_tap_text "Delete" "55-tap-delete"
sleep 2
confirmation="$(ui_dump "55-confirmation")"
screenshot "55-confirmation"

if python3 "$DRIVE_DIR/ui.py" "$confirmation" --text "cannot be undone" > /dev/null; then
    pass "the confirmation says the delete cannot be undone"
else
    fail "the confirmation does not say the delete cannot be undone (view tree in $confirmation)"
    python3 "$DRIVE_DIR/ui.py" "$confirmation" --list | sed 's/^/     /'
fi

# there is no recycle bin on the share, so there is nothing to offer to skip. The checkbox is
# gone rather than unchecked, and a view that is gone is not in the tree at all
if python3 "$DRIVE_DIR/ui.py" "$confirmation" --resource-id "skip_the_recycle_bin_checkbox" > /dev/null; then
    fail "the confirmation offers to skip the recycle bin, and the share has none"
else
    pass "and it does not offer to skip a recycle bin the share does not have"
fi

step "deleting it"
logcat_reset
ui_tap_text "Yes" "55-confirm"

# said once per delete, after the rows have gone with the file, so the line means "it is off the
# share and the list would not show it"
if ! wait_for_log "Deleted 1 of 1 media from the share" 120 "55-delete"; then
    fail "the delete never finished"
    screenshot "55-no-delete"
    logcat_dump "55-delete" > /dev/null
    finish
fi

capture_log "55-delete"
refute_log "Deleted 0 of 1 media from the share" "the delete did not report a failure"

step "what the share has now"
if [ -f "$doomed_file_on_host" ]; then
    fail "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE is still on the share"
else
    pass "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE is gone from the share"
fi

# the check a delete that reached too far would fail: the file beside it is the fixture's own
if [ -f "$neighbour_on_host" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$neighbour_on_host is gone; the delete took the folder's other media with it"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_DELETE_FOLDER" | sed 's/^/     /'
fi

if ui_wait_text "$FIXTURE_DELETE_FILE" 10 "55-grid-after"; then
    fail "$FIXTURE_DELETE_FILE is still in the grid, though it is off the share"
    screenshot "55-still-in-grid"
else
    pass "and it is out of the grid"
fi

step "back to the folder list, to delete a whole folder of the share"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3

if ! ui_wait_exact_text "$FIXTURE_DELETE_DOOMED_FOLDER" 60 "55-folder-list"; then
    fail "$FIXTURE_DELETE_DOOMED_FOLDER is not in the folder list"
    screenshot "55-no-doomed-folder"
    finish
fi

if ! select_row "$FIXTURE_DELETE_DOOMED_FOLDER" "55-select-folder"; then
    screenshot "55-folder-not-selected"
    finish
fi

folder_selection="$(ui_dump "55-folder-selected")"
if python3 "$DRIVE_DIR/ui.py" "$folder_selection" --text "Delete" --exact > /dev/null; then
    pass "'Delete' is offered for a folder of the share"
else
    fail "'Delete' is not offered for a folder of the share (view tree in $folder_selection)"
    finish
fi

ui_tap_text "Delete" "55-tap-delete-folder"
sleep 2
screenshot "55-folder-confirmation"
logcat_reset
ui_tap_text "Yes" "55-confirm-folder"

if ! wait_for_log "Deleted 1 of 1 folders from the share" 120 "55-delete-folder"; then
    fail "the folder delete never finished"
    screenshot "55-no-folder-delete"
    logcat_dump "55-delete-folder" > /dev/null
    finish
fi

capture_log "55-delete-folder"
refute_log "Deleted 0 of 1 folders from the share" "the folder delete did not report a failure"

step "what the share has now"
if [ -d "$doomed_folder_on_host" ]; then
    fail "$FIXTURE_DELETE_DOOMED_FOLDER/ is still on the share, holding:"
    ls -l "$doomed_folder_on_host" | sed 's/^/     /'
else
    pass "$FIXTURE_DELETE_DOOMED_FOLDER/ and the medium in it are gone from the share"
fi

# the folder above them both. A recursive delete that started one level too high would take it
if [ -d "$FIXTURE_SHARE_DIR/$FIXTURE_DELETE_FOLDER" ]; then
    pass "and $FIXTURE_DELETE_FOLDER/ is still there"
else
    fail "$FIXTURE_DELETE_FOLDER/ is gone; the folder delete reached above the folder it was given"
fi

screenshot "55-done"
finish
