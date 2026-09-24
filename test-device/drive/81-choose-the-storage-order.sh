#!/usr/bin/env bash
# The order the storages stand in, chosen in the settings (#128, second step).
#
# What is checked:
#
# - the settings offer "Storage order", and its dialog lists all four storages in the default
#   order: All storages, pCloud, this device, the network share
# - "All storages" can be dragged by its handle from the top to the bottom -- it moves like the
#   rest, which is what the maintainer asked for
# - once OK is pressed, the settings row reads the new order, and so does the storage menu of
#   the folder list. The menu is the witness that matters: a dialog that only reorders itself
#   would pass the first two checks and change nothing the user sees
#
# The app is signed in to pCloud with the stub's token, as in 80, so every storage has a row in
# the menu to stand in; nothing is scanned
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

step "seeding, signed in to pCloud so that every storage has a row"
env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 5

# the labels of the dialog's rows, top to bottom, comma-joined
dialog_order() {
    local dump
    dump="$(ui_dump "$1")"
    python3 "$DRIVE_DIR/ui.py" "$dump" --list \
        | rg 'storage_order_label' \
        | cut -f1 \
        | tr '\n' ','
}

# ---------------------------------------------------------------- the dialog

step "the settings offer the storage order, in the default order"
tap_action "Settings" "81-settings" || finish
sleep 2
ui_tap_exact_text "Storage order" "81-settings-row" || { screenshot "81-no-row"; finish; }
sleep 1

order="$(dialog_order "81-dialog")"
if [ "$order" = "All storages,pCloud,This device,Network share," ]; then
    pass "the dialog lists All storages, pCloud, this device, the network share"
else
    fail "the dialog reads '$order'"
    screenshot "81-dialog"
    finish
fi

step "dragging All storages from the top to the bottom"
dump="$(ui_dump "81-before-drag")"
# the first handle is the top row's, All storages
handle="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "Reorder storages by dragging")"
target="$(python3 "$DRIVE_DIR/ui.py" "$dump" --text "Network share" --exact)"
read -r hx hy <<< "$handle"
read -r _ ty <<< "$target"

# held and moved in steps: ItemTouchHelper swaps a row only once the dragged one has passed the
# middle of the next, so a jump straight to the end would move nothing
"${ADB[@]}" shell input motionevent DOWN "$hx" "$hy"
sleep 0.5
for ((y = hy; y < ty + 60; y += 20)); do
    "${ADB[@]}" shell input motionevent MOVE "$hx" "$y"
done
sleep 0.5
"${ADB[@]}" shell input motionevent UP "$hx" "$((ty + 60))"
sleep 1

order="$(dialog_order "81-after-drag")"
if [ "$order" = "pCloud,This device,Network share,All storages," ]; then
    pass "All storages went to the bottom"
else
    fail "after the drag the dialog reads '$order'"
    screenshot "81-after-drag"
fi

ui_tap_exact_text "OK" "81-ok"
sleep 1

step "the settings row reads the new order"
dump="$(ui_dump "81-settings-after")"
if python3 "$DRIVE_DIR/ui.py" "$dump" --text "pCloud, This device, Network share, All storages" --exact > /dev/null; then
    pass "pCloud, This device, Network share, All storages"
else
    fail "the settings row does not read the new order (view tree in $dump)"
    screenshot "81-settings-after"
fi

# ---------------------------------------------------------------- the menu

step "the storage menu of the folder list stands in the new order"
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

if ! open_storage_menu "81-menu"; then
    screenshot "81-no-chip"
    finish
fi

dump="$(ui_dump "81-menu-open")"
order="$(python3 "$DRIVE_DIR/ui.py" "$dump" --list \
    | rg -o '^(All storages|This device|pCloud|Network share)' \
    | tr '\n' ',')"

if [ "$order" = "pCloud,This device,Network share,All storages," ]; then
    pass "the menu reads pCloud, this device, the network share, All storages"
else
    fail "the storage menu reads '$order' (view tree in $dump)"
    screenshot "81-menu"
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
finish
