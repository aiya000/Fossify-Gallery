#!/usr/bin/env bash
# A folder of the folder list, copied whole with the destination picker's OK onto pCloud and onto
# this device. 72-a-folder-to-the-top-or-into-a-group.sh drives the share; the other two
# storages are made ready another way, and that is what is driven here:
#
# - pCloud uploads into a folder id, which only a folder that exists has, so the app makes the
#   folder first. Carried, a folder of this device, is copied with pCloud's chip and OK at the
#   top: the account has to gain Carried with the photo in it, and the stub has to have been
#   asked to make it
# - this device's folder is made under Pictures/. FromTheCloud, a folder of pCloud, is copied
#   with this device's chip and OK at the top: /sdcard/Pictures/FromTheCloud has to hold the photo
#
# pCloud is fixture/pcloud-stub.py, as in 60. Its transfers say nothing in the log when they go
# well, so what is waited for is the file arriving, on this machine and on the device.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed_file_on_host="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_file_on_host" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

device_dir="$FIXTURE_CARRIED_FOLDER_DIR"
device_file="$device_dir/$FIXTURE_CARRIED_FOLDER_FILE"
cloud_folder="$FIXTURE_CLOUD_FOLDER_NAME"
cloud_file="$FIXTURE_CLOUD_FOLDER_FILE"
arrived_on_device="/sdcard/Pictures/$cloud_folder/$cloud_file"
arrived_on_pcloud="$FIXTURE_PCLOUD_DIR/$FIXTURE_CARRIED_FOLDER_NAME/$FIXTURE_CARRIED_FOLDER_FILE"
requests_log="$RUN_DIR/pcloud-requests.log"

stub_pid=""
leave_things_as_they_were() {
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
    "${ADB[@]}" shell "rm -rf '/sdcard/Pictures/$cloud_folder'" > /dev/null 2>&1 || true
}
trap leave_things_as_they_were EXIT

device_file_exists() {
    "${ADB[@]}" shell "[ -f '$1' ] && echo yes" | tr -d '\r' | rg -q yes
}

step "building a pCloud account with one folder of its own"
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$FIXTURE_PCLOUD_DIR/$cloud_folder"
cp "$seed_file_on_host" "$FIXTURE_PCLOUD_DIR/$cloud_folder/$cloud_file"

python3 "$TEST_DEVICE_DIR/fixture/pcloud-stub.py" \
    --root "$FIXTURE_PCLOUD_DIR" \
    --port "$FIXTURE_PCLOUD_PORT" \
    --token "$FIXTURE_PCLOUD_TOKEN" \
    --log "$requests_log" \
    > "$RUN_DIR/pcloud-stub.log" 2>&1 &
stub_pid=$!

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

step "putting a folder on the device"
"${ADB[@]}" shell "rm -rf '$device_dir' '/sdcard/Pictures/$cloud_folder'"
"${ADB[@]}" shell "mkdir -p '$device_dir'"
"${ADB[@]}" push "$seed_file_on_host" "$device_file" > /dev/null
"${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$device_file" > /dev/null 2>&1 || true

env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4

########################################################################################
step "Copy to, pCloud, OK at the top"
########################################################################################

if ! ui_wait_exact_text "$FIXTURE_CARRIED_FOLDER_NAME" 60 "73-device-list"; then
    fail "$FIXTURE_CARRIED_FOLDER_NAME is not in the folder list of this device"
    screenshot "73-no-device-folder"
    finish
fi

select_row "$FIXTURE_CARRIED_FOLDER_NAME" "73-select-carried" || finish
tap_action "Copy to" "73-copy-carried" || finish
sleep 2
if ! ui_wait_exact_text "pCloud" 30 "73-picker-carried"; then
    fail "the destination picker has no pCloud chip"
    screenshot "73-no-pcloud-chip"
    finish
fi

ui_tap_exact_text "pCloud" "73-pcloud-chip" || finish
sleep 2
screenshot "73-picker-pcloud"
ui_tap_exact_text "OK" "73-ok-pcloud" || finish

arrived=0
for _ in $(seq 1 60); do
    if [ -f "$arrived_on_pcloud" ]; then
        arrived=1
        break
    fi
    sleep 2
done

if [ "$arrived" = "1" ] && cmp -s "$seed_file_on_host" "$arrived_on_pcloud"; then
    pass "pCloud has $FIXTURE_CARRIED_FOLDER_NAME/$FIXTURE_CARRIED_FOLDER_FILE, byte for byte"
else
    fail "pCloud has no $FIXTURE_CARRIED_FOLDER_NAME/$FIXTURE_CARRIED_FOLDER_FILE; the account holds:"
    ls -la "$FIXTURE_PCLOUD_DIR" | sed 's/^/     /'
    note "what the stub was asked for:"
    sed 's/^/     /' "$requests_log" || true
fi

if rg -q "CREATED FOLDER $FIXTURE_CARRIED_FOLDER_NAME\$" "$requests_log"; then
    pass "and the app asked pCloud to make the folder before uploading into it"
else
    fail "pCloud was never asked to make $FIXTURE_CARRIED_FOLDER_NAME (requests are in $requests_log)"
fi

if device_file_exists "$device_file"; then
    pass "the device still has $device_file"
else
    fail "a copy took $device_file off the device"
fi

########################################################################################
step "Copy to, this device, OK at the top"
########################################################################################

sleep 3
switch_storage_to "pCloud" "73-storage-pcloud"
sleep 2
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "73-menu-pcloud"
if ! ui_wait_exact_text "$cloud_folder" 120 "73-pcloud-list"; then
    fail "$cloud_folder did not turn up after a pCloud scan"
    screenshot "73-no-pcloud-folder"
    finish
fi

select_row "$cloud_folder" "73-select-cloud" || finish
tap_action "Copy to" "73-copy-cloud" || finish
sleep 2
if ! ui_wait_exact_text "This device" 30 "73-picker-cloud"; then
    fail "the destination picker has no chip for this device"
    screenshot "73-no-device-chip"
    finish
fi

ui_tap_exact_text "This device" "73-device-chip" || finish
sleep 2
screenshot "73-picker-device"
ui_tap_exact_text "OK" "73-ok-device" || finish

arrived=0
for _ in $(seq 1 60); do
    if device_file_exists "$arrived_on_device"; then
        arrived=1
        break
    fi
    sleep 2
done

if [ "$arrived" = "1" ]; then
    pass "this device has Pictures/$cloud_folder/$cloud_file"
else
    fail "this device has no Pictures/$cloud_folder/$cloud_file"
    "${ADB[@]}" shell "ls -la /sdcard/Pictures" | sed 's/^/     /'
    screenshot "73-not-on-device"
fi

if [ -f "$FIXTURE_PCLOUD_DIR/$cloud_folder/$cloud_file" ]; then
    pass "and pCloud still has $cloud_folder/$cloud_file"
else
    fail "a copy took $cloud_folder/$cloud_file off pCloud"
fi

screenshot "73-done"
finish
