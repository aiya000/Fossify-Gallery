#!/usr/bin/env bash
# #71: a medium of the share can be handed to another app, set as the wallpaper, asked for its
# place on the map, printed, and resized -- everything the fullscreen viewer offers that wants a
# real file to read.
#
# The bug this was written for: the viewer offered all of these for a medium of the share, and
# every one of them ended in a toast saying the file could not be fetched from the network share.
# A medium of the share has no file on this device, and the share -- unlike pCloud -- had no way
# to fetch one for reading: only the viewer's own copy and the editor's were fetched. Now the
# copy the viewer drew is handed out under the medium's own name, the way pCloud's is.
#
# Worth driving rather than reading, because what each action leads to is a window of somebody
# else's: the system's chooser for "Open with" and "Set as", the print spooler's preview, the
# map for the place in the EXIF, and the share itself for the resize. A unit test can say that
# the storage no longer throws; only the device can say that the other side got a file it could
# show.
#
# What the resize checks is on the share: the medium's pixels shrank, its bytes changed, nothing
# is left under the stash name the overwrite goes through, and the file beside it is untouched.
#
# Nothing in the fixture's counts may be written over -- 10-scan-whole-share.sh asserts on them --
# so this script brings its own file and takes it away again on the way in and on the way out.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# an image the fixture already has, copied into place with a location written into its EXIF,
# which is what the map check relies on: the fixture's own images carry none
seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_image" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

target_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_HANDOUT_FOLDER/$FIXTURE_HANDOUT_FILE"
stash_on_host="$target_on_host.$FIXTURE_OVERWRITE_STASH_SUFFIX"
# the one that must come through untouched: it sits in the same folder as the medium being
# resized, and it is what a stash rename that got the path wrong would land on
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

# the width of an image on this machine, read the way the app never does: off the file
image_width() {
    ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$1"
}

# The links the app has handed out, which is readable on the debug build and nowhere else.
# Empty, not an error, when the directory is not there: on a build that never hands anything
# out it never is, and under `set -o pipefail` a failing `ls` would end the script before it
# had said what it found -- which is how the first red run ended without a verdict
smb_work_listing() {
    "${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" ls -R "$FIXTURE_SMB_WORK_DIR" 2> /dev/null | tr -d '\r' || true
}

# which window has the focus: the chooser and the print spooler are other apps' windows, and
# the surest sign that the app handed the file over is that one of them is now in front
focused_window() {
    "${ADB[@]}" shell dumpsys window | tr -d '\r' | rg -o 'mCurrentFocus=.*' | head -n 1 || true
}

# Waits for a window of somebody else's to come in front, and says which. The chooser is the
# system's intent resolver; on this emulator the one app that can take a photo is Google's,
# so its name is what turns up when the chooser is skipped for being the only choice
wait_for_foreign_window() {
    local pattern="$1" seconds="${2:-30}"
    local waited=0 focus
    while [ "$waited" -lt "$seconds" ]; do
        focus="$(focused_window)"
        if printf '%s\n' "$focus" | rg -q -- "$pattern"; then
            printf '%s\n' "$focus"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done

    printf '%s\n' "$focus"
    return 1
}

# Every action below hands the medium over the same way, so after the first one the link is
# already there and the rest are instant. What is asked after each one is the same: the link
# is under the medium's own name, and nothing said the fetch failed
check_handed_out() {
    local name="$1"
    local listing
    listing="$(smb_work_listing)"
    if printf '%s\n' "$listing" | rg -q -- "^$FIXTURE_HANDOUT_FILE\$"; then
        pass "the app handed the medium out under its own name"
    else
        fail "$FIXTURE_SMB_WORK_DIR holds no $FIXTURE_HANDOUT_FILE; the medium was not fetched for reading:"
        printf '%s\n' "$listing" | sed 's/^/     /'
    fi

    capture_log "$name"
    refute_log "Could not fetch" "and nothing said the fetch failed"
}

step "putting a photo on the share for the app to hand out"
# A copy of the fixture's image with a place written into its EXIF, so that "Show on map" has
# somewhere to go. The EXIF is also what tells this file from its neighbour byte for byte, so
# that "the neighbour is untouched" cannot pass on a build that wrote over the neighbour instead.
# Pillow is already what thumbs.py reads screenshots with; the GPS IFD has to be hung on the
# main one as a whole, get_ifd() alone hands back a dict nothing writes out
python3 - "$seed_image" "$target_on_host" << 'EOF'
import sys
from PIL import Image
from PIL.TiffImagePlugin import IFDRational

