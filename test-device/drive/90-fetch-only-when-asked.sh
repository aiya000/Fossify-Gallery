#!/usr/bin/env bash
# #87: the list is fetched again only when the settings say so -- for both events that can ask, and
# for both storages alike.
#
# What #87 is really about is the spinner, and the spinner is not what this asserts. It is up only
# for as long as the cache takes to be read, and uiautomator gives it no name to look for, so a
# check on it would be a race dressed as a test. What is left is the question the spinner is now
# raised from the answer to, instead of before it was asked: was anything fetched at all.
#
# Both events go through MainActivity.startRemoteScans(). Kept apart at their call sites the two
# had drifted, and the pull had ended up asking SmbSyncPolicy while never asking PCloudSyncPolicy
# at all -- so the pCloud half below is the one that fails on a build without the fix, and the SMB
# half is what keeps the gathering of the rules from quietly losing one on the way.
#
# Every case is run twice, once with its setting off and once on. The "on" half is what stops the
# "off" half passing on an app that fetches nothing ever.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# long enough that a scan which was going to start has started -- it is queued the moment the event
# is handled -- and short enough that six cases do not add up to a coffee break
SETTLE=15

seed() {
    env "$@" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

expect_no_scan() {
    local label="$1"
    if wait_for_service RemoteScanService "$SETTLE"; then
        fail "$label (a scan started)"
        screenshot "$label"
    else
        pass "$label"
    fi
}

expect_scan() {
    local label="$1"
    if wait_for_service RemoteScanService "$SETTLE"; then
        pass "$label"
    else
        fail "$label (nothing started)"
        screenshot "$label"
    fi
}

# A pull is an ordinary drag, which `input swipe` can make -- unlike the sideways one between
# storages, whose detection wants more move events than a synthesised swipe carries
pull_to_refresh() {
    "${ADB[@]}" shell input swipe 540 700 540 1700 400
    sleep 2
}

# ================================================================ the share

step "arriving at the share fetches nothing while the setting is off"
seed
FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4
switch_storage_to "Network share" "90a-arrive"
expect_no_scan "90a: the switch walked nothing"

step "and fetches when the setting is on"
seed FIXTURE_RESCAN_ON_STORAGE_SWITCH=true
FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4
switch_storage_to "Network share" "90b-arrive"
expect_scan "90b: the switch walked the share"

step "a pull on the share fetches nothing while the setting is off"
seed
FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4
pull_to_refresh
expect_no_scan "90c: the pull walked nothing"

step "and fetches when the setting is on"
seed FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true
FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4
pull_to_refresh
expect_scan "90d: the pull walked the share"

# ================================================================ pCloud
#
# This is the half that was missing. A pull rescanned pCloud whenever pCloud was on screen, with no
# policy asked at all, so there was no setting to turn it off with -- which is what the setting
# added here is, and what these two cases are for.
#
# pCloud is fixture/pcloud-stub.py, the same as 60-copy-to-pcloud.sh uses; see the comment at the
# top of it for why there is no account behind this. Whether the app fetched is read off the
# requests the stub was sent, which is pCloud's own answer rather than the app's

requests_log="$RUN_DIR/pcloud-requests.log"

step "building a pCloud account for the pull to fetch"
rm -rf "$FIXTURE_PCLOUD_DIR"
mkdir -p "$FIXTURE_PCLOUD_DIR/$FIXTURE_PCLOUD_DESTINATION_NAME"
cp "$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE" \
    "$FIXTURE_PCLOUD_DIR/$FIXTURE_PCLOUD_DESTINATION_NAME/seed.jpg"

python3 "$TEST_DEVICE_DIR/fixture/pcloud-stub.py" \
    --root "$FIXTURE_PCLOUD_DIR" \
    --port "$FIXTURE_PCLOUD_PORT" \
    --token "$FIXTURE_PCLOUD_TOKEN" \
    --log "$requests_log" \
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

# a listing of the account, under either name the scanner asks by: the first sync of a fresh
# install has no diff id to carry, so it lists the whole account instead of asking for a diff
fetches_of_the_account() {
    rg -c -N 'GET (diff|listfolder)' "$requests_log" 2> /dev/null || echo 0
}

seed_pcloud() {
    env "$@" \
        FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
        FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
        "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

step "a pull on pCloud fetches nothing while the setting is off"
seed_pcloud
FIXTURE_STORAGE_FILTER=2
app_start
sleep 4
before="$(fetches_of_the_account)"
pull_to_refresh
sleep "$SETTLE"
after="$(fetches_of_the_account)"
if [ "$before" = "$after" ]; then
    pass "90e: the pull asked pCloud for nothing"
else
    fail "90e: the pull listed the account although the setting is off ($before -> $after in $requests_log)"
    screenshot "90e"
fi

step "and fetches when the setting is on"
seed_pcloud FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true
FIXTURE_STORAGE_FILTER=2
app_start
sleep 4
before="$(fetches_of_the_account)"
pull_to_refresh
sleep "$SETTLE"
after="$(fetches_of_the_account)"
if [ "$after" -gt "$before" ]; then
    pass "90f: the pull listed the account"
else
    fail "90f: the pull asked pCloud for nothing although the setting is on (still $before in $requests_log)"
    screenshot "90f"
fi

finish
