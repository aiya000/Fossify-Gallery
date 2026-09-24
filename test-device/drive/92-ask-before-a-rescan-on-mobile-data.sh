#!/usr/bin/env bash
# #124: on mobile data, with "unmetered only" on, a rescan the user started with a gesture is
# asked about instead of being skipped without a word.
#
# Before, every event answered the same on a metered network: no, silently. Out of the house the
# only way to a rescan was the settings screen, twice. Now the pull on the folder list and the
# opening of a folder put one question, with the scope named -- the whole share, or this one
# folder -- and a yes runs that one rescan, a no runs nothing, and neither touches the setting.
# The events nobody sees (launch, the interval, after a write) still ask nothing and run nothing.
#
# The network is the emulator's own. Its Wi-Fi is what the app reads as unmetered; with it off,
# the emulated mobile network takes over, which the system counts as metered -- and 10.0.2.2,
# the host, is still reachable over it, so the share and the stub are there for a yes to walk.
# The Wi-Fi is put back on the way out, whatever happened.
#
# The share is the storage the question matters for -- a thousand folders is what the setting is
# there to keep off mobile data -- so the pull is driven against it, and the scan starting is the
# witness. The folder half is driven against the pCloud stub instead: a folder to open needs a
# list with rows in it, which on the share is a five-minute walk and on the stub is a second,
# and the stub's request log says whether a rescan happened, which is pCloud's own answer
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

# long enough that a scan which was going to start has started -- it is queued the moment the
# answer is given -- and short enough that the cases do not add up to a coffee break
SETTLE=15

seed() {
    env "$@" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

# ---------------------------------------------------------------- the network

# The transport the default network is on, read out of the connectivity service: the line naming
# the active network's id, then the agent with that id and what it carries
default_transport_is() {
    local transport="$1" dump id
    dump="$("${ADB[@]}" shell dumpsys connectivity --short | tr -d '\r')"
    id="$(printf '%s\n' "$dump" | rg -o 'Active default network: [0-9]+' | rg -o '[0-9]+$' || true)"
    [ -n "$id" ] && printf '%s\n' "$dump" | rg -q "network\{$id\}.*Transports: $transport\b"
}

wait_for_transport() {
    local transport="$1" waited=0
    while [ "$waited" -lt 60 ]; do
        if default_transport_is "$transport"; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done

    fail "the emulator never came up on $transport"
    return 1
}

on_mobile_data() {
    "${ADB[@]}" shell svc wifi disable
    wait_for_transport CELLULAR
    note "on mobile data"
}

on_wifi() {
    "${ADB[@]}" shell svc wifi enable
    wait_for_transport WIFI
    note "on Wi-Fi"
}

stub_pid=""
leave_things_as_they_were() {
    "${ADB[@]}" shell svc wifi enable > /dev/null 2>&1 || true
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

# the dialog is its own window, so a dump sees it whatever is behind it
expect_question() {
    local label="$1"
    if ui_wait_text "mobile data" 10 "$label-question"; then
        pass "$label: asked before the rescan"
        return 0
    fi

    fail "$label: nothing asked about mobile data"
    screenshot "$label-no-question"
    return 1
}

# what the question names is what decides whether a yes is a few listings or a walk of a
# thousand folders, so it is pinned along with the question itself
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
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "mobile data" > /dev/null; then
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

step "a pull on the share over mobile data asks first, and No walks nothing"
seed FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true FIXTURE_RESCAN_ON_UNMETERED_ONLY=true
FIXTURE_STORAGE_FILTER=4
on_mobile_data
logcat_reset
app_start
sleep 4
pull_to_refresh
if expect_question "92a"; then
    expect_question_names "92a" "whole network share"
    answer "No" "92a" || finish
fi
expect_no_scan "92a: No walked nothing"

step "and Yes walks the whole share, nothing in the settings having changed"
pull_to_refresh
if expect_question "92b"; then
    answer "Yes" "92b" || finish
fi
expect_scan "92b: Yes walked the share"

# ================================================================ pCloud, on a pull
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
# of asking for a diff, and a folder opened on its own is listed by name
fetches() {
    rg -c -N 'GET (diff|listfolder)' "$requests_log" 2> /dev/null || echo 0
}

seed_pcloud() {
    env "$@" \
        FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
        FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
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

step "a pull on pCloud over mobile data asks first, and No lists nothing"
seed_pcloud FIXTURE_RESCAN_ON_PULL_TO_REFRESH=true FIXTURE_RESCAN_ON_UNMETERED_ONLY=true
FIXTURE_STORAGE_FILTER=2
app_start
sleep 4
before="$(fetches)"
pull_to_refresh
if expect_question "92c"; then
    answer "No" "92c" || finish
fi
sleep "$SETTLE"
expect_not_fetched "92c: No asked pCloud for nothing" "$before"

step "Yes lists the account"
before="$(fetches)"
pull_to_refresh
if expect_question "92d"; then
    answer "Yes" "92d" || finish
fi
sleep "$SETTLE"
expect_fetched "92d: Yes listed the account" "$before"

step "and the next pull on the same trip is not asked again"
before="$(fetches)"
pull_to_refresh
refute_question "92e"
sleep "$SETTLE"
expect_fetched "92e: the yes was remembered and the account listed again" "$before"

# ================================================================ pCloud, on opening a folder

step "a fresh start on Wi-Fi: the account listed from the menu, and a folder opened without a question"
seed_pcloud FIXTURE_RESCAN_ON_FOLDER_OPEN=true FIXTURE_RESCAN_ON_UNMETERED_ONLY=true
FIXTURE_STORAGE_FILTER=2
on_wifi
app_start
sleep 4
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "92f-menu"
if ! ui_wait_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" 60 "92f-list"; then
    fail "$FIXTURE_PCLOUD_DESTINATION_NAME never turned up in the folder list, so there is no folder to open"
    screenshot "92f-no-folder"
    finish
fi

before="$(fetches)"
ui_tap_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" "92f-open" || finish
refute_question "92f"
sleep "$SETTLE"
expect_fetched "92f: on Wi-Fi the folder was rescanned with nothing asked" "$before"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

step "the same folder opened over mobile data asks, for that folder alone, and No lists nothing"
on_mobile_data
before="$(fetches)"
ui_tap_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" "92g-open" || finish
if expect_question "92g"; then
    expect_question_names "92g" "folder"
    answer "No" "92g" || finish
fi
sleep "$SETTLE"
expect_not_fetched "92g: No listed nothing" "$before"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

step "opened again it asks again -- a no is not remembered -- and Yes lists the folder"
before="$(fetches)"
ui_tap_exact_text "$FIXTURE_PCLOUD_DESTINATION_NAME" "92h-open" || finish
if expect_question "92h"; then
    answer "Yes" "92h" || finish
fi
sleep "$SETTLE"
expect_fetched "92h: Yes listed the folder" "$before"

finish
