#!/usr/bin/env bash
# #92 and #71: "Save as" knows all three storages, and a photo of the share can be rotated and
# saved without the app reaching for a file that is not there.
#
# The bug this was written for: rotating a photo of the share and saving it ended in an OS toast
# reading `java.io.FileNotFoundException: sm…`, cut off mid-path. The viewer handed the share's
# pseudo path straight to the file APIs, both for the source it read and the destination it
# wrote. The picker behind the path box only knew this device, so there was nowhere else to send
# it either.
#
# Worth driving rather than reading, because every part of it is a different layer answering:
# the picker lists pCloud over http and the share over SMB, the source is fetched into the
# device cache, the rotation happens on that copy, and the result lands on a storage that was
# picked by hand. A unit test can say none of it.
#
# The share is signed in to pCloud as well, through the stub, because the chip row is the thing
# under test and a chip that is missing because nothing is configured proves nothing.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

source_file_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$source_file_on_host" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

saved_on_device="$FIXTURE_LOCAL_DESTINATION_DIR/$FIXTURE_COPY_SOURCE_FILE"
pcloud_destination_dir="$FIXTURE_PCLOUD_DIR/$FIXTURE_PCLOUD_DESTINATION_NAME"

# what the share holds before any of this, to compare against at the end
source_md5_before="$(md5sum < "$source_file_on_host")"

device_file_exists() {
    "${ADB[@]}" shell "[ -f '$1' ] && echo yes" 2> /dev/null | tr -d '\r' | rg -q yes
}

# Looks for a row in the folder picker, scrolling down when it is not on screen.
#
# uiautomator dumps what is drawn, so a row below the fold is not in the tree and reads exactly
# like a row that is not there. The internal storage root has a dozen folders and Pictures sits
# past the bottom of the dialog, which is how this was found
find_in_picker() {
    local text="$1" name="${2:-picker}"
    local attempt
    for attempt in 1 2 3 4 5; do
        if ui_wait_exact_text "$text" 4 "$name-$attempt"; then
            return 0
        fi

        "${ADB[@]}" shell input swipe 540 1600 540 900 300
        sleep 1
    done

    return 1
}

step "building the pCloud account, so that its chip has something behind it"
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$pcloud_destination_dir"
cp "$source_file_on_host" "$pcloud_destination_dir/seed.jpg"

step "starting the pCloud stub on port $FIXTURE_PCLOUD_PORT"
python3 "$TEST_DEVICE_DIR/fixture/pcloud-stub.py" \
    --root "$FIXTURE_PCLOUD_DIR" \
    --port "$FIXTURE_PCLOUD_PORT" \
    --token "$FIXTURE_PCLOUD_TOKEN" \
    --log "$RUN_DIR/pcloud-requests.log" \
    > "$RUN_DIR/pcloud-stub.log" 2>&1 &
stub_pid=$!
trap 'kill "$stub_pid" 2> /dev/null || true' EXIT

stub_is_up=0
for _ in $(seq 1 20); do
    if (exec 3<> "/dev/tcp/127.0.0.1/$FIXTURE_PCLOUD_PORT") 2> /dev/null; then
        exec 3<&- || true
        stub_is_up=1
        break
    fi
    sleep 1
done

if [ "$stub_is_up" != "1" ]; then
    fail "the pCloud stub never came up; its own log is at $RUN_DIR/pcloud-stub.log"
    finish
fi

step "making a folder on the device for the save to land in"
"${ADB[@]}" shell "rm -f '$saved_on_device'" || true
"${ADB[@]}" shell "mkdir -p '$FIXTURE_LOCAL_DESTINATION_DIR'"
# one image, so that MediaStore has the folder and the picker lists it
"${ADB[@]}" push "$source_file_on_host" "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" > /dev/null
for path in "$FIXTURE_LOCAL_DESTINATION_DIR/seed.jpg" "$saved_on_device"; do
    "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$path" > /dev/null 2>&1 || true
done

step "seeding, signing in to the stub, and scanning the share"
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "75-menu-share"
if ! wait_for_log "Walked the share:" 900 "75-scan"; then
    fail "the share was never scanned, so there is no photo to open"
    screenshot "75-no-scan"
    finish
fi

step "opening a photo of the share full screen"
if ! ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 60 "75-list"; then
    fail "$FIXTURE_COPY_SOURCE_FOLDER is not in the folder list"
    screenshot "75-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_COPY_SOURCE_FOLDER" "75-open-folder"
sleep 3

dump="$(ui_dump "75-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "75-grid"; then
    fail "$FIXTURE_COPY_SOURCE_FILE is not in the grid"
    screenshot "75-no-file"
    finish
fi

ui_tap_text "$FIXTURE_COPY_SOURCE_FILE" "75-open-photo"
sleep 4
screenshot "75-viewer"

step "rotating it, which is what puts Save as on the toolbar"
# Rotate and Save as are icons on the viewer's own toolbar, not items in its overflow: the menu
# XML gives them icons and always/ifRoom, and uiautomator hangs each title on its icon as a
# content description, which is what ui.py matches. Save as is the tick, and it is invisible
# until something has been rotated -- so its absence here is the "before" of this step
before_rotate="$(ui_dump "75-before-rotate")"
if python3 "$DRIVE_DIR/ui.py" "$before_rotate" --text "Save as" --exact > /dev/null; then
    fail "'Save as' is on the toolbar before anything was rotated"
