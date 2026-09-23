#!/usr/bin/env bash
# #107: the last two things the grid's selection could do to a photo of this device and not to
# one of the share -- turn it where it lies, and shrink several at once -- go the same way on
# the share now: each photo is fetched into a copy of its own, changed there, and written back
# over itself through the stash and replace an overwrite uses.
#
# Both used to be kept off the selection's menu for the share, and "resize several" for pCloud
# as well, with a comment saying the write back was not built. It was, by #99; the menu was
# never told.
#
# Worth driving rather than reading, because the witness is the share. A JPEG is turned the
# way the device turns one: by its EXIF orientation tag, the pixels left as they are, so a
# photo turned right is one whose tag reads 6, read with Pillow off fixture/share. A photo
# shrunk by three quarters is one three quarters as wide in pixels, read with ffprobe. Nothing
# is left under the stash name, and the photo beside them is untouched.
#
# Nothing in the fixture's counts may be written over -- 10-scan-whole-share.sh asserts on them
# -- so this script brings its own two files and takes them away again on the way in and out.
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

turned_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_IN_PLACE_FOLDER/$FIXTURE_IN_PLACE_FILE"
other_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_IN_PLACE_FOLDER/$FIXTURE_IN_PLACE_OTHER_FILE"
neighbour_on_host="$seed_image"

clean_the_share() {
    rm -f "$turned_on_host" "$turned_on_host.$FIXTURE_OVERWRITE_STASH_SUFFIX" \
        "$other_on_host" "$other_on_host.$FIXTURE_OVERWRITE_STASH_SUFFIX"
}

trap clean_the_share EXIT
clean_the_share

image_width() {
    ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$1"
}

# the EXIF orientation tag, 1 when there is none: 6 is "turned right", which is what the app
# writes for a JPEG instead of turning its pixels
exif_orientation() {
    python3 - "$1" << 'EOF'
import sys
from PIL import Image
print(Image.open(sys.argv[1]).getexif().get(0x0112, 1))
EOF
}

# the share must hold either the old photo or the new one, and nothing under the stash name
check_stash_gone() {
    local file="$1"
    if [ -e "$file.$FIXTURE_OVERWRITE_STASH_SUFFIX" ]; then
        fail "$(basename "$file").$FIXTURE_OVERWRITE_STASH_SUFFIX is still on the share; the stash was never taken away"
    else
        pass "nothing is left under the name $(basename "$file") was stashed as"
    fi
}

step "putting two photos on the share for the app to write over"
cp "$seed_image" "$turned_on_host"
printf 'not the neighbour' >> "$turned_on_host"
cp "$seed_image" "$other_on_host"
printf 'not the neighbour either' >> "$other_on_host"
orientation_before="$(exif_orientation "$turned_on_host")"
width_before="$(image_width "$other_on_host")"
neighbour_before="$(md5sum < "$neighbour_on_host")"
note "$FIXTURE_IN_PLACE_FILE has orientation $orientation_before, $FIXTURE_IN_PLACE_OTHER_FILE is $width_before across"
if [ "$orientation_before" = "6" ]; then
    fail "the seed photo is already turned right; nothing here could tell a turn from no turn"
    finish
fi

step "seeding, and scanning the share so they have rows"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "86-menu"
if ! wait_for_log "Walked the share:" 900 "86-scan"; then
    fail "the share was never scanned, so there is nothing to turn"
    screenshot "86-no-scan"
    finish
fi

step "opening $FIXTURE_IN_PLACE_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_IN_PLACE_FOLDER" 60 "86-list"; then
    fail "$FIXTURE_IN_PLACE_FOLDER is not in the folder list"
    screenshot "86-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_IN_PLACE_FOLDER" "86-open-folder"
sleep 3

dump="$(ui_dump "86-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_IN_PLACE_FILE" 60 "86-grid"; then
    fail "$FIXTURE_IN_PLACE_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "86-no-file"
    finish
fi

step "turning one of them where it lies"
if ! select_row "$FIXTURE_IN_PLACE_FILE" "86-select-one"; then
    screenshot "86-not-selected"
    finish
fi

