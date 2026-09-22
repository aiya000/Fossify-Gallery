#!/usr/bin/env bash
# #28: a medium of the share can be copied to pCloud, and nothing on the share is touched.
#
# The other half of SmbTransferService. TO_DEVICE is what 50-copy-off-share.sh drives; this is
# TO_PCLOUD, which goes a longer way round: the file is read off the share into the app's cache,
# uploaded from there, the staging directory is dropped, and the destination folder is scanned
# again so the lists would show what arrived. Every one of those steps is invisible from inside
# the app, which is why this is driven rather than read.
#
# pCloud itself is fixture/pcloud-stub.py -- see the comment at the top of it for why there is no
# account behind this. What is checked here is the app's side of the exchange: that it uploads at
# all, that it asks pCloud not to overwrite and not to leave a partial file, that it keeps the
# share's modification time, that it clears up after itself, and that what arrived is the file
# that was on the share, byte for byte.
#
# What is deliberately NOT checked here is the numbering of a name that is already taken on
# pCloud: pCloud does that itself, so a check on it would be a check on the stub. The app's part
# in it is the renameifexists it sends, and that is asserted on the request instead.
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

destination_dir="$FIXTURE_PCLOUD_DIR/$FIXTURE_PCLOUD_DESTINATION_NAME"
uploaded_file="$destination_dir/$FIXTURE_COPY_SOURCE_FILE"
requests_log="$RUN_DIR/pcloud-requests.log"

# what the share holds before any of this, so the last check has something to compare against
source_size_before="$(stat -c %s "$source_file_on_host")"

step "building the pCloud account the copy is sent to"
# From nothing every run: the ids the stub hands out live as long as the process, and a folder
# left over from an earlier run would be listed with an id the app's cache no longer agrees with.
# One image in it because an empty folder is no destination -- the picker lists the folders a scan
# found, and a scan finds folders by the media in them
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$destination_dir"
cp "$source_file_on_host" "$destination_dir/seed.jpg"
note "the account root is $FIXTURE_PCLOUD_DIR"

step "starting the pCloud stub on port $FIXTURE_PCLOUD_PORT"
python3 "$TEST_DEVICE_DIR/fixture/pcloud-stub.py" \
    --root "$FIXTURE_PCLOUD_DIR" \
    --port "$FIXTURE_PCLOUD_PORT" \
    --token "$FIXTURE_PCLOUD_TOKEN" \
    --log "$requests_log" \
    > "$RUN_DIR/pcloud-stub.log" 2>&1 &
stub_pid=$!
# it outlives nothing: a stub left listening would be picked up by the next run, which would then
# be talking to an account it did not build
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
note "requests to it are logged at $requests_log"

step "seeding, and signing the app in to the stub"
# The token and the host are the whole of what the OAuth screen leaves behind, so this is signing
# in as far as everything below the screen is concerned. The screen itself needs a client id that
# cannot be published, and driving a browser is not what this script is about
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

logcat_reset
app_start
sleep 4

step "scanning the share, so there is something to copy"
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "60-menu-share"
if ! wait_for_log "Walked the share:" 900 "60-scan-share"; then
    fail "the share was never scanned, so there is nothing to copy"
    screenshot "60-no-share-scan"
    finish
fi

step "scanning pCloud, so there is somewhere to copy to"
switch_storage_to "pCloud" "60-storage-pcloud"
sleep 2
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "60-menu-pcloud"

# A pCloud scan says nothing in the log when it goes well, so the folder turning up in the list is
# the signal. It is also the more honest one: a scan that wrote nothing the list can draw is no
# use to a copy that has to pick a destination out of that list
if ! ui_wait_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" 120 "60-pcloud-list"; then
    fail "$FIXTURE_PCLOUD_DESTINATION_NAME did not turn up after a pCloud scan"
    screenshot "60-no-pcloud-folder"
    note "what the stub was asked for:"
    sed 's/^/     /' "$requests_log" || true
    finish
fi

pass "the pCloud scan reached the stub and found $FIXTURE_PCLOUD_DESTINATION_NAME"

step "going back to the share and picking $FIXTURE_COPY_SOURCE_FILE"
switch_storage_to "Network share" "60-storage-share"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 60 "60-list"; then
    fail "$FIXTURE_COPY_SOURCE_FOLDER is not in the folder list"
    screenshot "60-no-folder"
    finish
fi

ui_tap_text "$FIXTURE_COPY_SOURCE_FOLDER" "60-open"
sleep 3

# the filenames, so a medium can be picked by name rather than by where it happens to be drawn
dump="$(ui_dump "60-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

if ! ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "60-grid"; then
    fail "$FIXTURE_COPY_SOURCE_FILE is not in the grid"
    screenshot "60-no-file"
    finish
fi

if ! select_row "$FIXTURE_COPY_SOURCE_FILE" "60-select"; then
    screenshot "60-not-selected"
    finish
fi

step "copying it to pCloud"
open_overflow_menu
sleep 1
menu="$(ui_dump "60-menu-open")"
if ! python3 "$DRIVE_DIR/ui.py" "$menu" --text "Copy to" --exact > /dev/null; then
    fail "'Copy to' is not offered for a medium of the share (view tree in $menu)"
    finish
fi

ui_tap_text "Copy to" "60-tap-copy"
sleep 2

# the picker opens on the storage the folder list was on -- the share -- and its chips are what
# reaches another one. A destination on the share is refused, pCloud is not
if ! ui_wait_exact_text "pCloud" 30 "60-picker"; then
    fail "the folder picker offers no pCloud chip, so pCloud cannot be reached as a destination"
    screenshot "60-no-pcloud-chip"
    finish