src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src)
exif = im.getexif()
exif[0x8825] = {
    1: "N",
    2: (IFDRational(35, 1), IFDRational(0, 1), IFDRational(0, 1)),
    3: "E",
    4: (IFDRational(135, 1), IFDRational(0, 1), IFDRational(0, 1)),
}
im.save(dst, exif=exif.tobytes())
EOF
before_md5="$(md5sum < "$target_on_host")"
before_width="$(image_width "$target_on_host")"
neighbour_before="$(md5sum < "$neighbour_on_host")"
note "$FIXTURE_HANDOUT_FOLDER/$FIXTURE_HANDOUT_FILE is $before_width pixels across on the share"

step "seeding, and scanning the share so it has a row"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "47-menu"
if ! wait_for_log "Walked the share:" 900 "47-scan"; then
    fail "the share was never scanned, so there is nothing to hand out"
    screenshot "47-no-scan"
    finish
fi

step "opening $FIXTURE_HANDOUT_FOLDER on the share"
if ! ui_wait_exact_text "$FIXTURE_HANDOUT_FOLDER" 60 "47-list"; then
    fail "$FIXTURE_HANDOUT_FOLDER is not in the folder list"
    screenshot "47-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_HANDOUT_FOLDER" "47-open-folder"
sleep 3

# the filenames, so the medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "47-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_HANDOUT_FILE" 60 "47-grid"; then
    fail "$FIXTURE_HANDOUT_FILE is not in the grid; the scan did not see what was put on the share"
    screenshot "47-no-file"
    finish
fi

ui_tap_text "$FIXTURE_HANDOUT_FILE" "47-open-photo"
sleep 4
screenshot "47-viewer"

# The chooser, the print spooler and the map's toast are all windows of their own, so the view
# tree has them while they are up. The chooser is the system's intent resolver, and it is what
# comes in front even when the one app that can take the medium is the only choice
step "Open with, which hands the medium to another app"
logcat_reset
tap_action "Open with" "47-open-with" || finish
if focus="$(wait_for_foreign_window 'intentresolver|ChooserActivity|ResolverActivity|com.google.android.apps.photos' 30)"; then
    pass "another app's window came in front: $focus"
else
    fail "nothing came in front after Open with; the focus is on $focus"
fi
sleep 1
screenshot "47-open-with-chooser"
check_handed_out "47-open-with"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

# the viewer may have been left by the other app taking the medium; come back to it
if ! ui_wait_text "$FIXTURE_HANDOUT_FILE" 10 "47-back-from-open-with"; then
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 2
fi

step "Set as, which offers the medium as a wallpaper"
logcat_reset
tap_action "Set as" "47-set-as" || finish
if focus="$(wait_for_foreign_window 'intentresolver|ChooserActivity|ResolverActivity' 30)"; then
    pass "the chooser came in front: $focus"
else
    fail "no chooser came in front after Set as; the focus is on $focus"
fi
sleep 1
set_as="$(ui_dump "47-set-as-chooser")"
screenshot "47-set-as-chooser"
# the chooser carries no title in its view tree; what says it is the set-as chooser and not the
# open-with one is what it lists -- the wallpaper setters
if python3 "$DRIVE_DIR/ui.py" "$set_as" --text "Wallpaper" > /dev/null; then
    pass "and it offers the medium as a wallpaper"
else
    fail "the chooser offers no wallpaper setter (view tree in $set_as)"
    python3 "$DRIVE_DIR/ui.py" "$set_as" --list | sed 's/^/     /'
fi
check_handed_out "47-set-as"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

# The place seeded into the EXIF above is read off the file the app handed out, and sent to
# whatever opens a geo link -- on this emulator, Google Maps. The map coming in front is the
# check: it is only asked for after the EXIF was read. A photo with no place in it ends in a
# toast instead, which is up for two seconds and was never caught by a dump in time
step "Show on map, which reads the medium's EXIF"
logcat_reset
tap_action "Show on map" "47-show-on-map" || finish
if focus="$(wait_for_foreign_window 'com.google.android.apps.maps' 90)"; then
    pass "the map came in front with the place read out of the EXIF: $focus"
else
    fail "no map came in front after Show on map; the focus is on $focus"
fi
sleep 2
screenshot "47-map"
check_handed_out "47-show-on-map"
# the map is somebody else's app, and a first launch of it may be several screens deep; the
# surest way back to the viewer is to take it down
"${ADB[@]}" shell am force-stop com.google.android.apps.maps
sleep 3
if ! ui_wait_text "$FIXTURE_HANDOUT_FILE" 10 "47-back-from-map"; then
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 2
fi

step "Print, which hands the medium to the print spooler"
logcat_reset
tap_action "Print" "47-print" || finish
if focus="$(wait_for_foreign_window 'printspooler' 60)"; then
    pass "the print spooler came in front: $focus"
