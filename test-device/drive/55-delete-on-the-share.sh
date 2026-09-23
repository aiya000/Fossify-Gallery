#!/usr/bin/env bash
# #112: a medium of the share, and a folder of the share, are deleted into the app's recycle bin
# on the share, brought back out of it, and deleted for good -- and the share is the only witness
# worth asking.
#
# A delete on the share was for good once (#28), and this script pinned that. Now the app's own
# bin is a folder on the share, ".gallery-recycle-bin" in its root, the same as on pCloud: a
# delete is a move into it, a restore a move back out, and neither moves a byte. The folder list
# shows one recycle bin for the three storages, and this script is the share's half of that.
#
# So what is driven here is the whole round trip:
#
# - the confirmation offers the bin: it says "recycle bin", and the "skip the recycle bin"
#   checkbox is on it -- the one this script once pinned as absent
# - the file really leaves its folder and turns up in the bin, under its original layout. Read
#   off fixture/share on this machine, which is the container's own directory rather than
#   anything the app believes
# - the file next to it does not move. A delete that took a folder's other media with it would
#   pass every check that only looks at what was asked for
# - a whole folder goes the same way, its medium into the bin, and the folder above it stays
# - a rescan of the share does not walk the bin: the file count is the fixture's, not the
#   fixture's plus what is in the bin
# - the bin's tile is in the folder list, on the share's own storage filter, and the medium
#   restored out of it lands back where it was
# - "Empty the recycle bin" takes the rest away from the share for good
#
# Nothing in the fixture's counts may be deleted -- 10-scan-whole-share.sh asserts on them -- so
# this script puts its own file and its own folder on the share first, and takes back whatever is
# left of them on the way out, the bin included.
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