fi

ui_tap_text "pCloud" "60-pick-pcloud"
sleep 2

if ! ui_wait_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" 30 "60-picker-pcloud"; then
    fail "$FIXTURE_PCLOUD_DESTINATION_NAME is not in the picker"
    screenshot "60-no-destination"
    finish
fi

screenshot "60-picker-pcloud"
ui_tap_text "$FIXTURE_PCLOUD_DESTINATION_NAME" "60-pick-destination"

# said once per job, after the destination has been scanned again, so the line means "it is on
# pCloud and the list would show it"
if ! wait_for_log "Copied 1 of 1 off the share to pcloud:" 300 "60-copy"; then
    fail "the copy to pCloud never finished"
    screenshot "60-no-copy"
    note "what the stub was asked for:"
    sed 's/^/     /' "$requests_log" || true
    finish
fi

capture_log "60-copy"
refute_log "0 of 1 off the share" "the copy did not report a failure"

step "what arrived on pCloud"
if [ -f "$uploaded_file" ]; then
    pass "$FIXTURE_PCLOUD_DESTINATION_NAME/$FIXTURE_COPY_SOURCE_FILE is there"
else
    fail "nothing was uploaded; the account holds:"
    ls -l "$destination_dir" | sed 's/^/     /'
    finish
fi

if cmp -s "$source_file_on_host" "$uploaded_file"; then
    pass "and it is the file that was on the share, byte for byte"
else
    fail "what arrived is not the file on the share ($(stat -c %s "$uploaded_file") bytes against $(stat -c %s "$source_file_on_host"))"
fi

# the gallery sorts by the modification time, and pCloud takes the one the upload names. A copy
# that carried the time of the upload would sort to the top of the folder instead of where the
# original belongs
expected_modified="$(stat -c %Y "$source_file_on_host")"
actual_modified="$(stat -c %Y "$uploaded_file")"
drift=$((actual_modified - expected_modified))
if [ "${drift#-}" -le 2 ]; then
    pass "and it kept the share's modification time"
else
    fail "the upload is dated $actual_modified, the file on the share is dated $expected_modified"
fi

step "what the app asked pCloud for"
# nopartial: a file whose upload broke off must not appear at all. renameifexists: a copy must
# never write over something already there, which is pCloud's side of what availableName() does
# on the device
if rg -q '"nopartial": "1"' "$requests_log"; then
    pass "the upload said nopartial, so a broken one leaves nothing behind"
else
    fail "the upload did not say nopartial (requests are in $requests_log)"
fi

if rg -q '"renameifexists": "1"' "$requests_log"; then
    pass "and renameifexists, so it cannot write over what is there"
else
    fail "the upload did not say renameifexists (requests are in $requests_log)"
fi

if rg -q 'REFUSED' "$requests_log"; then
    fail "the stub turned a request away; the app may not be sending its token"
else
    pass "every request carried the token"
fi

step "what the app left in its cache"
# the staging directory is the app's own copy of the file, made because an upload needs a length
# and a share stream has none. It lives in the cache, which outlives the app being killed, so a
# directory left behind here would sit there until the system is short of space
staged="$("${ADB[@]}" shell "run-as $FIXTURE_PACKAGE sh -c 'ls cache/smb-transfer 2> /dev/null'" | tr -d '\r' | rg -v '^$' || true)"
if [ -z "$staged" ]; then
    pass "the staging directory was cleared up"
else
    fail "the app kept its staging copies: $staged"
fi

step "and the copy shows up in the app"
# Out of the folder first. The copy was started from inside the share's folder, and the storage
# chips belong to the folder list -- from a folder there is nothing to tap, which reads as "the
# chip is gone" rather than as "we are in the wrong screen". A back press while a selection is
# still on clears the selection instead of leaving, so it can take more than one
went_back=0
for _ in 1 2 3; do
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 2
    if ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 10 "60-back"; then
        went_back=1
        break
    fi
done

if [ "$went_back" != "1" ]; then
    fail "the folder list did not come back after the copy"
    screenshot "60-no-folder-list"
    finish
fi

# settle() scans the destination folder again before the service says it is done, so by now the
# lists have it. This is the half a look at the account on this machine cannot see
switch_storage_to "pCloud" "60-storage-pcloud-after"
sleep 2
if ! ui_wait_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" 60 "60-pcloud-list-after"; then
    fail "the pCloud folder list is empty after the copy"
    screenshot "60-no-pcloud-list-after"
    finish
fi

ui_tap_text "$FIXTURE_PCLOUD_DESTINATION_NAME" "60-open-destination"
sleep 3
if ui_wait_text "$FIXTURE_COPY_SOURCE_FILE" 60 "60-pcloud-grid"; then
    pass "$FIXTURE_COPY_SOURCE_FILE is in the pCloud folder"
else
    fail "the copy is on pCloud but the app does not show it; the folder was not scanned again"
    screenshot "60-not-shown"
fi

step "and the share still has what it had"
# the point of copying rather than moving. Read from the host side: the container serves this very
# directory, so what is here is what the share has
if [ -f "$source_file_on_host" ] \
    && [ "$(stat -c %s "$source_file_on_host")" = "$source_size_before" ] \
    && [ "$(stat -c %Y "$source_file_on_host")" = "$expected_modified" ]; then
    pass "$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE is untouched on the share"
else
    fail "the file on the share changed; nothing here may write to it"
fi

screenshot "60-done"
finish
