#!/usr/bin/env bash
# Rule 1 of #59: what a sideways swipe between storages calls off, and what it leaves alone.
#
# This is the check that costs the most by hand. Each case is a scan of several minutes that has
# to be interrupted at the right moment, and what it proves is one comparison coming out one way.
#
# Read RemoteScanScheduler before changing the expectations here. The ranks are
# AUTO(0) < SWITCH(1) < MANUAL(2) < TRANSFER(3), and leftBehind() drops only what ranks below
# SWITCH. So:
#
# - a scan the settings started on launch ranks AUTO and is called off by a swipe
# - a scan the arrival at this storage started ranks SWITCH and is NOT called off by a later swipe
# - a scan asked for from the menu ranks MANUAL and is never called off by a swipe
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed() {
    # each case starts from an app with nothing cached, so that what the log says belongs to it
    env "$@" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

# ---------------------------------------------------------------- rule 1-a

step "rule 1-a: a swipe calls off a scan the settings started"
seed FIXTURE_RESCAN_ON_LAUNCH=true FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start

if ! wait_for_service RemoteScanService 60; then
    fail "the scan the settings ask for on launch never started"
    screenshot "20a-no-scan"
else
    screenshot "20a-scanning"
    note "the scan is running; swiping away from the share"
    swipe_storage_forward
    sleep 3
    screenshot "20a-after-swipe"

    expect_log "left SMB behind" "the list said it had left the share"
    expect_log "called off" "the scan the settings started was called off"
    logcat_dump "20a" > /dev/null
fi

# ---------------------------------------------------------------- rule 1-b

step "rule 1-b: a swipe leaves a scan the user asked for alone"
seed FIXTURE_STORAGE_FILTER=4
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "20b-menu"

if ! wait_for_service RemoteScanService 60; then
    fail "the rescan asked for from the menu never started"
    screenshot "20b-no-scan"
else
    screenshot "20b-scanning"
    note "the scan is running; swiping away from the share"
    swipe_storage_forward
    sleep 3
    screenshot "20b-after-swipe"

    refute_log "called off" "the manual scan survived the swipe"

    # and it has to run to the end, not merely survive the moment of the swipe
    if wait_for_log "Walked the share:" 300 "20b"; then
        pass "the manual scan finished after the swipe"
        expect_log "Walked the share: [0-9]+ folders, $FIXTURE_MEDIA files" "and it found everything the fixture holds"
    else
        fail "the manual scan never finished"
    fi
    logcat_dump "20b" > /dev/null
fi

# ---------------------------------------------------------------- what the ranks actually say

step "a scan the arrival started ranks with the switch, so a later swipe does not call it off"
note "this is the behaviour as written, not a wish: leftBehind() drops only what ranks below SWITCH"
seed FIXTURE_RESCAN_ON_STORAGE_SWITCH=true FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4

# arrive at the share, which is what starts the scan at the switch's own rank
swipe_storage_back
if ! wait_for_service RemoteScanService 60; then
    fail "arriving at the share did not start the scan the settings ask for"
    screenshot "20c-no-scan"
else
    screenshot "20c-scanning"
    swipe_storage_forward
    sleep 3
    screenshot "20c-after-swipe"

    refute_log "called off" "the scan the arrival started survived leaving again"
    logcat_dump "20c" > /dev/null
fi

finish
