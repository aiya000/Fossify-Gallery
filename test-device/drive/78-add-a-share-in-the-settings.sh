#!/usr/bin/env bash
# #155, the settings half: a second share added, renamed and forgotten in the settings.
#
# - with a share set up, the settings row lists the shares with "Add a network share" under them,
#   and the dialog that opens from it has a name field. What is typed there is saved as a second
#   connection -- read off the app's preferences with run-as, the id and the name -- and that
#   share, and only that one, is walked right away
# - the storage menu then has a row for it by its name, beside the first share, which is called
#   by its address now that there are two
# - a new name is saved without the share being walked again: the name is only what the menus
#   call it, and a walk of a share of a thousand folders is minutes
# - forgetting it takes its settings out of the preferences and its row out of the menu
#
# The share added is the fixture share with a root of its own, a folder this script brings; see
# 77 for why a second root is a second share. Nothing of the first share is walked
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

extra_root="${FIXTURE_MEDIUM_PREFIX}Extra"
extra_on_host="$FIXTURE_SHARE_DIR/$extra_root"
extra_name="Extra"
renamed="Renamed"
# the first share is the one the seed sets up, with no name, so with two it is its address
first_label="\\\\$FIXTURE_SMB_HOST\\$FIXTURE_SHARE_NAME"
# the id the new connection is given: the first is 0, and no other has been made
extra_id=1

clean_up() {
    rm -rf "$extra_on_host"
}

trap clean_up EXIT
clean_up

step "putting a root for the new share on the share: $extra_root, with one folder of one photo"
mkdir -p "$extra_on_host/One"
cp "$FIXTURE_SHARE_DIR/Screens/image-1.jpg" "$extra_on_host/One/one.jpg"

"$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null
app_stop
logcat_reset
FIXTURE_STORAGE_FILTER=1 app_start
sleep 4

prefs() { "${ADB[@]}" shell "run-as $FIXTURE_PACKAGE sh -c 'cat shared_prefs/Prefs.xml'" | tr -d '\r'; }

# The share's row is on the Network share tab under Storage (#58), scrolled to in case the tab is
# longer than the screen. It is found by its id: the row's label reads like the tab's title
open_share_row() {
    local name="$1" i dump point
    open_settings_tab "$name" "Storage" "Network share" || return 1
    for i in 1 2 3 4 5; do
        dump="$(ui_dump "$name-scroll-$i")"
        if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "settings_smb_share")"; then
            # shellcheck disable=SC2086
            "${ADB[@]}" shell input tap $point
            sleep 1
            return 0
        fi

        "${ADB[@]}" shell input swipe 540 1800 540 700 300
        sleep 1
    done

    fail "the settings have no Network share (SMB) row"
    return 1
}

# types into the dialog's field with this id, emptying it first
type_into() {
    local id="$1" text="$2" dump point
    dump="$(ui_dump "78-field-$id")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "$id")"; then
        fail "the dialog has no field $id (view tree in $dump)"
        return 1
    fi

    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 0.5
    replace_text_field "$text" 24
}

# the keyboard over the dialog can hide what comes after the field typed into
hide_keyboard() {
    if keyboard_is_shown; then
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
        sleep 1
    fi
}

########################################################################################
step "adding a share: the settings list the one there is, with a way to add another"
########################################################################################

tap_action "Settings" "78-settings" || finish
sleep 2
open_share_row "78-add" || finish
if ui_wait_text "Add a network share" 5 "78-list"; then
    pass "the row lists the shares with 'Add a network share' under them"
else
    fail "the row did not list the shares"
    finish
fi

ui_tap_text "Add a network share" "78-add-tap"
sleep 1

# the fields are filled top to bottom, the keyboard put away before each: it covers the lower half
# of the dialog, and a tap meant for a field would land on a key
for field in "smb_name:$extra_name" "smb_host:$FIXTURE_SMB_HOST" "smb_port:$FIXTURE_SMB_PORT" "smb_share_name:$FIXTURE_SHARE_NAME" \
    "smb_root_path:$extra_root" "smb_user:$FIXTURE_SMB_USER" "smb_password:$FIXTURE_SMB_PASSWORD"; do
    hide_keyboard
    type_into "${field%%:*}" "${field#*:}" || finish
done

hide_keyboard
logcat_reset
ui_tap_exact_text "OK" "78-add-ok"
sleep 2

