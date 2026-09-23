#!/usr/bin/env bash
# #103: the pencil on a medium of the share opens the editor on the photo, and saving writes the
# result back onto the share.
#
# It used to open the editor on nothing. A medium of the share has no file on this device -- its
# path is a pseudo path, "smb:/Screens/a.jpg" -- and the editor is handed a path and reads it with
# File(). So the canvas came up empty, "Save as" offered a folder that exists nowhere
# ("ルート/smb:/Screens/"), and the save threw. The fix is the road pCloud has had since #66: the
# medium is fetched into a copy of this app's own, the editor is pointed at the copy, and what
# comes back is written over the medium on the share.
#
# Two halves, and each one fails in a way the other cannot see:
#
# - the way in. What says the editor got a real file is the pixels: the view tree is the same
#   either way -- the canvas is there, it is simply drawing nothing -- so a screenshot of it is
#   read rather than the tree. A build handed the pseudo path draws one flat colour there
# - the way back. What says the share took the edit is the share: the medium's bytes changed, and
#   nothing is left under the name the original was stashed as while the new content went up
#
# The editor's "Save as" is checked to be offered as well. It was hidden for a copy at first,
# since saving the copy under another name would have left the edit in this app's cache; now it
# asks where to save on every storage and sends the edit there (#105), which
# 98-save-as-out-of-the-editor.sh drives. What this script drives is the other save, the one
# that goes back over the original.
#
# Nothing in the fixture's counts may be edited -- 10-scan-whole-share.sh asserts on them -- so
# this script brings its own file and takes it away again on the way in and on the way out.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# an image the fixture already has, copied into place. What is in it does matter a little here:
# the way-in check asks whether the canvas is drawing a picture, and the fixture's images are test
# patterns -- many colours, which is what tells a drawn canvas from an empty one
seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

target_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_EDIT_FOLDER/$FIXTURE_EDIT_FILE"
stash_on_host="$target_on_host.$FIXTURE_OVERWRITE_STASH_SUFFIX"
# the one that must come through untouched: it sits in the same folder as the medium being
# edited, and it is what a stash rename that got the path wrong would land on
neighbour_on_host="$seed_image"
neighbour_before=""

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# a file of this script's left on the share would be counted by the next run of
# 10-scan-whole-share.sh
clean_the_share() {
    rm -f "$target_on_host" "$stash_on_host"
}

trap clean_the_share EXIT
clean_the_share

step "putting a photo on the share for the app to edit"
cp "$seed_image" "$target_on_host"
# a few bytes past the end of the JPEG, which every decoder ignores and no checksum does. Without
# them this file is a byte-for-byte copy of its neighbour, and "the neighbour is untouched" would
# pass on a build that wrote over the neighbour instead
printf 'not the neighbour' >> "$target_on_host"
before_md5="$(md5sum < "$target_on_host")"
before_size="$(stat -c %s "$target_on_host")"
neighbour_before="$(md5sum < "$neighbour_on_host")"
note "$FIXTURE_EDIT_FOLDER/$FIXTURE_EDIT_FILE is $before_size bytes on the share"

step "seeding, and scanning the share so it has a row"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "97-menu"
if ! wait_for_log "Walked the share:" 900 "97-scan"; then
    fail "the share was never scanned, so there is nothing to edit"
    screenshot "97-no-scan"
    finish
fi

step "opening $FIXTURE_EDIT_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_EDIT_FOLDER" 60 "97-list"; then
    fail "$FIXTURE_EDIT_FOLDER is not in the folder list"
    screenshot "97-no-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_EDIT_FOLDER" "97-open-folder"
sleep 3

# the filenames, so the medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "97-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_EDIT_FILE" 60 "97-grid"; then
    fail "$FIXTURE_EDIT_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "97-no-file"
    finish
fi

ui_tap_text "$FIXTURE_EDIT_FILE" "97-open-photo"
sleep 4
screenshot "97-viewer"

step "the pencil, and what the editor opens on"
logcat_reset
tap_action "Edit" "97-edit" || finish

# The system must not stand between the two screens here.
#
# An ACTION_EDIT intent is answered by whatever editor the device has -- this emulator carries
# Markup and Photos besides the app's own -- and the list it puts up costs the edit: the intent
# carries FLAG_ACTIVITY_NEW_TASK, so the request is answered with "cancelled" the moment the list
# appears, and the screen waiting to put the edit back onto the share lets the copy go before the
# user has drawn a line. A medium of the share is therefore handed to this app's own editor by
# name, and the list appearing at all is this check failing
sleep 3
chooser="$(ui_dump "97-chooser")"
if python3 "$DRIVE_DIR/ui.py" "$chooser" --text "Just once" --exact > /dev/null; then
    fail "the system asked which editor to use; what the editor saves would never reach the share"
    python3 "$DRIVE_DIR/ui.py" "$chooser" --list | sed 's/^/     /'
    screenshot "97-chooser"
    finish
fi

pass "the app opened its own editor rather than asking which one to use"

# the medium is fetched off the share before the editor is started, so this waits rather than
# sleeps: a share on a slow link takes as long as it takes
if ! ui_wait_text "Overwrite original" 90 "97-editor-wait"; then
    # the title is in the overflow when the toolbar has no room; either way the editor is up once
    # the canvas is in the tree
    if ! ui_wait_text "Transform" 30 "97-editor-wait-2"; then
        fail "the editor never came up after the pencil"
        screenshot "97-no-editor"
        logcat_dump "97-editor" > /dev/null
        finish
    fi
