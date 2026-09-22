#!/usr/bin/env bash
# #28: a medium of the share, and a folder of the share, can be given another name -- and the
# share is the only witness worth asking.
#
# A rename is the first write that changes something on the share without adding or removing a
# file, and it is the one whose failures are quiet. A copy that goes wrong leaves a file behind; a
# delete that goes wrong is noticed at once. A rename that goes wrong renames the wrong thing, or
# renames over something, and the share afterwards looks like a share somebody tidied.
#
# So what is driven here is:
#
# - the file really changes name on the share. Read off fixture/share on this machine, which is
#   the container's own directory rather than anything the app believes
# - the file next to it does not, and neither does its content. A rename that landed on the
#   neighbour, or over it, would pass every check that only looks at the name that was asked for
# - a name the share already has is refused rather than written over. There is no recycle bin on
#   the share to take the overwritten file back out of, so this is the check that matters most
# - the folder, and the media under it, travel together
#
# Nothing in the fixture's counts may be renamed -- 10-scan-whole-share.sh asserts on them -- so
# this script puts its own file and its own folder on the share first, and takes back whatever is
# left of them on the way out, under either name.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# an image the fixture already has, copied into place. What is in it does not matter; what matters
# is that it is a medium the app lists, so it can be picked out of a grid
seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

before_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_FOLDER/$FIXTURE_RENAME_FILE"
after_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_FOLDER/$FIXTURE_RENAME_NEW_FILE"
folder_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_DIR"
renamed_folder_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_NEW_DIR"
# the one that must come through untouched: it sits in the same folder, and it is also the name
# the collision step tries to rename over
neighbour_on_host="$seed_image"
neighbour_before=""

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# a file of this script's left on the share would be counted by the next run of
# 10-scan-whole-share.sh
clean_the_share() {
    rm -f "$before_on_host" "$after_on_host"
    rm -rf "$folder_on_host" "$renamed_folder_on_host"
}

trap clean_the_share EXIT
clean_the_share

step "putting a file and a folder on the share for the app to rename"
cp "$seed_image" "$before_on_host"
# a few bytes past the end of the JPEG, which every decoder ignores and no checksum does.
# Without them this file is a byte-for-byte copy of the one the collision step tries to rename
# over, and "the neighbour was not written over" would pass on a build that wrote over it
printf 'not the neighbour' >> "$before_on_host"
mkdir -p "$folder_on_host"
cp "$seed_image" "$folder_on_host/$FIXTURE_RENAME_DIR_FILE"
# what the neighbour holds now, so that "it was not written over" can be said about its content
# and not only about its name
neighbour_before="$(md5sum < "$neighbour_on_host")"
note "$FIXTURE_RENAME_FOLDER/$FIXTURE_RENAME_FILE and $FIXTURE_RENAME_DIR/ are on the share"

step "seeding, and scanning the share so both have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "65-menu"
if ! wait_for_log "Walked the share:" 900 "65-scan"; then
    fail "the share was never scanned, so there is nothing to rename"
    screenshot "65-no-scan"
    finish
fi

step "opening $FIXTURE_RENAME_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_RENAME_FOLDER" 60 "65-list"; then
    fail "$FIXTURE_RENAME_FOLDER is not in the folder list"
    screenshot "65-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_RENAME_FOLDER" "65-open"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it happens to be drawn --
# and so that the name it ends up with can be read off the grid afterwards
dump="$(ui_dump "65-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

step "picking $FIXTURE_RENAME_FILE"
if ! ui_wait_text "$FIXTURE_RENAME_FILE" 60 "65-grid"; then
    fail "$FIXTURE_RENAME_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "65-no-file"
    finish
fi

if ! select_row "$FIXTURE_RENAME_FILE" "65-select"; then
    screenshot "65-not-selected"
    finish
fi

# Rename is showAsAction="never", so it is always in the selection's overflow rather than on its
# toolbar. It is opened rather than tapped straight through, because what is on that menu is half
# of what this script is about: until now the share was offered no rename at all
step "what the selection's overflow offers for a medium of the share"
open_overflow_menu
sleep 1
selection="$(ui_dump "65-selected")"
screenshot "65-selected"
if python3 "$DRIVE_DIR/ui.py" "$selection" --text "Rename" --exact > /dev/null; then
    pass "'Rename' is offered for a medium of the share"
else
    fail "'Rename' is not offered for a medium of the share (view tree in $selection)"
    python3 "$DRIVE_DIR/ui.py" "$selection" --list | sed 's/^/     /'
    finish
fi

step "trying to rename it to a name the share already has"
ui_tap_text "Rename" "65-tap-rename-collision"
sleep 2
dialog="$(ui_dump "65-collision-dialog")"
if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "$FIXTURE_RENAME_FILE" > /dev/null; then
    pass "the dialog opens with the name it has now"
else
    fail "the rename dialog does not show $FIXTURE_RENAME_FILE (view tree in $dialog)"
fi

logcat_reset
replace_text_field "$FIXTURE_COPY_SOURCE_FILE" "${#FIXTURE_RENAME_FILE}"
screenshot "65-collision-typed"
ui_tap_text "OK" "65-collision-ok"

if ! wait_for_log "A write to the share failed" 60 "65-collision"; then
    fail "the share took a name it already had, or the refusal was never reported"
    screenshot "65-no-collision"
fi

capture_log "65-collision"
refute_log "Renamed a medium on the share" "nothing was renamed"

# the point of the whole step: what was already there is still there, with its own bytes
if [ -f "$neighbour_on_host" ] && [ "$(md5sum < "$neighbour_on_host")" = "$neighbour_before" ]; then
    pass "$FIXTURE_COPY_SOURCE_FILE is untouched; the share did not write over it"
