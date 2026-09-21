#!/usr/bin/env bash
# A scan of the whole fixture share, and what it reports when it is done.
#
# By hand this is minutes of watching a notification against a share whose contents nobody can
# count. Here the share holds exactly what manifest.env says, so "it worked" becomes a number.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

step "starting the app"
app_stop
logcat_reset
app_start
sleep 4
screenshot "10-launched"

step "asking for a rescan of the share"
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "10-menu"

step "waiting for the walk to finish"
if ! wait_for_log "Walked the share:" 900 "10-scan"; then
    fail "the walk never finished; see $(logcat_dump 10-scan-timeout)"
    screenshot "10-scan-timeout"
    finish
fi

screenshot "10-scanned"
log="$(logcat_dump 10-scan)"
walked="$(rg -o -N 'Walked the share: .*' "$log" | tail -n 1)"
note "$walked"

# the counts the manifest promises. The folder count is reported rather than asserted on: whether
# a folder holding nothing but other folders is counted is the scanner's business, and pinning it
# here would turn a deliberate change into a failure that looks like a bug
expect_log "Walked the share: [0-9]+ folders, $FIXTURE_MEDIA files" "the scan found the $FIXTURE_MEDIA files the fixture holds"
expect_log "Walked the share: .*, 0 folders skipped" "no folder was left unread"
refute_log "was called off" "nothing called the scan off"

step "what the folder list draws afterwards"
# Only the folders that are in no group are drawn as themselves. Kyoto and Osaka are members of
# the fixture's groups, so the list draws the group in their place -- which is the whole point of
# a group, and was worth being surprised by once
for name in Camera Screens "$FIXTURE_GROUP_PARENT_NAME"; do
    if ui_wait_exact_text "$name" 30 "10-row-$name"; then
        pass "$name is in the folder list"
    else
        fail "$name is not in the folder list"
    fi
done

# Trips the folder holds no media of its own, only the two folders under it. What carries its name
# in the list is the group, not the folder
screenshot "10-folders"

finish
