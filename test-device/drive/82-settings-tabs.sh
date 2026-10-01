#!/usr/bin/env bash
# The tabs of the settings (#58): General and Storage on top, and under Storage one tab per
# storage -- This device, pCloud, Network share, Other.
#
# What is checked, tab by tab: the rows that belong there are on screen, and a row of every other
# tab is not. Looking only for the rows that should be there would pass a screen that shows every
# page at once, which is the very length #58 is about. The second row of tabs is checked the same
# way: it is there under Storage and gone under General.
#
# Each page is short enough to fit the emulator's screen, so a row that is not in the dump is not
# on the page; a page that grows past the screen would need its later rows scrolled to
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
app_stop
app_start
sleep 4

# one row of each page, by its whole label (the share's row is by id, see 78)
general_row="Customize appearance"
local_row="Manage excluded folders"
pcloud_row="pCloud account"
other_row="Storage order"

on_screen() {
    local dump="$1" row="$2"
    if [ "$row" = "share" ]; then
        python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "settings_smb_share" > /dev/null
    else
        python3 "$DRIVE_DIR/ui.py" "$dump" --text "$row" --exact > /dev/null
    fi
}

# the page now on screen is the one with this row, and none of the others
expect_page() {
    local name="$1" wanted="$2" dump row
    dump="$(ui_dump "$name")"
    for row in "$general_row" "$local_row" "$pcloud_row" "share" "$other_row"; do
        if [ "$row" = "$wanted" ]; then
            if on_screen "$dump" "$row"; then
                pass "'$row' is on the page"
            else
                fail "'$row' is not on the page (view tree in $dump)"
                screenshot "$name"
            fi
        elif on_screen "$dump" "$row"; then
            fail "'$row' is on the page too, though it belongs to another tab (view tree in $dump)"
            screenshot "$name"
        fi
    done

    # the second row of tabs, by one of its titles
    local storage_tabs=0
    if python3 "$DRIVE_DIR/ui.py" "$dump" --text "This device" --exact --ignore-case > /dev/null; then
        storage_tabs=1
    fi

    if [ "$wanted" = "$general_row" ] && [ "$storage_tabs" = 1 ]; then
        fail "the storage tabs are showing under General"
    elif [ "$wanted" != "$general_row" ] && [ "$storage_tabs" = 0 ]; then
        fail "the storage tabs are not showing under Storage"
    fi
}

step "the settings open on General"
tap_action "Settings" "82-settings" || finish
sleep 2
expect_page "82-general" "$general_row"

step "Storage opens on This device"
open_settings_tab "82-storage" "Storage" || finish
expect_page "82-local" "$local_row"

step "pCloud"
open_settings_tab "82-pcloud" "pCloud" || finish
expect_page "82-pcloud" "$pcloud_row"

step "Network share"
open_settings_tab "82-share" "Network share" || finish
expect_page "82-share" "share"

step "Other"
open_settings_tab "82-other" "Other" || finish
expect_page "82-other" "$other_row"

step "back to General, and Storage again keeps the storage tab it was left on"
open_settings_tab "82-back" "General" || finish
expect_page "82-general-again" "$general_row"
open_settings_tab "82-storage-again" "Storage" || finish
expect_page "82-other-again" "$other_row"

"${ADB[@]}" shell input keyevent KEYCODE_BACK
finish