if wait_for_log "Walked the share: .* \\(smb:$extra_id\\)" 60 "78-walk"; then
    capture_log "78-walk"
    expect_log "Walked the share: [0-9]+ folders, 1 files, 0 folders skipped \\(smb:$extra_id\\)" "the new share was walked, and found its one photo"
    refute_log 'Walked the share: .* \(smb:\)' "and the first share was not walked with it"
else
    fail "the new share was not walked"
fi

saved="$(prefs)"
if printf '%s' "$saved" | rg -q "name=\"smb_connection_ids\">$extra_id<" && printf '%s' "$saved" | rg -q "name=\"smb_name_$extra_id\">$extra_name<"; then
    pass "it was saved as connection $extra_id, named $extra_name"
else
    fail "the preferences do not hold connection $extra_id named $extra_name"
fi

if printf '%s' "$saved" | rg -q "name=\"smb_root_path_$extra_id\">$extra_root<"; then
    pass "with $extra_root as its root"
else
    fail "its root was not saved as $extra_root"
fi

dump="$(ui_dump "78-row-after-add")"
if python3 "$DRIVE_DIR/ui.py" "$dump" --text "$extra_name" > /dev/null; then
    pass "the settings row names it"
else
    fail "the settings row does not name $extra_name (view tree in $dump)"
fi

########################################################################################
step "the storage menu has a row for it"
########################################################################################

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2
open_storage_menu "78-menu" || finish
menu="$(python3 "$DRIVE_DIR/ui.py" "$(ui_dump "78-menu-rows")" --list)"
for label in "$extra_name" "$first_label"; do
    if printf '%s\n' "$menu" | cut -f1 | rg -q -x -F -- "$label"; then
        pass "the menu offers '$label'"
    else
        fail "the menu does not offer '$label' (it has: $(printf '%s' "$menu" | cut -f1 | tr '\n' '|'))"
    fi
done

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 1
switch_storage_to "$extra_name" "78-to-extra"
if ui_wait_exact_text "One" 30 "78-extra-list"; then
    pass "its list shows One, the folder of its root"
else
    fail "its list does not show One"
fi

########################################################################################
step "a new name is saved without walking the share again"
########################################################################################

tap_action "Settings" "78-settings-rename" || finish
sleep 2
open_share_row "78-rename" || finish
ui_tap_text "$extra_name (" "78-rename-pick" || finish
sleep 1
ui_tap_exact_text "Network share (SMB)" "78-rename-edit" || finish
sleep 1
type_into "smb_name" "$renamed" || finish
hide_keyboard
logcat_reset
ui_tap_exact_text "OK" "78-rename-ok"
sleep 10

capture_log "78-rename"
refute_log "Walked the share" "the share was not walked for a new name"
# read whole before it is searched: `rg -q` stops at the first match and closes the pipe, and
# under pipefail the reader it cut off turns a found name into a failed check
saved="$(prefs)"
if printf '%s' "$saved" | rg -q "name=\"smb_name_$extra_id\">$renamed<"; then
    pass "the name is $renamed now"
else
    fail "the name was not saved"
fi

########################################################################################
step "forgetting it takes it out of the settings and the menu"
########################################################################################

open_share_row "78-forget" || finish
ui_tap_text "$renamed (" "78-forget-pick" || finish
sleep 1
ui_tap_exact_text "Forget this share" "78-forget-option" || finish
sleep 1
ui_tap_exact_text "Yes" "78-forget-yes" || finish
sleep 2

saved="$(prefs)"
if printf '%s' "$saved" | rg -q "name=\"smb_host_$extra_id\""; then
    fail "its settings are still in the preferences"
else
    pass "its settings are gone from the preferences"
fi

if printf '%s' "$saved" | rg -q 'name="smb_host">'; then
    pass "and the first share's are still there"
else
    fail "the first share's settings went with it"
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2
open_storage_menu "78-menu-after" || finish
menu="$(python3 "$DRIVE_DIR/ui.py" "$(ui_dump "78-menu-after-rows")" --list | cut -f1)"
if printf '%s\n' "$menu" | rg -q -x -F -- "$renamed"; then
    fail "the menu still offers $renamed"
else
    pass "the menu no longer offers it"
fi

if printf '%s\n' "$menu" | rg -q -x -F -- "Network share"; then
    pass "and the first share is 'Network share' again, being the only one"
else
    fail "the first share is not called 'Network share' (the menu has: $(printf '%s' "$menu" | tr '\n' '|'))"
fi

"${ADB[@]}" shell input keyevent KEYCODE_BACK
finish
