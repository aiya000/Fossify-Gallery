#!/usr/bin/env bash
# A pull at the top of the folder list asks before it rescans a remote storage, when that
# storage's setting says so; inside a group, and with the setting off, it goes ahead.
#
# A pull is the easiest gesture in the app to make by accident, and at the top of the list it
# reaches the whole of a storage: on the share a walk of every folder, minutes of work that goes
# on in the background and slows everything else while it runs. The setting puts one question in
# front of it. A no runs nothing, a yes runs the rescan the gesture would have, and neither is
# remembered: the next pull asks again, which is what a question about an accident is for.
#
# The share half is driven against the share, with the scan starting as the witness, the same as
# 92. The group half is driven against the pCloud stub, as 92 drives its folder half: a group to
# open needs a list with rows in it, which is a second on the stub and a five-minute walk on the
# share, and the stub's request log says whether pCloud was asked. The group Trips is given a
# pCloud folder for it (FIXTURE_GROUP_EXTRA_MEMBER), as 93 does
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# long enough that a scan which was going to start has started -- it is queued the moment the
# answer is given -- and short enough that the cases do not add up to a coffee break
SETTLE=15

# what the two screens log when a pull reaches them, see MainActivity.refreshDirectories() and
# MediaActivity.refreshMedia(): a pull the switch has turned off never gets that far
PULLED_LIST="Pulled the folder list"
PULLED_FOLDER="Pulled the folder pcloud:/"