# Rotate is `ifRoom` with an icon on the selection's toolbar, its directions a submenu under it
logcat_reset
tap_action "Rotate" "86-rotate" || finish
sleep 1
ui_tap_exact_text "Rotate right" "86-rotate-right" || finish

if ! wait_for_log "Wrote over \"$FIXTURE_IN_PLACE_FILE\" on the share" 180 "86-rotate-write"; then
    fail "the turned photo was never written back onto the share"
    screenshot "86-no-rotate-write"
    logcat_dump "86-rotate-write" > /dev/null
    finish
fi

sleep 2
capture_log "86-rotate-write"
refute_log "A write to the share failed" "the share took the write"
refute_log "could not be given its name back" "the original never had to be put back"

orientation_after="$(exif_orientation "$turned_on_host")"
if [ "$orientation_after" = "6" ]; then
    pass "$FIXTURE_IN_PLACE_FILE is turned right now, by its orientation tag"
else
    fail "$FIXTURE_IN_PLACE_FILE has orientation $orientation_after; 6 was expected after a turn to the right"
fi

check_stash_gone "$turned_on_host"

step "shrinking both of them at once, where they lie"
if ! ui_wait_text "$FIXTURE_IN_PLACE_OTHER_FILE" 30 "86-grid-again"; then
    fail "the grid did not come back after the rotation"
    screenshot "86-no-grid-again"
    finish
fi

if ! in_selection_mode "86-still-selected"; then
    if ! select_row "$FIXTURE_IN_PLACE_FILE" "86-select-again"; then
        screenshot "86-not-selected-again"
        finish
    fi
fi

ui_tap_text "$FIXTURE_IN_PLACE_OTHER_FILE" "86-add-other" || finish
sleep 1

open_overflow_menu
sleep 1
menu="$(ui_dump "86-selection-menu")"
if python3 "$DRIVE_DIR/ui.py" "$menu" --text "Resize" --exact > /dev/null; then
    pass "a selection of two photos of the share is offered Resize"
else
    fail "a selection of two photos of the share is not offered Resize (view tree in $menu)"
    python3 "$DRIVE_DIR/ui.py" "$menu" --list | sed 's/^/     /'
    finish
fi

ui_tap_exact_text "Resize" "86-tap-resize" || finish
sleep 4
resize="$(ui_dump "86-resize-dialog")"
screenshot "86-resize-dialog"
# the dialog opens with 75 per cent filled in, which is the factor this script counts on
logcat_reset
ui_tap_exact_text "OK" "86-resize-ok" || finish

# one "wrote over" line per photo; the second is the one to wait for
if ! wait_for_log "Wrote over \"$FIXTURE_IN_PLACE_OTHER_FILE\" on the share" 240 "86-resize-write"; then
    fail "the shrunk photos were never written back onto the share"
    screenshot "86-no-resize-write"
    logcat_dump "86-resize-write" > /dev/null
    finish
fi

sleep 3
capture_log "86-resize-write"
expect_log "Wrote over \"$FIXTURE_IN_PLACE_FILE\" on the share" "the first photo was written back as well"
refute_log "A write to the share failed" "the share took both writes"

# both are the seed's width in pixels, the turn having touched only the tag
expected_width=$((width_before * 3 / 4))
for file in "$turned_on_host" "$other_on_host"; do
    width_now="$(image_width "$file")"
    if [ "$width_now" = "$expected_width" ]; then
        pass "$(basename "$file") is $width_now across now, three quarters of what it was"
    else
        fail "$(basename "$file") is $width_now across; $expected_width was expected"
    fi

    check_stash_gone "$file"
done

# what the shrink does to the orientation tag is the same on every storage -- it is one
# resizeImage() for all three -- so it is not asked about here: seen once, the tag is reset,
# which is the device's behaviour too and not this script's business

if [ -f "$neighbour_on_host" ] && [ "$(md5sum < "$neighbour_on_host")" = "$neighbour_before" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to them, is untouched"
else
    fail "$FIXTURE_COPY_SOURCE_FILE is gone or changed; a write landed on the wrong file"
fi

screenshot "86-done"
finish
