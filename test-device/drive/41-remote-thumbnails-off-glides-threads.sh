#!/usr/bin/env bash
# #165: the thumbnails of a remote storage are fetched on threads of the app's own, never on
# Glide's, so that this device's tiles are never stuck behind a fetch from the network.
#
# What went wrong before: Glide decodes every thumbnail on one small pool, and a fetcher that
# had started a network read held its thread until the read was through, Glide's cancel() being
# only a flag. A few of those and the pool was gone, and the folder list of this device stayed
# blank for as long as the share took to answer. Now a remote fetcher hands its read to
# RemoteThumbnails and gives Glide's thread back at once.
#
# This is the pCloud half, against the stub, because it is seconds: the account is listed, a
# folder opened, and every tile has to draw as before (thumbs.py) while the log says each read
# ran on a "remote-thumbnails-N" thread and none on a "glide-" one. The share's half is in
# 40-share-thumbnails.sh, which already walks the share and opens a folder of it.
#
# The thread name is the witness because the slowness itself is not something a run on a fast
# local network can reproduce: the fix is about where the waiting happens, not how long it is
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

stub_pid=""
leave_things_as_they_were() {
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
}
trap leave_things_as_they_were EXIT

requests_log="$RUN_DIR/pcloud-requests.log"

# the fixture's screens: a handful of JPEGs, which the stub renders thumbnails for
FOLDER="Screens"

step "building a pCloud account of one folder of pictures"
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$FIXTURE_PCLOUD_DIR/$FOLDER"
cp "$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER"/*.jpg "$FIXTURE_PCLOUD_DIR/$FOLDER/"
expected="$(ls -1 "$FIXTURE_PCLOUD_DIR/$FOLDER" | wc -l)"
note "$expected pictures in $FOLDER"

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

step "seeding, and listing the account from the menu"
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
FIXTURE_STORAGE_FILTER=2
logcat_reset
app_start
sleep 4
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "41-menu"
if ! ui_wait_exact_text "$FOLDER" 60 "41-list"; then
    fail "$FOLDER never turned up in the folder list"
    screenshot "41-no-folder"
    finish
fi

step "opening $FOLDER"
ui_tap_exact_text "$FOLDER" "41-open" || finish
sleep 3

# the filenames, so that a tile that drew nothing can be named rather than counted
step "turning the filenames on"
dump="$(ui_dump "41-toolbar")"
if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 2
else
    fail "the filename toggle is not on screen (view tree in $dump)"
fi

step "waiting for the thumbnails"
waited=0
while [ "$waited" -lt 60 ]; do
    screenshot "41-tiles"
    dump="$(ui_dump "41-tiles")"
    if python3 "$DRIVE_DIR/thumbs.py" "$RUN_DIR/41-tiles.png" "$dump" --expect "$expected" > /dev/null 2>&1; then
        break
    fi
    sleep 3
    waited=$((waited + 3))
done

step "what drew, and what did not"
if python3 "$DRIVE_DIR/thumbs.py" "$RUN_DIR/41-tiles.png" "$dump" --expect "$expected" | sed 's/^/     /'; then
    pass "every one of the $expected pictures has a thumbnail"
else
    fail "a picture drew nothing; the screenshot is $RUN_DIR/41-tiles.png and the view tree is $dump"
fi

step "whose threads pCloud was read on"
capture_log "41-threads"
reads="$(captured_log | rg -c 'Read the thumbnail of pcloud:' || true)"
on_own="$(captured_log | rg -c 'Read the thumbnail of pcloud:.* on remote-thumbnails-' || true)"
on_glide="$(captured_log | rg -c 'Read the thumbnail of pcloud:.* on glide-' || true)"
if [ "${reads:-0}" -gt 0 ] && [ "${on_own:-0}" -eq "${reads:-0}" ] && [ "${on_glide:-0}" -eq 0 ]; then
    pass "every one of the $reads reads of pCloud ran on the app's own thumbnail threads"
elif [ "${reads:-0}" -eq 0 ]; then
    fail "no read of pCloud was logged, so there is nothing to say whose thread it ran on"
else
    fail "$on_own of $reads reads of pCloud ran on the app's own threads, $on_glide on Glide's"
fi

finish