else
    fail "the print spooler never came in front after Print; the focus is on $focus"
fi
sleep 2
screenshot "47-print-preview"
check_handed_out "47-print"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 3
if ! ui_wait_text "$FIXTURE_HANDOUT_FILE" 10 "47-back-from-print"; then
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 2
fi

step "Resize, which writes the medium back over itself on the share"
tap_action "Resize" "47-resize" || finish
sleep 2
resize="$(ui_dump "47-resize-dialog")"
screenshot "47-resize-dialog"

# the dialog opens on the folder the medium is in. The folder box used to read
# "Internalsmb:/Screens/" -- this device's label glued onto the raw pseudo path -- which said the
# medium was somewhere it is not
if python3 "$DRIVE_DIR/ui.py" "$resize" --text "smb:" > /dev/null; then
    fail "the folder box shows the raw pseudo path (view tree in $resize)"
    python3 "$DRIVE_DIR/ui.py" "$resize" --list | sed 's/^/     /'
else
    pass "the folder box does not show the raw pseudo path"
fi

if python3 "$DRIVE_DIR/ui.py" "$resize" --text "Network share" > /dev/null; then
    pass "and it names the network share"
else
    fail "the folder box does not name the network share (view tree in $resize)"
    python3 "$DRIVE_DIR/ui.py" "$resize" --list | sed 's/^/     /'
fi

# half as wide, typed into the width box; the height follows on its own
new_width=$((before_width / 2))
if point="$(python3 "$DRIVE_DIR/ui.py" "$resize" --resource-id "resize_image_width")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 1
    replace_text_field "$new_width" 6
    sleep 1
else
    fail "the width box is not in the dialog (view tree in $resize)"
    finish
fi

screenshot "47-resize-typed"
ui_tap_text "OK" "47-resize-ok"
sleep 3
confirm="$(ui_dump "47-resize-confirm")"
screenshot "47-resize-confirm"

# the name is the one the medium already has, so the app has to ask before writing over it: an
# overwritten medium does not pass through the recycle bin on any storage
if python3 "$DRIVE_DIR/ui.py" "$confirm" --text "already exists" > /dev/null; then
    pass "the app asks whether to write over the medium"
else
    fail "nothing asked about the name that is taken (view tree in $confirm)"
    python3 "$DRIVE_DIR/ui.py" "$confirm" --list | sed 's/^/     /'
    finish
fi

logcat_reset
ui_tap_text "Yes" "47-resize-yes"

if ! wait_for_log "Wrote over \"$FIXTURE_HANDOUT_FILE\" on the share" 180 "47-resize-write"; then
    fail "the write over never finished"
    screenshot "47-no-resize-write"
    logcat_dump "47-resize-write" > /dev/null
    finish
fi

capture_log "47-resize-write"
refute_log "A write to the share failed" "the share took the write"
refute_log "could not be given its name back" "the original never had to be put back"

step "what the share has now"
if [ -f "$target_on_host" ]; then
    pass "$FIXTURE_HANDOUT_FOLDER/$FIXTURE_HANDOUT_FILE is still on the share"
else
    fail "$FIXTURE_HANDOUT_FOLDER/$FIXTURE_HANDOUT_FILE is gone; the write took the medium with it"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_HANDOUT_FOLDER" | sed 's/^/     /'
    finish
fi

after_width="$(image_width "$target_on_host")"
if [ "$after_width" = "$new_width" ]; then
    pass "and it is $after_width pixels across now, down from $before_width"
else
    fail "the medium on the share is $after_width pixels across; $new_width was asked for"
fi

if [ "$(md5sum < "$target_on_host")" != "$before_md5" ]; then
    pass "and what is in it is not what was in it before"
else
    fail "the medium on the share is byte for byte what it was; nothing was written over"
fi

# the one thing a write that stopped between the stash and the drop leaves behind
if [ -e "$stash_on_host" ]; then
    fail "$FIXTURE_HANDOUT_FILE.$FIXTURE_OVERWRITE_STASH_SUFFIX is still on the share; the stash was never taken away"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_HANDOUT_FOLDER" | sed 's/^/     /'
else
    pass "and nothing is left under the name the original was stashed as"
fi

if [ -f "$neighbour_on_host" ] && [ "$(md5sum < "$neighbour_on_host")" = "$neighbour_before" ]; then
    pass "and $FIXTURE_COPY_SOURCE_FILE, which was next to it, is untouched"
else
    fail "$FIXTURE_COPY_SOURCE_FILE is gone or changed; the write landed on the wrong file"
    ls -l "$FIXTURE_SHARE_DIR/$FIXTURE_HANDOUT_FOLDER" | sed 's/^/     /'
fi

screenshot "47-done"
finish
