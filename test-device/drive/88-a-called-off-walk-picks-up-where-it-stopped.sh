#!/usr/bin/env bash
# #80: a walk of the share that was called off keeps its place, and the next walk picks it up
# rather than walking the share again from the top.
#
# The call-off is the one rule 1-a of 20-storage-switch.sh makes: the scan the settings start on
# launch, left behind by a swipe. Arriving at the share again starts the scan the settings attach to
# the arrival, which is the same work, and that is the one that has to pick the place up. The
# witnesses are the scanner's own lines -- where it put the walk down and where it picked it up,
# which have to be the same folder count -- and the count the finished walk reports, which has to
# be every file the fixture holds: a resumed walk that lost or doubled a folder shows up there.
#
# The second case is the notification's stop action, which must not keep the place: "stop" means
# leave the network alone, not "pause". The next walk then starts from the top.
#
# The folders a transfer writes to while a walk is put down are read again when it is picked up;
# that is the JVM test SmbWalkTest's, which can stop the walk at every folder in turn.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed() {
    env "$@" "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
}

# the folder count of the last line matching [pattern] in the captured log, or nothing
listed_count() {
    captured_log | rg -o -N -r '$1' -- "$1 at ([0-9]+) folders listed" | tail -n 1
}

# FIXTURE_ONLY_CASES="b" drives only that case, while it is being worked on
wanted() {
    [ -z "${FIXTURE_ONLY_CASES:-}" ] || [[ " $FIXTURE_ONLY_CASES " == *" $1 "* ]]
}

# ---------------------------------------------------------------- a: picked up

case_picked_up() {
    step "a: a walk left behind keeps its place, and the next walk picks it up"
    seed FIXTURE_RESCAN_ON_LAUNCH=true FIXTURE_RESCAN_ON_STORAGE_SWITCH=true
    logcat_reset
    app_start

    if ! wait_for_service RemoteScanService 60; then
        fail "the scan the settings ask for on launch never started"
        screenshot "88a-no-scan"
    else
        # long enough to be well into the share, short of the half minute the whole of it takes
        sleep 15
        screenshot "88a-scanning"
        switch_storage_to "This device" "88a-leave"

        if ! wait_for_log "Put the walk of the share down" 30 "88a-put-down"; then
            capture_log "88a-put-down"
            fail "the walk that was called off did not hold its place"
        else
            capture_log "88a-put-down"
            put_down="$(listed_count "Put the walk of the share down")"
            note "put down at $put_down folders listed"
            if [ "${put_down:-0}" -gt 0 ]; then
                pass "the walk had got somewhere before it was called off"
            else
                fail "the walk was called off before it had listed anything; nothing tells a resume from a start"
            fi

            switch_storage_to "Network share" "88a-arrive"
            if ! wait_for_log "Walked the share:" 600 "88a-walk"; then
                fail "the walk the arrival started never finished"
            else
                capture_log "88a-walked"
                picked_up="$(listed_count "Picking up the walk of the share")"
                note "picked up at ${picked_up:-nothing} folders listed"
                if [ -n "$picked_up" ] && [ "$picked_up" = "$put_down" ]; then
                    pass "the next walk picked up where the first was put down"
                else
                    fail "the next walk did not pick up at $put_down folders (it said: ${picked_up:-nothing})"
                fi

                expect_log "Walked the share: [0-9]+ folders, $FIXTURE_MEDIA files" "and it came to every file the fixture holds, none lost or doubled"
            fi
        fi
    fi
}

# ---------------------------------------------------------------- b: stopped

case_stopped() {
    step "b: a walk stopped from the notification does not keep its place"
    seed
    logcat_reset
    app_start
    sleep 4

    open_overflow_menu
    sleep 1
    ui_tap_text "Rescan the network share" "88b-menu"

    if ! wait_for_service RemoteScanService 60; then
        fail "the rescan asked for from the menu never started"
        screenshot "88b-no-scan"
    else
        # What the notification's Stop sends, sent straight to the service. The button itself cannot
        # be pressed from here: the notification is redrawn twice a second while the walk runs, and
        # uiautomator will not dump a screen that never goes still ("could not get idle state"). The
        # service is not exported, so it takes root to reach it, which the emulator's su gives
        sleep 2
        "${ADB[@]}" shell su 0 am startservice \
            -n "$FIXTURE_PACKAGE/org.fossify.gallery.jobs.RemoteScanService" \
            -a org.fossify.gallery.ABORT_REMOTE_SCAN > /dev/null

        if ! wait_for_log "Stopped the walk of the share" 30 "88b-stopped"; then
            capture_log "88b-stopped"
            fail "the walk did not stop on the notification's action"
        else
            pass "the walk stopped without keeping its place"

            logcat_reset
            open_overflow_menu
            sleep 1
            ui_tap_text "Rescan the network share" "88b-menu-again"
            if ! wait_for_log "Walked the share:" 600 "88b-walk"; then
                fail "the rescan after the stop never finished"
            else
                capture_log "88b-walked"
                refute_log "Picking up the walk of the share" "the next walk started from the top"
                expect_log "Walked the share: [0-9]+ folders, $FIXTURE_MEDIA files" "and came to every file the fixture holds"
            fi
        fi
    fi
}

if wanted a; then case_picked_up; fi
if wanted b; then case_stopped; fi

finish
