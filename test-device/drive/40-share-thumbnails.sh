#!/usr/bin/env bash
# Every medium in a folder of the share gets a thumbnail, whatever size the file is.
#
# The folder of renders is the one that broke: PNGs of a few megabytes each, and not one tile drew
# anything. It is not size on its own -- a 9 MB JPEG in the same folder was always fine -- it is
# that Glide will only rewind a stream so far while it works out how big the picture is, and
# BitmapFactory reads a PNG to its end before it will say. ThumbnailPolicy carries the number.
#
# What made it worth driving rather than reading: the tile showed nothing, not even the warning
# icon the adapter puts up for a load that failed, because the fallback to Picasso was swallowing
# the error on a path Picasso can never read. A view tree says the same thing either way, so the
# check here is on the pixels -- see thumbs.py.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

step "seeding, and scanning the share so the folder has rows"
env FIXTURE_STORAGE_FILTER=4 "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "40-menu"
if ! wait_for_log "Walked the share:" 900 "40-scan"; then
    fail "the share was never scanned, so there is nothing to draw"
    screenshot "40-no-scan"
    finish
fi

step "opening $FIXTURE_RENDERS_FOLDER"
if ! ui_wait_exact_text "$FIXTURE_RENDERS_FOLDER" 60 "40-list"; then
    fail "$FIXTURE_RENDERS_FOLDER is not in the folder list"
    screenshot "40-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_RENDERS_FOLDER" "40-open"
sleep 3

# the filenames, so that a tile that drew nothing can be named rather than counted. They are a
# toolbar toggle and pm clear left them off
step "turning the filenames on"
dump="$(ui_dump "40-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

# Waits for them rather than sleeping a guessed number of seconds: the largest of these is 21 MB
# and has to come over the network before anything can be decoded out of it. A run that has them
# all early stops early
step "waiting for the thumbnails"
waited=0
while [ "$waited" -lt 180 ]; do
    screenshot "40-renders"
    dump="$(ui_dump "40-renders")"
    if python3 "$DRIVE_DIR/thumbs.py" "$RUN_DIR/40-renders.png" "$dump" --expect "${#FIXTURE_RENDERS[@]}" > /dev/null 2>&1; then
        break
    fi
    sleep 5
    waited=$((waited + 5))
done

step "what drew, and what did not"
if python3 "$DRIVE_DIR/thumbs.py" "$RUN_DIR/40-renders.png" "$dump" --expect "${#FIXTURE_RENDERS[@]}" | sed 's/^/     /'; then
    pass "every one of the ${#FIXTURE_RENDERS[@]} renders has a thumbnail"
else
    fail "a render drew nothing; the screenshot is $RUN_DIR/40-renders.png and the view tree is $dump"
fi

finish