seed() {
    env "$@" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

stub_pid=""
leave_things_as_they_were() {
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
}
trap leave_things_as_they_were EXIT

# ---------------------------------------------------------------- the question

# A pull is an ordinary drag, which `input swipe` can make -- unlike the sideways one between
# storages, whose detection wants more move events than a synthesised swipe carries
pull_to_refresh() {
    "${ADB[@]}" shell input swipe 540 700 540 1700 400
    sleep 2
}

# the dialog is its own window, so a dump sees it whatever is behind it. "Rescan" is the word
# every wording of the question opens with, and no row of the list carries it
expect_question() {
    local label="$1"
    if ui_wait_text "Rescan" 10 "$label-question"; then
        pass "$label: asked before the rescan"
        return 0
    fi

    fail "$label: nothing asked before the rescan"
    screenshot "$label-no-question"
    return 1
}

# what the question names is pinned along with the question itself
expect_question_names() {
    local label="$1" scope="$2" dump
    dump="$(ui_dump "$label-scope")"
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "$scope" > /dev/null; then
        pass "$label: the question names $scope"
    else
        fail "$label: the question does not say what it would rescan (view tree in $dump)"
    fi
}

refute_question() {
    local label="$1" dump
    sleep 4
    dump="$(ui_dump "$label-no-question")"
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "Rescan" > /dev/null; then
        fail "$label: asked although nothing should have been (view tree in $dump)"
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
    else
        pass "$label: nothing was asked"
    fi
}

answer() {
    local word="$1" label="$2"
    ui_tap_exact_text "$word" "$label-$word" || return 1
    sleep 1
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

# ================================================================ the share, on a pull

step "a pull at the top of the share asks first, and No walks nothing"
seed FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true FIXTURE_ASK_BEFORE_PULL_TO_REFRESH=true
FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4
pull_to_refresh
if expect_question "96a"; then
    expect_question_names "96a" "whole network share"
    answer "No" "96a" || finish
fi
expect_no_scan "96a: No walked nothing"

step "pulled again it asks again -- a no is not remembered -- and Yes walks the whole share"
pull_to_refresh
if expect_question "96b"; then
    answer "Yes" "96b" || finish
fi
expect_scan "96b: Yes walked the share"

step "with the setting off, a pull walks the share with nothing asked"
seed FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true FIXTURE_ASK_BEFORE_PULL_TO_REFRESH=false
FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4
pull_to_refresh
refute_question "96c"
expect_scan "96c: the share was walked on the gesture alone"
capture_log "96c"
expect_log "$PULLED_LIST" "96c: the gesture reached the list"

# The share's "pull to refresh" switch is the gesture on the share: off, the list cannot be
# pulled there at all, rather than pulled to re-read a cache nobody asked for. That the gesture
# never reached the list is read off the log line refreshDirectories() writes
step "and with the share's pull off, the share cannot be pulled: nothing asked, nothing walked, nothing reached"
seed FIXTURE_RESCAN_ON_PULL_TO_REFRESH=false FIXTURE_ASK_BEFORE_PULL_TO_REFRESH=true
FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4
pull_to_refresh
refute_question "96d"
expect_no_scan "96d: nothing was walked"
capture_log "96d"
refute_log "$PULLED_LIST" "96d: the gesture never reached the list"

# ================================================================ pCloud, at the top and inside a group
#
# pCloud is fixture/pcloud-stub.py, the same as 92 and 93 use; see the comment at the top of it
# for why there is no account behind this. Whether the app fetched is read off the requests the
# stub was sent, which is pCloud's own answer rather than the app's

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

# a listing of the account or of one folder of it, under either name the scanner asks by: the
# first sync of a fresh install has no diff id to carry, so it lists the whole account instead
# of asking for a diff, and a folder of a group is listed by name
fetches() {
    rg -c -N 'GET (diff|listfolder)' "$requests_log" 2> /dev/null || echo 0
}

seed_pcloud() {
    env "$@" \
        FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
        FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
        FIXTURE_GROUP_EXTRA_MEMBER="pcloud:/InGroup" \
        "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

# whether the stub was asked for a listing between two readings of its log
expect_fetched() {
    local label="$1" before="$2" after
    after="$(fetches)"
    if [ "$after" -gt "$before" ]; then
        pass "$label ($before -> $after listings in $requests_log)"
    else
        fail "$label (still $before listings in $requests_log)"
        screenshot "$label"
    fi
}

expect_not_fetched() {
    local label="$1" before="$2" after
    after="$(fetches)"
    if [ "$after" = "$before" ]; then
        pass "$label"
    else
        fail "$label ($before -> $after listings in $requests_log)"
        screenshot "$label"
    fi
}

step "a pull at the top of pCloud asks first, and No lists nothing"
seed_pcloud FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true FIXTURE_ASK_BEFORE_PULL_TO_REFRESH=true
FIXTURE_STORAGE_FILTER=2
app_start
sleep 4
before="$(fetches)"
pull_to_refresh
if expect_question "96e"; then
    expect_question_names "96e" "pCloud"
    answer "No" "96e" || finish
fi
sleep "$SETTLE"
expect_not_fetched "96e: No asked pCloud for nothing" "$before"

step "Yes lists the account"
before="$(fetches)"
pull_to_refresh
if expect_question "96f"; then
    answer "Yes" "96f" || finish
fi
sleep "$SETTLE"
expect_fetched "96f: Yes listed the account" "$before"

step "inside the group $FIXTURE_GROUP_PARENT_NAME a pull lists its folder with nothing asked"
if ! ui_wait_text "$FIXTURE_GROUP_PARENT_NAME" 30 "96g-list"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the folder list"
    screenshot "96g-no-group"
    finish
fi

ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "96g-open" || finish
sleep 2
before="$(fetches)"
pull_to_refresh
refute_question "96g"
sleep "$SETTLE"
expect_fetched "96g: the group's folder was listed on the gesture alone" "$before"

# ================================================================ pCloud, inside a folder, and with the pull off

step "inside the folder InGroup a pull lists that folder"
if ! ui_wait_exact_text "InGroup" 30 "96h-group"; then
    fail "InGroup is not in the group $FIXTURE_GROUP_PARENT_NAME, so there is no folder to open"
    screenshot "96h-no-folder"
    finish
fi

logcat_reset
ui_tap_exact_text "InGroup" "96h-open" || finish
sleep 3
before="$(fetches)"
pull_to_refresh
sleep "$SETTLE"
expect_fetched "96h: the folder was listed on the gesture alone" "$before"
capture_log "96h"
expect_log "$PULLED_FOLDER" "96h: the gesture reached the folder"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

# pCloud's "pull to refresh" switch is the gesture on pCloud, the same as the share's: off,
# neither the list nor a folder of it can be pulled. The account is listed from the menu first,
# so that there is a folder to open
step "with pCloud's pull off, neither the list nor a folder can be pulled"
seed_pcloud FIXTURE_RESCAN_ON_PULL_TO_REFRESH=false FIXTURE_ASK_BEFORE_PULL_TO_REFRESH=true
FIXTURE_STORAGE_FILTER=2
app_start
sleep 4
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "96i-menu"
if ! ui_wait_exact_text "Outside" 60 "96i-list"; then
    fail "Outside never turned up in the folder list, so there is no folder to open"
    screenshot "96i-no-folder"
    finish
fi

sleep "$SETTLE"
logcat_reset
before="$(fetches)"
pull_to_refresh
refute_question "96i"
capture_log "96i-list"
refute_log "$PULLED_LIST" "96i: the gesture never reached the list"

ui_tap_exact_text "Outside" "96i-open" || finish
sleep 3
pull_to_refresh
sleep "$SETTLE"
capture_log "96i-folder"
refute_log "$PULLED_FOLDER" "96i: the gesture never reached the folder"
expect_not_fetched "96i: pCloud was asked for nothing by either pull" "$before"

finish
