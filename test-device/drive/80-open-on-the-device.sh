#!/usr/bin/env bash
# Where the folder list opens, and the order the storages stand in.
#
# Both are the maintainer's preferences rather than something else's requirement, which is exactly
# why they are worth driving: a preference has nothing underneath it that breaks loudly when it is
# undone, so an undone one is noticed only by the person who asked for it, weeks later.
#
# What is checked:
#
# - the storage menu lists All storages, pCloud, this device, the network share, in that order
#   (#128) -- a swipe walks the menu's own order, so the row above is the one a rightward drag
#   uncovers, and the pickers' chips stand in it too
# - the folder list opens on this device whatever storage it was left on. The device is the one
#   storage that never waits on the network, so it is the one that can be drawn at once
#
# The second is read off the mark in the storage menu rather than off the folders on screen: with
# nothing scanned yet both storages look alike from the list, and a check that cannot tell them
# apart passes whatever the app does.
#
# The app is signed in to pCloud for this, with the stub's token and nothing behind it: the order
# is the thing under test, and a row that is missing because nothing is configured says nothing
# about where it would stand. No scan is started by the settings, so nothing is asked of it
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

step "seeding, signed in to pCloud so that every storage has a row"
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

# this script is about where the list opens, so nothing drives it anywhere first
FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 5

# ---------------------------------------------------------------- the order

step "the storage menu lists All storages, pCloud, this device, the network share, in that order"
if ! open_storage_menu "80-order"; then
    screenshot "80-no-chip"
    finish
fi

order_dump="$(ui_dump "80-order-menu")"
order="$(python3 "$DRIVE_DIR/ui.py" "$order_dump" --list \
    | rg -o '^(All storages|This device|pCloud|Network share)' \
    | tr '\n' ',')"

if [ "$order" = "All storages,pCloud,This device,Network share," ]; then
    pass "All storages, pCloud, this device, the network share"
else
    fail "the storage menu reads '$order' rather than 'All storages,pCloud,This device,Network share,' (view tree in $order_dump)"
    screenshot "80-order"
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1

# ---------------------------------------------------------------- where it opens

step "the list opens on this device, even when it was left on the share"
switch_storage_to "Network share" "80-onto-the-share"

# the mark has to be on the share first, or the check below would pass on an app that never
# switched at all
marked="$(storage_marked_in_menu "80-on-the-share")"
if [ "$marked" = "Network share" ]; then
    pass "the list is on the share before it is closed"
else
    fail "the list did not go to the share; the menu marks '$marked'"
    screenshot "80-not-on-the-share"
fi

note "closing the app and opening it again"
app_stop
sleep 1
app_start
sleep 5

marked="$(storage_marked_in_menu "80-after-restart")"
if [ "$marked" = "This device" ]; then
    pass "it opened on this device, not on the share it was left on"
else
    fail "it opened on '$marked' rather than on this device"
    screenshot "80-after-restart"
fi

finish
