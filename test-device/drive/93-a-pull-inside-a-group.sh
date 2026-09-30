#!/usr/bin/env bash
# A pull on the folder list reaches what is on screen: every folder at the top of the list, and
# only the group's folders inside a group -- on every storage alike.
#
# What is checked, with both remote storages set to rescan on a pull and the list on All storages:
#
# - at the top, a pull walks the whole share and lists the whole pCloud account, as it always has
# - inside the group Trips -- the share's Osaka, its subgroup Kyoto with the share's Kyoto, and
#   the pCloud folder InGroup -- a pull reads those folders and nothing else:
#     - the share: its two folders are rescanned one by one, and the share is not walked
#     - pCloud: InGroup is listed, Outside is not, and the account is not asked for its diff
#     - this device's recheck goes through the group's three folders, not the whole list
#
# pCloud is fixture/pcloud-stub.py, as in 92; whether it was asked is read off its request log.
# The share has to be walked once before the group has anything in it to show, and the pull at
# the top is that walk
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

# ---------------------------------------------------------------- pCloud

requests_log="$RUN_DIR/pcloud-requests.log"

step "building a pCloud account of two folders, one of them in the group"
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$FIXTURE_PCLOUD_DIR/InGroup" "$FIXTURE_PCLOUD_DIR/Outside"
cp "$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE" "$FIXTURE_PCLOUD_DIR/InGroup/in-group.jpg"
cp "$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE" "$FIXTURE_PCLOUD_DIR/Outside/outside.jpg"

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

# the stub's request log from line $1 on
requests_since() {
    tail -n "+$(($1 + 1))" "$requests_log" 2> /dev/null || true
}

request_count() {
    rg -c -N '' "$requests_log" 2> /dev/null || echo 0
}

pull_to_refresh() {
    "${ADB[@]}" shell input swipe 540 700 540 1700 400
    sleep 2
}

# ---------------------------------------------------------------- the top of the list

step "seeding, with both remote storages rescanned on a pull and the pCloud folder in the group"
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true \
    FIXTURE_GROUP_EXTRA_MEMBER="pcloud:/InGroup" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

FIXTURE_STORAGE_FILTER=3
app_start
sleep 3

step "a pull at the top walks the whole share and lists the whole pCloud account"
logcat_reset
before="$(request_count)"
pull_to_refresh
if ! wait_for_log "Walked the share:" 900 "93-top-walk"; then
    fail "a pull at the top did not walk the share"
    screenshot "93-top-no-walk"
    finish
fi
pass "the share was walked"

# the scans share one queue, so pCloud's comes after the walk rather than beside it
asked_for_account=0
for _ in $(seq 1 30); do
    if requests_since "$before" | rg -q 'GET listfolder .*"path": "/"'; then
        asked_for_account=1
        break
    fi
    sleep 2
done

if [ "$asked_for_account" = "1" ]; then
    pass "pCloud was asked for the whole account"
else
    fail "pCloud was never asked for the whole account, see $requests_log"
fi

# ---------------------------------------------------------------- inside the group

step "opening the group $FIXTURE_GROUP_PARENT_NAME"
sleep 3
if ! ui_wait_text "$FIXTURE_GROUP_PARENT_NAME" 30 "93-list"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the folder list"
    screenshot "93-no-group"
    finish
fi

ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "93-open-group" || finish
sleep 3
if ! ui_wait_text "InGroup" 20 "93-in-group"; then
    fail "the group does not show the pCloud folder InGroup"
    screenshot "93-group"
    finish
fi

step "a pull inside the group reads the group's folders, and only them"
logcat_reset
before="$(request_count)"
pull_to_refresh
if ! wait_for_log "Rescanned [0-9]+ folders of the share" 120 "93-group-rescan"; then
    fail "the share's folders of the group were not rescanned"
    screenshot "93-group-no-rescan"
    finish
fi

sleep 5
capture_log "93-group"
expect_log "Rescanned 2 folders of the share" "the share: the group's two folders, Osaka and Kyoto"
refute_log "Walked the share:" "the share was not walked"
expect_log "Rechecking 3 of [0-9]+ folders" "this device's recheck went through the group's three folders"

asked="$(requests_since "$before")"
note "pCloud was sent: $(echo "$asked" | tr '\n' ' ')"
if echo "$asked" | rg -q 'GET listfolder .*InGroup'; then
    pass "pCloud: InGroup was listed"
else
    fail "pCloud: InGroup was not listed, see $requests_log"
fi

if echo "$asked" | rg -q 'Outside|GET diff'; then
    fail "pCloud: more than the group was asked for, see $requests_log"
else
    pass "pCloud: neither Outside nor the account's diff was asked for"
fi

finish