fi

sleep 2
editor="$(ui_dump "97-editor")"
screenshot "97-editor"

# The pixels, not the tree. A canvas handed a path this device does not have is still a canvas:
# it is in the tree, the right size, drawing nothing. Seen on a build without the fix, which is
# the whole reason this check reads a screenshot.
#
# Which of the two canvases is the one on screen depends on the mode the editor opens in -- the
# crop view while it is transforming, the plain one while it is filtering -- so both are asked for
canvas=""
for view in crop_image_view default_image_view; do
    if canvas="$(python3 "$DRIVE_DIR/ui.py" "$editor" --resource-id "$view" --bounds)"; then
        note "the editor is drawing into $view"
        break
    fi
    canvas=""
done

if [ -z "$canvas" ]; then
    fail "the editor has no canvas in it (view tree in $editor)"
    python3 "$DRIVE_DIR/ui.py" "$editor" --list | sed 's/^/     /'
    finish
fi

# shellcheck disable=SC2086
if python3 "$DRIVE_DIR/thumbs.py" "$RUN_DIR/97-editor.png" --region $canvas; then
    pass "the editor opened on the photo"
else
    fail "the editor's canvas is drawing nothing; it was handed the medium's path rather than a copy"
    note "the screenshot is $RUN_DIR/97-editor.png, the canvas is at $canvas"
    finish
fi

step "turning it, so that there is an edit to write back"
# the editor opens on whichever mode it was left in, so the transform row may already be there
if ! python3 "$DRIVE_DIR/ui.py" "$editor" --text "Rotate" --exact > /dev/null; then
    ui_tap_exact_text "Transform" "97-transform" || finish
    sleep 2
fi

ui_tap_exact_text "Rotate" "97-rotate" || finish
sleep 2
screenshot "97-after-rotate"

step "saving, which is the only save a medium of the share has"
open_overflow_menu
sleep 1
menu="$(ui_dump "97-save-menu")"
screenshot "97-save-menu"

if python3 "$DRIVE_DIR/ui.py" "$menu" --text "Overwrite original" --exact > /dev/null; then
    pass "the editor offers to write the medium of the share over itself"
else
    fail "nothing in the editor's menu writes back over the original (view tree in $menu)"
    python3 "$DRIVE_DIR/ui.py" "$menu" --list | sed 's/^/     /'
    finish
fi

# "Save as" is offered too (#105): it asks where to save, on every storage, and sends the edit
# there rather than leaving it in the cache the copy lives in. 98-save-as-out-of-the-editor.sh
# drives that way out; here it only has to be offered. It is `ifRoom` with an icon, so it is the
# tick on the toolbar when there is room and a line in the overflow when there is not -- the
# editor's dump from before the rotation holds the toolbar, the menu's dump holds the overflow
if python3 "$DRIVE_DIR/ui.py" "$editor" --text "Save as" --exact > /dev/null || python3 "$DRIVE_DIR/ui.py" "$menu" --text "Save as" --exact > /dev/null; then
    pass "and it offers to save the edit under another name, somewhere else"
else
    fail "the editor does not offer Save as for a medium of the share, on the toolbar or in its overflow (view trees in $editor and $menu)"
fi

logcat_reset
ui_tap_text "Overwrite original" "97-overwrite" || finish

if ! wait_for_log "Wrote over \"$FIXTURE_EDIT_FILE\" on the share" 180 "97-write-back"; then
    fail "the edit was never written back onto the share"
    screenshot "97-no-write-back"
    logcat_dump "97-write-back" > /dev/null
    finish
fi

capture_log "97-write-back"
refute_log "A write to the share failed" "the share took the write"
refute_log "could not be given its name back" "the original never had to be put back"
refute_log "The editor reported a save but left" "the editor wrote the copy it was handed"

step "what the share has now"
if [ -f "$target_on_host" ]; then
    pass "$FIXTURE_EDIT_FOLDER/$FIXTURE_EDIT_FILE is still on the share"
else
    fail "$FIXTURE_EDIT_FOLDER/$FIXTURE_EDIT_FILE is gone; the write took the medium with it"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_EDIT_FOLDER" | sed 's/^/     /'
    finish
fi

after_md5="$(md5sum < "$target_on_host")"
after_size="$(stat -c %s "$target_on_host")"
if [ "$after_md5" != "$before_md5" ]; then
    pass "and what is in it is the edit, not what was in it before"
else
    fail "the medium on the share is byte for byte what it was; the edit stayed on this device"
fi

if [ "$after_size" -gt 0 ]; then
    pass "and it has $after_size bytes in it"
else
    fail "what is on the share is empty"
fi

# the one thing a write that stopped between the stash and the drop leaves behind
if [ -e "$stash_on_host" ]; then
    fail "$FIXTURE_EDIT_FILE.$FIXTURE_OVERWRITE_STASH_SUFFIX is still on the share; the stash was never taken away"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_EDIT_FOLDER" | sed 's/^/     /'
else
    pass "and nothing is left under the name the original was stashed as"
fi

if [ -f "$neighbour_on_host" ] && [ "$(md5sum < "$neighbour_on_host")" = "$neighbour_before" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$FIXTURE_COPY_SOURCE_FILE is gone or changed; the write landed on the wrong file"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_EDIT_FOLDER" | sed 's/^/     /'
fi

screenshot "97-done"
finish