# the bin on the share, and where each of the two lands in it: the same layout as outside it
bin_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_RECYCLE_BIN_FOLDER"
doomed_file_in_bin="$bin_on_host/$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE"
doomed_folder_file_in_bin="$bin_on_host/$FIXTURE_DELETE_DOOMED_FOLDER/$FIXTURE_DELETE_DOOMED_FOLDER_FILE"

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# a doomed.jpg left on the share would be counted by the next run of 10-scan-whole-share.sh. The
# bin is this script's too: nothing else puts anything in it
clean_the_share() {
    rm -f "$doomed_file_on_host"
    rm -rf "$doomed_folder_on_host"
    rm -rf "$bin_on_host"
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

if python3 "$DRIVE_DIR/ui.py" "$confirmation" --text "recycle bin" > /dev/null; then
    pass "the confirmation offers the recycle bin"
else
    fail "the confirmation does not mention the recycle bin (view tree in $confirmation)"
    python3 "$DRIVE_DIR/ui.py" "$confirmation" --list | sed 's/^/     /'
fi

if python3 "$DRIVE_DIR/ui.py" "$confirmation" --text "cannot be undone" > /dev/null; then
    fail "the confirmation still says the delete cannot be undone"
else
    pass "and it no longer says the delete cannot be undone"
fi

# the share has a bin now, so there is one to offer to skip, the same as on the device (56)
if python3 "$DRIVE_DIR/ui.py" "$confirmation" --resource-id "skip_the_recycle_bin_checkbox" > /dev/null; then
    pass "and it offers to skip it"
else
    fail "the confirmation has no 'skip the recycle bin' checkbox, and the share has a bin"
fi

step "deleting it into the bin"
logcat_reset
ui_tap_text "Yes" "55-confirm"

# said once per delete, after the rows have moved with the file, so the line means "it is in
# the bin and the folder would not show it"
if ! wait_for_log "Moved 1 of 1 media into the share's recycle bin" 120 "55-delete"; then
    fail "the delete never finished"
    screenshot "55-no-delete"
    logcat_dump "55-delete" > /dev/null
    finish
fi

capture_log "55-delete"
refute_log "Moved 0 of 1 media into the share's recycle bin" "the delete did not report a failure"
refute_log "Deleted 1 of 1 media from the share" "and nothing was deleted from the share for good"

step "what the share has now"
if [ -f "$doomed_file_on_host" ]; then
    fail "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE is still in its folder on the share"
else
    pass "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE is gone from its folder on the share"
fi

if [ -f "$doomed_file_in_bin" ]; then
    pass "and it is in the share's recycle bin, under $FIXTURE_RECYCLE_BIN_FOLDER/$FIXTURE_DELETE_FOLDER/"
else
    fail "$FIXTURE_DELETE_FILE is not at $doomed_file_in_bin; the bin holds:"
    find "$bin_on_host" -type f 2>/dev/null | sed 's/^/     /'
fi

# the check a delete that reached too far would fail: the file beside it is the fixture's own
if [ -f "$neighbour_on_host" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$neighbour_on_host is gone; the delete took the folder's other media with it"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_DELETE_FOLDER" | sed 's/^/     /'
fi

if ui_wait_text "$FIXTURE_DELETE_FILE" 10 "55-grid-after"; then
    fail "$FIXTURE_DELETE_FILE is still in the grid, though it is in the bin"
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
folder_confirmation="$(ui_dump "55-folder-confirmation")"
screenshot "55-folder-confirmation"
if python3 "$DRIVE_DIR/ui.py" "$folder_confirmation" --text "recycle bin" > /dev/null; then
    pass "the folder's confirmation offers the recycle bin"
else
    fail "the folder's confirmation does not mention the recycle bin (view tree in $folder_confirmation)"
    python3 "$DRIVE_DIR/ui.py" "$folder_confirmation" --list | sed 's/^/     /'
fi

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
expect_log "Moved 1 of 1 media into the share's recycle bin" "the medium under it went into the bin first"

step "what the share has now"
if [ -d "$doomed_folder_on_host" ]; then
    fail "$FIXTURE_DELETE_DOOMED_FOLDER/ is still on the share, holding:"
    ls -l "$doomed_folder_on_host" | sed 's/^/     /'
else
    pass "$FIXTURE_DELETE_DOOMED_FOLDER/ is gone from the share"
fi

if [ -f "$doomed_folder_file_in_bin" ]; then
    pass "and the medium in it is in the share's recycle bin, under $FIXTURE_RECYCLE_BIN_FOLDER/$FIXTURE_DELETE_DOOMED_FOLDER/"
else
    fail "$FIXTURE_DELETE_DOOMED_FOLDER_FILE is not at $doomed_folder_file_in_bin; the bin holds:"
    find "$bin_on_host" -type f 2>/dev/null | sed 's/^/     /'
fi

# the folder above them both. A recursive delete that started one level too high would take it
if [ -d "$FIXTURE_SHARE_DIR/$FIXTURE_DELETE_FOLDER" ]; then
    pass "and $FIXTURE_DELETE_FOLDER/ is still there"
else
    fail "$FIXTURE_DELETE_FOLDER/ is gone; the folder delete reached above the folder it was given"
fi

# the rows were marked deleted, which is what puts the bin's tile in the list -- on the share's
# own filter, since the one bin belongs to no one storage
step "the recycle bin in the folder list"
if ui_wait_exact_text "Recycle bin" 30 "55-bin-tile"; then
    pass "the recycle bin is in the folder list, with what it holds"
else
    fail "the folder list has no recycle bin tile, though two media of the share went into it"
    screenshot "55-no-bin-tile"
fi

step "rescanning the share, which must not walk the bin"
logcat_reset
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "55-rescan-menu"
if ! wait_for_log "Walked the share:" 900 "55-rescan"; then
    fail "the share was never scanned again"
    screenshot "55-no-rescan"
    finish
fi

capture_log "55-rescan"
# the fixture's own count: the two media in the bin are not in it, and neither is the bin
expect_log "Walked the share: [0-9]+ folders, $FIXTURE_MEDIA files" "the scan counted the fixture's $FIXTURE_MEDIA files and not the two in the bin"

if ui_wait_exact_text "Recycle bin" 30 "55-bin-tile-after-rescan"; then
    pass "and the recycle bin is still in the folder list afterwards"
else
    fail "the rescan took the recycle bin out of the folder list"
    screenshot "55-no-bin-tile-after-rescan"
    finish
fi

step "opening the recycle bin, and restoring $FIXTURE_DELETE_FILE"
ui_tap_exact_text "Recycle bin" "55-open-bin"
sleep 3

# the filenames are still on: the toggle is one setting for every grid, and it was turned on in
# Screens above. Pressing it here again would turn them off
if ! ui_wait_text "$FIXTURE_DELETE_FILE" 60 "55-bin-grid"; then
    fail "$FIXTURE_DELETE_FILE is not in the recycle bin's grid"
    screenshot "55-not-in-bin"
    finish
fi

if ui_wait_text "$FIXTURE_DELETE_DOOMED_FOLDER_FILE" 10 "55-bin-grid-folder-file"; then
    pass "both media of the share are in the recycle bin's grid"
else
    fail "$FIXTURE_DELETE_DOOMED_FOLDER_FILE is not in the recycle bin's grid"
fi

if ! select_row "$FIXTURE_DELETE_FILE" "55-select-in-bin"; then
    screenshot "55-not-selected-in-bin"
    finish
fi

if ! tap_action "Restore selected files" "55-restore"; then
    screenshot "55-no-restore"
    finish
fi

sleep 2
restore_dialog="$(ui_dump "55-restore-dialog")"
screenshot "55-restore-dialog"
if python3 "$DRIVE_DIR/ui.py" "$restore_dialog" --text "Restore to /$FIXTURE_DELETE_FOLDER" > /dev/null; then
    pass "the dialog says it goes back to /$FIXTURE_DELETE_FOLDER"
else
    fail "the dialog does not name /$FIXTURE_DELETE_FOLDER as the destination (view tree in $restore_dialog)"
    python3 "$DRIVE_DIR/ui.py" "$restore_dialog" --list | sed 's/^/     /'
fi

logcat_reset
ui_tap_exact_text "Restore" "55-confirm-restore"

if ! wait_for_log "Restored 1 of 1 media from the share's recycle bin" 120 "55-restore"; then
    fail "the restore never finished"
    screenshot "55-no-restore-done"
    logcat_dump "55-restore" > /dev/null
    finish
fi

step "what the share has now"
if [ -f "$doomed_file_on_host" ]; then
    pass "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE is back in its folder on the share"
else
    fail "$FIXTURE_DELETE_FOLDER/$FIXTURE_DELETE_FILE did not come back to its folder"
fi

if [ -e "$doomed_file_in_bin" ]; then
    fail "$FIXTURE_DELETE_FILE is still in the bin as well"
else
    pass "and it is out of the bin"
fi

# the layout the bin keeps is made as media go in and taken away as they leave
if [ -d "$bin_on_host/$FIXTURE_DELETE_FOLDER" ]; then
    fail "the bin still holds an empty $FIXTURE_DELETE_FOLDER/ folder"
else
    pass "and the folder it had in the bin went with it"
fi

if cmp -s "$doomed_file_on_host" "$seed_image"; then
    pass "and its content is what it was"
else
    fail "$doomed_file_on_host differs from what was put on the share"
fi

step "emptying the recycle bin"
if ! tap_action "Empty the recycle bin" "55-empty"; then
    screenshot "55-no-empty"
    finish
fi

sleep 2
screenshot "55-empty-confirmation"
logcat_reset
ui_tap_text "Yes" "55-confirm-empty"

if ! wait_for_log "Deleted 1 of 1 media from the share's recycle bin" 120 "55-empty"; then
    fail "emptying the bin never finished on the share"
    screenshot "55-no-empty-done"
    logcat_dump "55-empty" > /dev/null
    finish
fi

step "what the share has now"
left_in_bin="$(find "$bin_on_host" -type f 2>/dev/null | wc -l)"
if [ "$left_in_bin" -eq 0 ]; then
    pass "nothing is left in the share's recycle bin"
else
    fail "the share's recycle bin still holds $left_in_bin files:"
    find "$bin_on_host" -type f | sed 's/^/     /'
fi

if [ -f "$doomed_file_on_host" ]; then
    pass "and the restored $FIXTURE_DELETE_FILE was left alone"
else
    fail "emptying the bin took the restored $FIXTURE_DELETE_FILE with it"
fi

if ui_wait_exact_text "Recycle bin" 20 "55-bin-gone"; then
    fail "the recycle bin is still in the folder list, though it is empty"
    screenshot "55-bin-tile-stays"
else
    pass "and the recycle bin is out of the folder list"
fi

screenshot "55-done"
finish