else
    fail "$FIXTURE_COPY_SOURCE_FILE was written over by the refused rename"
fi

if [ -f "$before_on_host" ]; then
    pass "and $FIXTURE_RENAME_FILE still has its own name"
else
    fail "$FIXTURE_RENAME_FILE is gone from the share, though the rename was refused"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_FOLDER" | sed 's/^/     /'
fi

step "renaming it to a free name"
# the refusal left the selection as it was, so the menu is reached the same way again
if ! in_selection_mode "65-still-selected"; then
    if ! select_row "$FIXTURE_RENAME_FILE" "65-reselect"; then
        screenshot "65-not-reselected"
        finish
    fi
fi

open_overflow_menu
sleep 1
ui_tap_text "Rename" "65-tap-rename"
sleep 2
logcat_reset
replace_text_field "$FIXTURE_RENAME_NEW_FILE" "${#FIXTURE_RENAME_FILE}"
screenshot "65-typed"
ui_tap_text "OK" "65-ok"

if ! wait_for_log "Renamed a medium on the share" 120 "65-rename"; then
    fail "the rename never finished"
    screenshot "65-no-rename"
    logcat_dump "65-rename" > /dev/null
    finish
fi

capture_log "65-rename"

step "what the share has now"
if [ -f "$after_on_host" ]; then
    pass "$FIXTURE_RENAME_FOLDER/$FIXTURE_RENAME_NEW_FILE is on the share"
else
    fail "$FIXTURE_RENAME_FOLDER/$FIXTURE_RENAME_NEW_FILE is not on the share"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_FOLDER" | sed 's/^/     /'
fi

if [ -f "$before_on_host" ]; then
    fail "$FIXTURE_RENAME_FILE is still there too; the file was copied rather than renamed"
else
    pass "and the name it had is gone"
fi

# the check a rename that reached the wrong row would fail
if [ -f "$neighbour_on_host" ] && [ "$(md5sum < "$neighbour_on_host")" = "$neighbour_before" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$neighbour_on_host is gone or changed; the rename landed on the wrong file"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_RENAME_FOLDER" | sed 's/^/     /'
fi

if ui_wait_text "$FIXTURE_RENAME_NEW_FILE" 30 "65-grid-after"; then
    pass "and the grid shows the new name"
else
    fail "the grid does not show $FIXTURE_RENAME_NEW_FILE, though the share has it"
    screenshot "65-grid-stale"
fi

step "back to the folder list, to rename a whole folder of the share"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3

if ! ui_wait_exact_text "$FIXTURE_RENAME_DIR" 60 "65-folder-list"; then
    fail "$FIXTURE_RENAME_DIR is not in the folder list"
    screenshot "65-no-renameable-folder"
    finish
fi

if ! select_row "$FIXTURE_RENAME_DIR" "65-select-folder"; then
    screenshot "65-folder-not-selected"
    finish
fi

open_overflow_menu
sleep 1
folder_selection="$(ui_dump "65-folder-selected")"
if python3 "$DRIVE_DIR/ui.py" "$folder_selection" --text "Rename" --exact > /dev/null; then
    pass "'Rename' is offered for a folder of the share"
else
    fail "'Rename' is not offered for a folder of the share (view tree in $folder_selection)"
    python3 "$DRIVE_DIR/ui.py" "$folder_selection" --list | sed 's/^/     /'
    finish
fi

ui_tap_text "Rename" "65-tap-rename-folder"
sleep 2
logcat_reset
replace_text_field "$FIXTURE_RENAME_NEW_DIR" "${#FIXTURE_RENAME_DIR}"
screenshot "65-folder-typed"
ui_tap_text "OK" "65-folder-ok"

if ! wait_for_log "Renamed a folder on the share" 120 "65-rename-folder"; then
    fail "the folder rename never finished"
    screenshot "65-no-folder-rename"
    logcat_dump "65-rename-folder" > /dev/null
    finish
fi

capture_log "65-rename-folder"
# the media under it are the reason the rows have to follow, and the line says how many were found
expect_log "Renamed a folder on the share to \"$FIXTURE_RENAME_NEW_DIR\", with 1 media under it" \
    "the medium under the folder was carried with it"

step "what the share has now"
if [ -d "$renamed_folder_on_host" ]; then
    pass "$FIXTURE_RENAME_NEW_DIR/ is on the share"
else
    fail "$FIXTURE_RENAME_NEW_DIR/ is not on the share"
    ls -l "$FIXTURE_SHARE_DIR" | sed 's/^/     /'
    finish
fi

if [ -d "$folder_on_host" ]; then
    fail "$FIXTURE_RENAME_DIR/ is still there too; the folder was copied rather than renamed"
else
    pass "and the name it had is gone"
fi

# the folder travelled with what was in it. A rename that made an empty folder under the new name
# would pass every check above this one
if [ -f "$renamed_folder_on_host/$FIXTURE_RENAME_DIR_FILE" ]; then
    pass "and $FIXTURE_RENAME_DIR_FILE went with it"
else
    fail "$FIXTURE_RENAME_NEW_DIR/ is empty; the medium under the folder was left behind"
    ls -l "$renamed_folder_on_host" | sed 's/^/     /'
fi

if ui_wait_exact_text "$FIXTURE_RENAME_NEW_DIR" 30 "65-folder-list-after"; then
    pass "and the folder list shows the new name"
else
    fail "the folder list does not show $FIXTURE_RENAME_NEW_DIR, though the share has it"
    screenshot "65-folder-list-stale"
fi

screenshot "65-done"
finish
