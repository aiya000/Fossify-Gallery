#!/usr/bin/env bash
# #28: a medium of the share can be written over, and what the share holds afterwards is either
# the old file or the new one -- never neither, and never both.
#
# This is the last thing "Save as" could not do to the share. Until now ensureWritablePath() saw
# that the name was taken and said so out loud, because SMB has no request that replaces a file:
# create() refuses a name that is taken, and truncating the file that is there would leave a torn
# medium behind if the write stopped half way. So the app stashes the original under another name
# first, writes the new content, and only then drops the stash.
#
# That dance is the reason this is driven rather than read. What can go wrong with it is not a
# wrong answer but a wrong *state left behind*, and every one of those is on the share rather
# than on the screen:
#
# - the medium is there and its content changed. The plain "it worked"
# - nothing is left under the stash name. A build that stopped between the write and the drop
#   leaves the share holding two files, one of them under a name the app invented -- and nobody
#   would think to look for it
# - the file beside it is untouched. The stash is named after its owner, so a rename that got the
#   path wrong would land on the neighbour
# - the app moved its row on with the bytes. The copies it keeps of the share are named
#   "<hash>-<size>-<modified>", so the name of the copy it asks for next says what the row holds.
#   A build that wrote the new bytes and left the row saying the old size keeps serving the old
#   picture: the rotation would be on the share and nowhere the user can see it
#
# Nothing in the fixture's counts may be written over -- 10-scan-whole-share.sh asserts on them --
# so this script brings its own file and takes it away again on the way in and on the way out.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# an image the fixture already has, copied into place. What is in it does not matter; what matters
# is that it is a medium the app lists, so it can be opened out of a grid and rotated
seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

target_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_OVERWRITE_FOLDER/$FIXTURE_OVERWRITE_FILE"
stash_on_host="$target_on_host.$FIXTURE_OVERWRITE_STASH_SUFFIX"
# the one that must come through untouched: it sits in the same folder as the medium being
# written over, and it is what a stash rename that got the path wrong would land on
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

# what the app keeps of the share under its own cache directory, which is readable on the debug
# build and nowhere else
smb_cache_listing() {
    "${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" ls "$FIXTURE_SMB_CACHE_DIR" 2> /dev/null | tr -d '\r'
}

step "putting a photo on the share for the app to write over"
cp "$seed_image" "$target_on_host"
# a few bytes past the end of the JPEG, which every decoder ignores and no checksum does. Without
# them this file is a byte-for-byte copy of its neighbour, and "the neighbour is untouched" would
# pass on a build that wrote over the neighbour instead
printf 'not the neighbour' >> "$target_on_host"
before_md5="$(md5sum < "$target_on_host")"
before_size="$(stat -c %s "$target_on_host")"
neighbour_before="$(md5sum < "$neighbour_on_host")"
note "$FIXTURE_OVERWRITE_FOLDER/$FIXTURE_OVERWRITE_FILE is $before_size bytes on the share"

step "seeding, and scanning the share so it has a row"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "85-menu"
if ! wait_for_log "Walked the share:" 900 "85-scan"; then
    fail "the share was never scanned, so there is nothing to write over"
    screenshot "85-no-scan"
    finish
fi

step "opening $FIXTURE_OVERWRITE_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_OVERWRITE_FOLDER" 60 "85-list"; then
    fail "$FIXTURE_OVERWRITE_FOLDER is not in the folder list"
    screenshot "85-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_OVERWRITE_FOLDER" "85-open-folder"
sleep 3

# the filenames, so the medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "85-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_OVERWRITE_FILE" 60 "85-grid"; then
    fail "$FIXTURE_OVERWRITE_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "85-no-file"
    finish
fi

ui_tap_text "$FIXTURE_OVERWRITE_FILE" "85-open-photo"
sleep 4
screenshot "85-viewer"

# the viewer fetches a copy of the medium to show it full screen, and that copy is named after
# what the row says. It is read now so that the same directory can be asked about afterwards
cache_before=""
for _ in $(seq 1 10); do
    cache_before="$(smb_cache_listing)"
    if [ -n "$cache_before" ]; then
        break
    fi
    sleep 2
done

if [ -n "$cache_before" ]; then
    note "the app has a copy of the share in $FIXTURE_SMB_CACHE_DIR already"
else
    note "the app has no copy of the share yet; the row will be checked on what it fetches later"
fi

step "rotating it, which is what puts Save as on the toolbar"
ui_tap_text "Rotate" "85-tap-rotate"
sleep 1
ui_tap_text "Rotate right" "85-rotate-right"
sleep 3
screenshot "85-after-rotate"

ui_tap_text "Save as" "85-tap-save-as"
sleep 2
save_as="$(ui_dump "85-save-as")"
screenshot "85-save-as"

