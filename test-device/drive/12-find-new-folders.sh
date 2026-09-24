#!/usr/bin/env bash
# #127: "Find new folders" puts the folders of the remote storages that this app has no row for
# yet into the folder list, without the full rescan a share of a thousand folders needs.
#
# What it must do, and what it must not: a folder that arrived on the share since the last walk
# is found, with its photo, and is in the list when the search is over; a folder the app already
# knows is listed to see what lies under it and nothing more -- a photo that arrived in one is
# not read, its row is not rewritten, and the walk does not report itself as a walk of the share.
# pCloud is asked the same question through its diff: on an account never listed, every folder
# is new, and asked again straight after, nothing is.
#
# The witnesses are the log lines the service writes when a search is over -- the counts of new
# folders and files, which is the question being asked -- and the folder list afterwards. The
# absence of "Walked the share:" is what says a full walk did not happen in between.
#
# Nothing in the fixture's counts may be added to -- 10-scan-whole-share.sh asserts on them --
# so this script brings its own folder and its own file and takes them away again on the way in
# and on the way out. pCloud is fixture/pcloud-stub.py, as everywhere else
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

arrived_dir="$FIXTURE_SHARE_DIR/$FIXTURE_ARRIVED_FOLDER"
late_file="$FIXTURE_SHARE_DIR/$FIXTURE_LATE_FOLDER/$FIXTURE_LATE_FILE"

stub_pid=""
leave_things_as_they_were() {
    rm -rf "$arrived_dir"
    rm -f "$late_file"
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
}
trap leave_things_as_they_were EXIT
rm -rf "$arrived_dir"
rm -f "$late_file"

# ---------------------------------------------------------------- pCloud, through the stub

requests_log="$RUN_DIR/pcloud-requests.log"

step "building a pCloud account with one folder in it"
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$FIXTURE_PCLOUD_DIR/$FIXTURE_PCLOUD_DESTINATION_NAME"
cp "$seed_image" "$FIXTURE_PCLOUD_DIR/$FIXTURE_PCLOUD_DESTINATION_NAME/seed.jpg"

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

# ---------------------------------------------------------------- the share, walked once

step "seeding, signed in to pCloud, and walking the share so that its folders are known"
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "12-rescan-menu"
if ! wait_for_log "Walked the share:" 900 "12-walk"; then
    fail "the share was never walked, so nothing is known to be new against"
    screenshot "12-no-walk"
    finish
fi

# one search: the menu item, then both storages' answers in the log. The share is asked first,
# pCloud queues behind it
find_new_folders() {
    local name="$1"
    logcat_reset
    open_overflow_menu
    sleep 1
    if ! ui_tap_text "Find new folders" "$name-menu"; then
        screenshot "$name-no-menu-item"
        return 1
    fi

    if ! wait_for_log "Found [0-9]+ new folders on the share" 900 "$name-share"; then
        fail "$name: the search of the share never reported"
        screenshot "$name-no-share-report"
        return 1
    fi

    if ! wait_for_log "Found [0-9]+ new folders on pCloud" 120 "$name-pcloud"; then
        fail "$name: the search of pCloud never reported"
        screenshot "$name-no-pcloud-report"
        return 1
    fi

    sleep 2
    capture_log "$name"
}

# ---------------------------------------------------------------- a folder that arrived

step "a folder that arrived on the share since the walk is found, and only it"
mkdir -p "$arrived_dir"
cp "$seed_image" "$arrived_dir/$FIXTURE_ARRIVED_FILE"
find_new_folders "12a" || finish

expect_log "Found 1 new folders on the share, 1 files, 0 folders skipped" "12a: the one folder that arrived, with its one photo, and nothing else"
expect_log "Found 1 new folders on pCloud, 1 files" "12a: pCloud, never listed before, has its one folder found"
refute_log "Walked the share:" "12a: the share was not walked again for it"

if ui_wait_exact_text "$FIXTURE_ARRIVED_FOLDER" 60 "12a-list"; then
    pass "12a: $FIXTURE_ARRIVED_FOLDER is in the folder list"
else
    fail "12a: $FIXTURE_ARRIVED_FOLDER is not in the folder list"
    screenshot "12a-no-row"
fi

# ---------------------------------------------------------------- a file in a known folder

step "a photo that arrived in a folder the app already knows is not what this is for"
cp "$seed_image" "$late_file"
find_new_folders "12b" || finish

expect_log "Found 0 new folders on the share, 0 files, 0 folders skipped" "12b: nothing new on the share: the known folder was not read again"
expect_log "Found 0 new folders on pCloud, 0 files" "12b: nothing new on pCloud either, asked again"
refute_log "Walked the share:" "12b: and still no walk of the share"

screenshot "12-done"
finish