fi

ui_tap_text "Rotate" "75-tap-rotate"
sleep 1
ui_tap_text "Rotate right" "75-rotate-right"
sleep 3

after_rotate="$(ui_dump "75-after-rotate")"
screenshot "75-after-rotate"
if python3 "$DRIVE_DIR/ui.py" "$after_rotate" --text "Save as" --exact > /dev/null; then
    pass "'Save as' is on the toolbar after the rotation"
else
    fail "'Save as' did not appear after the rotation (view tree in $after_rotate)"
    screenshot "75-no-save-as"
    finish
fi

ui_tap_text "Save as" "75-tap-save-as"
sleep 2
save_as="$(ui_dump "75-save-as")"
screenshot "75-save-as"

# The path box used to read "Internalsmb:/00-Pictures/…": commons' humanizePath() does not know a
# pseudo path, so it prefixed this device's label onto one. A path that says it is on this device
# and is not is worse than an ugly one -- it is what the whole bug looked like from the outside
step "what the path box says"
if python3 "$DRIVE_DIR/ui.py" "$save_as" --text "smb:" > /dev/null; then
    fail "the path box still shows the raw pseudo path (view tree in $save_as)"
    python3 "$DRIVE_DIR/ui.py" "$save_as" --list | sed 's/^/     /'
else
    pass "the path box does not show the raw pseudo path"
fi

if python3 "$DRIVE_DIR/ui.py" "$save_as" --text "Network share" > /dev/null; then
    pass "and it names the storage the photo is on"
else
    fail "the path box does not name the network share (view tree in $save_as)"
fi

step "what the folder picker offers"
ui_tap_text "Network share" "75-tap-path"
sleep 3
picker="$(ui_dump "75-picker")"
screenshot "75-picker"

# "Internal" rather than "This device": this picker's chips are per storage of the device --
# internal, an SD card, a USB stick -- where the folder list's chip stands for the device as a
# whole. The two say different things, so they are allowed to read differently
for chip in "Internal" "pCloud" "Network share"; do
    if python3 "$DRIVE_DIR/ui.py" "$picker" --text "$chip" --exact > /dev/null; then
        pass "'$chip' is offered as a destination"
    else
        fail "'$chip' is not among the picker's storages (view tree in $picker)"
        python3 "$DRIVE_DIR/ui.py" "$picker" --list | sed 's/^/     /'
    fi
done

step "picking a folder of this device"
ui_tap_text "Internal" "75-pick-device"
sleep 3

# this picker lists real directories rather than what a scan found, so the walk down is the
# ordinary one: the storage root, then Pictures, then the folder seeded above
if ! find_in_picker "Pictures" "75-picker-root"; then
    fail "the internal storage root does not list Pictures"
    screenshot "75-no-pictures"
    finish
fi

ui_tap_text "Pictures" "75-open-pictures"
sleep 2

if ! find_in_picker "$FIXTURE_LOCAL_DESTINATION_NAME" "75-picker-pictures"; then
    fail "$FIXTURE_LOCAL_DESTINATION_NAME is not under Pictures"
    screenshot "75-no-destination"
    finish
fi

ui_tap_text "$FIXTURE_LOCAL_DESTINATION_NAME" "75-open-destination"
sleep 2
ui_tap_text "OK" "75-picker-ok"
sleep 2

step "saving it"
logcat_reset
ui_tap_text "OK" "75-save-ok"

# the fetch of the source, the rotation and the write all happen off the main thread; a few
# seconds is plenty for a small JPEG over the local network, and the file is the assertion
for _ in $(seq 1 20); do
    if device_file_exists "$saved_on_device"; then
        break
    fi
    sleep 2
done

screenshot "75-saved"
if device_file_exists "$saved_on_device"; then
    pass "$saved_on_device is there"
else
    fail "nothing was saved; the folder holds:"
    "${ADB[@]}" shell "ls -l '$FIXTURE_LOCAL_DESTINATION_DIR'" | sed 's/^/     /'
    capture_log "75-save"
    finish
fi

capture_log "75-save"
refute_log "FileNotFoundException" "nothing went looking for a file that does not exist"

# the whole point of a rotation: what arrived is not the bytes that were on the share
saved_size="$("${ADB[@]}" shell stat -c %s "$saved_on_device" | tr -d '\r')"
if [ "$saved_size" -gt 0 ]; then
    pass "and it has $saved_size bytes in it"
else
    fail "what was saved is empty"
fi

# A "save as" onto this device must leave the original alone. It is the check that would catch
# the opposite mistake to the bug -- a rotation written back over the source instead of beside it
step "and the photo on the share is untouched"
if [ ! -f "$source_file_on_host" ]; then
    fail "$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE is gone from the share"
elif [ "$(md5sum < "$source_file_on_host")" = "$source_md5_before" ]; then
    pass "$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE is byte for byte what it was"
else
    fail "the photo on the share was written over by a save that was sent to this device"
fi

screenshot "75-done"
finish
