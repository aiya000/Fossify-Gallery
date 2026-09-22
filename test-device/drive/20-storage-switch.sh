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
#
# The list opens on this device, so the scan the settings start on launch is not a scan of what is
# on screen: it is started for a storage that is set up, and this script is where that is seen --
# rule 1-a only has something to call off because the launch scan runs while the device is showing.
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
seed FIXTURE_RESCAN_ON_LAUNCH=true
logcat_reset
app_start

if ! wait_for_service RemoteScanService 60; then
    fail "the scan the settings ask for on launch never started"
    screenshot "20a-no-scan"
else
    screenshot "20a-scanning"
    note "the scan is running; leaving the share"
    switch_storage_to "This device" "20a-leave"
    sleep 3
    screenshot "20a-after-swipe"
    capture_log "20a"

    # the scheduler says so only when it actually calls something off, so this one line is the
    # whole of rule 1-a
    expect_log "left SMB behind; calling off" "the scheduler called off the scan the settings started"
    expect_log "was called off" "and the walk stopped without writing anything"
fi

# ---------------------------------------------------------------- rule 1-b

step "rule 1-b: a swipe leaves a scan the user asked for alone"
seed
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
    note "the scan is running; leaving the share"
    switch_storage_to "This device" "20b-leave"
    sleep 3
    screenshot "20b-after-swipe"
    capture_log "20b-during"

    refute_log "called off" "the manual scan survived being left"

    # and it has to run to the end, not merely survive the moment of the swipe
    if wait_for_log "Walked the share:" 600 "20b"; then
        capture_log "20b"
        pass "the manual scan finished after the storage was left"
        expect_log "Walked the share: [0-9]+ folders, $FIXTURE_MEDIA files" "and it walked everything the fixture holds"
    else
        fail "the manual scan never finished"
    fi
fi

# ---------------------------------------------------------------- what the ranks actually say

step "a scan the arrival started ranks with the switch, so a later swipe does not call it off"
note "this is the behaviour as written, not a wish: leftBehind() drops only what ranks below SWITCH"
seed FIXTURE_RESCAN_ON_STORAGE_SWITCH=true

# this case makes the arrival itself, so app_start has to leave the list where it opens
FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4

# arrive at the share, which is what starts the scan at the switch's own rank
switch_storage_to "Network share" "20c-arrive"
if ! wait_for_service RemoteScanService 60; then
    fail "arriving at the share did not start the scan the settings ask for"
    screenshot "20c-no-scan"
else
    screenshot "20c-scanning"
    switch_storage_to "This device" "20c-leave"
    sleep 3
    screenshot "20c-after-swipe"
    capture_log "20c"

    refute_log "called off" "the scan the arrival started survived leaving again"
fi

finish
