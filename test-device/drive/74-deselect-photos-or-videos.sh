#!/usr/bin/env bash
# #138: "Deselect photos" and "Deselect videos" in a folder's selection menu take one kind back
# out of the selection, and leave the other kind selected.
#
# A folder of this device holding two photos and two videos is opened and everything in it
# selected. What says what is selected is the toolbar's count -- "4 / 4", "2 / 4" -- which is the
# adapter's own selection, counted by commons, rather than anything this script infers. Then:
#
# - "Deselect photos" leaves "2 / 4", and after it only "Deselect videos" is offered: there is no
#   photo left to take out
# - "Deselect videos" then takes out the last two, and the selection ends, the same as it does
#   when the last item is deselected by hand
# - and the same the other way round, videos first, so neither entry passes by only ever being
#   the one that ends the selection
#
# It brings its own folder and takes it away on the way in and on the way out.
set -euo pipefail

# the folder list opens on this device, so nothing has to be switched to
export FIXTURE_STORAGE_FILTER=1

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed_image="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
seed_video="$FIXTURE_SHARE_DIR/Camera/video-1.mp4"
for seed in "$seed_image" "$seed_video"; do
    if [ ! -f "$seed" ]; then
        echo "the fixture share has no $seed; run fixture/seed-share.sh" >&2
        exit 1
    fi
done

mixed_on_device="/sdcard/Pictures/$FIXTURE_MIXED_FOLDER"
read -r -a photos <<< "$FIXTURE_MIXED_PHOTOS"
read -r -a videos <<< "$FIXTURE_MIXED_VIDEOS"
total=$(( ${#photos[@]} + ${#videos[@]} ))

scan_mixed() {
    local name
    for name in "${photos[@]}" "${videos[@]}"; do
        "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$mixed_on_device/$name" > /dev/null 2>&1 || true
    done
}

# Run on the way in as well as on the way out. A run that was killed outright never got here, and
# the folder would sit in the folder list of every later run
clean_the_device() {
    "${ADB[@]}" shell "rm -rf '$mixed_on_device'"
    scan_mixed
}

trap clean_the_device EXIT
clean_the_device

# the toolbar's count, exactly: "2 / 4" and not "12 / 40"
selection_count_is() {
    local count="$1" name="$2"
    local dump
    dump="$(ui_dump "$name")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --text "$count / $total" --exact > /dev/null
}

# what the selection's menu offers, the overflow opened and closed again
offered() {
    local text="$1" name="$2"
    local dump found=1
    open_overflow_menu
    sleep 1
    dump="$(ui_dump "$name")"
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "$text" --exact > /dev/null; then
        found=0
    fi
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 1
    return "$found"
}

# selects everything in the folder, starting from one held photo
select_everything() {
    local name="$1"
    if ! select_row "${photos[0]}" "$name-hold"; then
        screenshot "$name-not-selected"
        finish
    fi

    if ! tap_action "Select all" "$name-select-all"; then
        screenshot "$name-no-select-all"
        finish
    fi
    sleep 1

    if selection_count_is "$total" "$name-all"; then
        pass "everything is selected, $total / $total"
    else
        fail "'Select all' did not select all $total media"
        screenshot "$name-not-all"
        finish
    fi
}

# takes one kind out, and checks the other kind is what is left
deselect_first() {
    local label="$1" left="$2" other_label="$3" name="$4"
    if ! tap_action "$label" "$name-deselect"; then
        screenshot "$name-no-deselect"
        finish
    fi
    sleep 1

    if selection_count_is "$left" "$name-after"; then
        pass "'$label' left $left / $total selected"
    else
        fail "'$label' did not leave $left / $total selected"
        screenshot "$name-wrong-count"
        finish
    fi

    if offered "$label" "$name-menu"; then
        fail "'$label' is still offered with nothing of its kind selected"
    else
        pass "and it is no longer offered"
    fi

    if offered "$other_label" "$name-menu-other"; then
        pass "while '$other_label' still is"
    else
        fail "'$other_label' is not offered, though its kind is still selected"
    fi
}

# takes the last kind out, and checks the selection is over
deselect_last() {
    local label="$1" name="$2"
    if ! tap_action "$label" "$name-deselect"; then
        screenshot "$name-no-deselect"
        finish
    fi
    sleep 1

    if in_selection_mode "$name-after"; then
        fail "'$label' took the last of the selection out, but the selection is still on"
        screenshot "$name-still-selecting"
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
    else
        pass "'$label' took out the rest, and the selection ended"
    fi
}

step "putting ${#photos[@]} photos and ${#videos[@]} videos into $FIXTURE_MIXED_FOLDER on the device"
"${ADB[@]}" shell "mkdir -p '$mixed_on_device'"
for name in "${photos[@]}"; do
    "${ADB[@]}" push "$seed_image" "$mixed_on_device/$name" > /dev/null
done
for name in "${videos[@]}"; do
    "${ADB[@]}" push "$seed_video" "$mixed_on_device/$name" > /dev/null
done
scan_mixed

step "seeding, and opening $FIXTURE_MIXED_FOLDER"
"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

if ! ui_wait_exact_text "$FIXTURE_MIXED_FOLDER" 60 "74-list"; then
    fail "$FIXTURE_MIXED_FOLDER is not in the folder list (MediaStore may not have taken the seed files)"
    screenshot "74-no-folder"
    finish
fi

ui_tap_exact_text "$FIXTURE_MIXED_FOLDER" "74-open"
sleep 3

# the filenames, so a medium can be held by name rather than by where it happens to be drawn
dump="$(ui_dump "74-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "${photos[0]}" 60 "74-grid"; then
    fail "${photos[0]} is not in the grid"
    screenshot "74-no-file"
    finish
fi

step "photos out first"
select_everything "74-photos"
deselect_first "Deselect photos" "${#videos[@]}" "Deselect videos" "74-photos"
deselect_last "Deselect videos" "74-photos-then-videos"

step "videos out first"
select_everything "74-videos"
deselect_first "Deselect videos" "${#photos[@]}" "Deselect photos" "74-videos"
deselect_last "Deselect photos" "74-videos-then-photos"

screenshot "74-done"
finish
