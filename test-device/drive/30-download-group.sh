#!/usr/bin/env bash
# #64: take a whole group of the share, fetch its videos, and play them in turn.
#
# The part worth driving is the order. The rule is that every level is taken in the order it is
# drawn in, walked depth first -- so a subgroup is fetched where it sits in the list, not after
# all the plain folders. The fixture is built for exactly that shape: the group "Trips" holds the
# folder Osaka and the subgroup "Kyoto", and with the list sorted by name Kyoto is drawn first.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

step "seeding, and scanning the share so the videos have rows to be fetched from"
env FIXTURE_STORAGE_FILTER=4 "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
logcat_reset
app_start
sleep 4

open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "30-menu"
if ! wait_for_log "Walked the share:" 900 "30-scan"; then
    fail "the share was never scanned, so there is nothing to download"
    screenshot "30-no-scan"
    finish
fi

step "picking the group"
if ! ui_wait_text "$FIXTURE_GROUP_PARENT_NAME" 30 "30-list"; then
    fail "the group $FIXTURE_GROUP_PARENT_NAME is not in the folder list"
    screenshot "30-no-group"
    finish
fi

if ! select_row "$FIXTURE_GROUP_PARENT_NAME" "30-select"; then
    screenshot "30-not-selected"
    finish
fi
screenshot "30-selected"

step "asking for the videos in it"
open_overflow_menu
sleep 1
ui_tap_text "Download the videos in the selection" "30-menu-download"
sleep 2
dialog="$(ui_dump "30-confirmation")"
screenshot "30-confirmation"

# the group holds four videos: two in Osaka, two in the subgroup Kyoto
wanted=4
if python3 "$DRIVE_DIR/ui.py" "$dialog" --text "$wanted of the selected videos" > /dev/null; then
    pass "the confirmation names the $wanted videos it is about to fetch"
else
    fail "the confirmation does not name $wanted videos (view tree in $dialog)"
fi

# a group can run to tens of gigabytes, so the size is named as well as the count
# "221.2 kB", as the app formats it -- the unit's case is the platform's, not ours
if python3 "$DRIVE_DIR/ui.py" "$dialog" --list | rg -qi '[0-9]+([.,][0-9]+)? ?[kmg]?b\b'; then
    pass "and it names how much that is"
else
    fail "the confirmation does not say how much is about to come over the network"
fi

step "letting it run"
ui_tap_text "Yes" "30-confirm-yes"

if ! wait_for_log "Downloaded .*video-.*" 300 "30-download"; then
    fail "nothing was downloaded"
    screenshot "30-no-download"
    finish
fi

# all four, and in the order the list draws them
sleep 5
log="$(logcat_dump 30-download)"
downloaded="$(rg -o -N 'Downloaded [^ ]+' "$log" | awk '{print $2}')"
count="$(echo "$downloaded" | rg -c 'video-' || true)"
note "downloaded in this order:"
echo "$downloaded" | sed 's/^/     /'

if [ "$count" = "$wanted" ]; then
    pass "all $wanted videos of the group were fetched"
else
    fail "expected $wanted videos, the log shows $count"
fi

first="$(echo "$downloaded" | head -n 1)"
case "$first" in
    *Kyoto*)
        pass "the subgroup was walked where it sits, before the plain folder"
        ;;
    *)
        fail "the first video came from $first; with the list sorted by name the subgroup Kyoto is drawn first, so it should have been fetched first"
        ;;
esac

screenshot "30-downloaded"

# The known gap in #64, written down rather than asserted on: asking for the same group a second
# time finds every video already on the device, so there is nothing to fetch -- and today that
# ends in a message rather than in playback. If this stops being true, the note is what to change
step "asking a second time, where #64 still has a hole"
# the selection ends when the download starts, so the group has to be picked again
select_row "$FIXTURE_GROUP_PARENT_NAME" "30-select-again" || true
open_overflow_menu
sleep 1
ui_tap_text "Download the videos in the selection" "30-menu-download-again" || true
sleep 2
screenshot "30-second-press"
note "what the second press does is in 30-second-press.png; today it is a message, not playback"

finish