# the dialog opens on the folder the medium is in, with the name it already has. Saving straight
# out of it is the case this script is about, so nothing here is typed
if python3 "$DRIVE_DIR/ui.py" "$save_as" --text "Network share" > /dev/null; then
    pass "the Save as dialog is offering to save back onto the share"
else
    fail "the Save as dialog does not name the network share (view tree in $save_as)"
    python3 "$DRIVE_DIR/ui.py" "$save_as" --list | sed 's/^/     /'
    finish
fi

step "what the app does about a name the share already has"
ui_tap_text "OK" "85-save-ok"
sleep 3
confirm="$(ui_dump "85-confirm")"
screenshot "85-confirm"

# The question that used to be a refusal. It is asked about rather than the refusal, because the
# refusal cannot be seen from here: it was an in-app message, which is drawn in the activity
# behind the Save as dialog, and a dump taken while a dialog is up holds the dialog's window
# alone. Seen on a build with the refusal put back: the dialog is still on screen and nothing has
# asked anything, which is what this catches
if python3 "$DRIVE_DIR/ui.py" "$confirm" --text "already exists" > /dev/null; then
    pass "the app asks whether to write over it"
else
    fail "nothing asked about the name that is taken (view tree in $confirm)"
    python3 "$DRIVE_DIR/ui.py" "$confirm" --list | sed 's/^/     /'
    finish
fi

logcat_reset
ui_tap_text "Yes" "85-confirm-yes"

if ! wait_for_log "Wrote over \"$FIXTURE_OVERWRITE_FILE\" on the share" 180 "85-overwrite"; then
    fail "the write over never finished"
    screenshot "85-no-overwrite"
    logcat_dump "85-overwrite" > /dev/null
    finish
fi

capture_log "85-overwrite"
refute_log "A write to the share failed" "the share took the write"
refute_log "could not be given its name back" "the original never had to be put back"

step "what the share has now"
if [ -f "$target_on_host" ]; then
    pass "$FIXTURE_OVERWRITE_FOLDER/$FIXTURE_OVERWRITE_FILE is still on the share"
else
    fail "$FIXTURE_OVERWRITE_FOLDER/$FIXTURE_OVERWRITE_FILE is gone; the write took the medium with it"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_OVERWRITE_FOLDER" | sed 's/^/     /'
    finish
fi

after_md5="$(md5sum < "$target_on_host")"
after_size="$(stat -c %s "$target_on_host")"
if [ "$after_md5" != "$before_md5" ]; then
    pass "and what is in it is not what was in it before"
else
    fail "the medium on the share is byte for byte what it was; nothing was written over"
fi

if [ "$after_size" -gt 0 ]; then
    pass "and it has $after_size bytes in it"
else
    fail "what is on the share is empty"
fi

# the one thing a write that stopped between the stash and the drop leaves behind
if [ -e "$stash_on_host" ]; then
    fail "$FIXTURE_OVERWRITE_FILE.$FIXTURE_OVERWRITE_STASH_SUFFIX is still on the share; the stash was never taken away"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_OVERWRITE_FOLDER" | sed 's/^/     /'
else
    pass "and nothing is left under the name the original was stashed as"
fi

if [ -f "$neighbour_on_host" ] && [ "$(md5sum < "$neighbour_on_host")" = "$neighbour_before" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$FIXTURE_COPY_SOURCE_FILE is gone or changed; the write landed on the wrong file"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_OVERWRITE_FOLDER" | sed 's/^/     /'
fi

step "and the app's row moved on with the bytes"
if [ "$after_size" = "$before_size" ]; then
    note "the medium is the same number of bytes as before, so the copy's name cannot tell the two apart; skipping"
    screenshot "85-done"
    finish
fi

# Back to the grid and in again, so that the viewer asks for a copy of the medium as it is now.
# The copy is named "<hash>-<size>-<modified>" out of the row, so what it is called says what the
# row holds -- a build that wrote the new bytes and left the row alone asks for the old name again
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3
ui_tap_text "$FIXTURE_OVERWRITE_FILE" "85-reopen"
sleep 4

cache_after=""
for _ in $(seq 1 10); do
    cache_after="$(smb_cache_listing)"
    if printf '%s\n' "$cache_after" | rg -q -- "-$after_size-"; then
        break
    fi
    sleep 2
done

if printf '%s\n' "$cache_after" | rg -q -- "-$after_size-"; then
    pass "the copy the viewer asked for is named after the $after_size bytes the share now holds"
else
    fail "the app is still asking for the medium as it was; $FIXTURE_SMB_CACHE_DIR holds:"
    printf '%s\n' "$cache_after" | sed 's/^/     /'
    screenshot "85-stale-row"
fi

if printf '%s\n' "$cache_after" | rg -q -- "-$before_size-"; then
    fail "the copy of what was there before is still in the cache"
    printf '%s\n' "$cache_after" | sed 's/^/     /'
else
    pass "and the copy of what was there before has been dropped"
fi

screenshot "85-done"
finish
