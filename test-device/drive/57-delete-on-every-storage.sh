#!/usr/bin/env bash
# #140: a medium, and a whole folder, deleted on every storage, every way the app deletes: into
# its recycle bin, past it with the confirmation's "skip the recycle bin", and with the bin turned
# off in the settings. A folder's confirmation has no checkbox, so a folder has two ways, and the
# table is 3 storages x (3 ways for a medium + 2 for a folder) = 15 cells.
#
# 55 and 56 drive the share's and the device's bin through a whole round trip -- the
# confirmation's wording, a restore, emptying it. This is the other axis: the same delete on every
# storage, pCloud included, which no script drove before, and the two ways past the bin, which
# neither of them drove.
#
# Every cell deletes something of its own, named after the cell, so the witnesses need no order:
#
# - the medium is gone from where it was, read off /sdcard, fixture/share or the pCloud stub's
#   directory, never off what the app shows
# - into the bin: it turns up in that storage's bin -- the app's own files directory on the
#   device (read with run-as), .gallery-recycle-bin in the root of the share and of pCloud
# - past the bin: it turns up in no bin
# - the medium beside the deleted ones (keep.jpg) is still there, since a delete that took too
#   much passes every check that only looks at what was asked for
#
# The cells hand their deletes over and are judged all at once, as 72 does. The settings'
# switch is flipped between the two halves with the app stopped, once the first half is judged,
# so that nothing of it is still running when the process goes.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

require_emulator

seed_file="$FIXTURE_SHARE_DIR/$FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE"
if [ ! -f "$seed_file" ]; then
    echo "the fixture share has no $FIXTURE_COPY_SOURCE_FOLDER/$FIXTURE_COPY_SOURCE_FILE; run fixture/seed-share.sh" >&2
    exit 1
fi

prefix="$FIXTURE_DELETE_PREFIX"
media_folder="${prefix}Del"
keep="keep.jpg"
device_pictures="/sdcard/Pictures"
bin="$FIXTURE_RECYCLE_BIN_FOLDER"
requests_log="$RUN_DIR/pcloud-requests.log"
media_dir="$RUN_DIR/57-media"

stub_pid=""
leave_things_as_they_were() {
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
    rm -rf "$FIXTURE_PCLOUD_DIR"
    rm -rf "$FIXTURE_SHARE_DIR/$prefix"* "$FIXTURE_SHARE_DIR/$bin"
    "${ADB[@]}" shell "rm -rf $device_pictures/$prefix*" > /dev/null 2>&1 || true
}

trap leave_things_as_they_were EXIT
leave_things_as_they_were

# --- the three storages, as a script reads them ----------------------------------------------

label_of() {
    case "$1" in
        device) echo "This device" ;;
        cloud) echo "pCloud" ;;
        share) echo "Network share" ;;
    esac
}

# the folder a whole-folder cell deletes
folder_of() { echo "${prefix}Folder$1"; }

# the medium a cell deletes, named after the cell
medium_of() { echo "$1-$2-$3.jpg"; }

is_there() {
    local storage="$1" folder="$2" file="$3"
    case "$storage" in
        device) "${ADB[@]}" shell "test -f '$device_pictures/$folder/$file'" ;;
        cloud) [ -f "$FIXTURE_PCLOUD_DIR/$folder/$file" ] ;;
        share) [ -f "$FIXTURE_SHARE_DIR/$folder/$file" ] ;;
    esac
}

# whether the storage's recycle bin holds a file of that name, anywhere in it
in_the_bin() {
    local storage="$1" file="$2"
    case "$storage" in
        device) "${ADB[@]}" shell "run-as $FIXTURE_PACKAGE sh -c 'find files -name \"$file\" 2>/dev/null'" | tr -d '\r' | rg -q . ;;
        cloud) [ -d "$FIXTURE_PCLOUD_DIR/$bin" ] && find "$FIXTURE_PCLOUD_DIR/$bin" -name "$file" | rg -q . ;;
        share) [ -d "$FIXTURE_SHARE_DIR/$bin" ] && find "$FIXTURE_SHARE_DIR/$bin" -name "$file" | rg -q . ;;
    esac
}

put_file() {
    local storage="$1" folder="$2" local_file="$3" name
    name="$(basename "$local_file")"
    case "$storage" in
        device)
            "${ADB[@]}" shell "mkdir -p '$device_pictures/$folder'"
            "${ADB[@]}" push "$local_file" "$device_pictures/$folder/$name" > /dev/null
            "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$device_pictures/$folder/$name" > /dev/null 2>&1 || true
            ;;
        cloud)
            mkdir -p "$FIXTURE_PCLOUD_DIR/$folder"
            cp "$local_file" "$FIXTURE_PCLOUD_DIR/$folder/$name"
            ;;
        share)
            mkdir -p "$FIXTURE_SHARE_DIR/$folder"
            cp "$local_file" "$FIXTURE_SHARE_DIR/$folder/$name"
            ;;
    esac
}

make_medium() {
    cp "$seed_file" "$media_dir/$1"
    printf '%s' "$1" >> "$media_dir/$1"
}

# the same search box 72 and 73 type into
search_for() {
    local text="$1" name="$2"
    local dump point
    dump="$(ui_dump "$name-search")"
    if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "top_toolbar_search")"; then
        fail "there is no search box on screen (view tree in $dump)"
        return 1
    fi
    # shellcheck disable=SC2086
    "${ADB[@]}" shell input tap $point
    sleep 1
    "${ADB[@]}" shell input text "${text%?}"
    sleep 2
    ui_wait_exact_text "$text" 30 "$name-found"
}

leave_the_selection() {
    local attempt
    for attempt in 1 2 3; do
        if ! in_selection_mode "leave-$attempt"; then
            return 0
        fi
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
        sleep 1
    done
}

show_the_filenames() {
    local file="$1" name="$2" dump point
    if ui_wait_text "$file" 4 "$name-names"; then
        return 0
    fi
    dump="$(ui_dump "$name-toolbar")"
    if point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "toggle_filename")"; then
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        sleep 2
    fi
    ui_wait_text "$file" 20 "$name-names-on"
}

# --- judging the deletes, all at once ----------------------------------------------------------

pending=()
JUDGE_SECONDS="${FIXTURE_JUDGE_SECONDS:-120}"

judge_the_deletes() {
    local deadline=$((SECONDS + JUDGE_SECONDS)) record storage folder file way name still
    local waiting=("${pending[@]}")

    step "what the ${#pending[@]} deletes left behind (waiting at most ${JUDGE_SECONDS}s)"
    while [ "${#waiting[@]}" -gt 0 ]; do
        still=()
        for record in "${waiting[@]}"; do
            IFS='|' read -r storage folder file way name <<< "$record"
            # into the bin, it is through once it is in there; past it, once it is gone -- a
            # delete into the bin moves the file, so it is never gone before it is in the bin
            if ! is_there "$storage" "$folder" "$file" && { [ "$way" != "bin" ] || in_the_bin "$storage" "$file"; }; then
                if [ "$way" = "bin" ]; then
                    pass "$name, $(label_of "$storage"): $file left $folder for the recycle bin"
                elif in_the_bin "$storage" "$file"; then
                    fail "$name, $(label_of "$storage"): $file went into the recycle bin, the delete was to skip it ($way)"
                else
                    pass "$name, $(label_of "$storage"): $file is gone, and in no recycle bin ($way)"
                fi
            else
                still+=("$record")
            fi
        done

        waiting=("${still[@]}")
        if [ "${#waiting[@]}" -eq 0 ] || [ "$SECONDS" -ge "$deadline" ]; then
            break
        fi
        sleep 5
    done

    for record in "${waiting[@]}"; do
        IFS='|' read -r storage folder file way name <<< "$record"
        if is_there "$storage" "$folder" "$file"; then
            fail "$name, $(label_of "$storage"): $file is still in $folder"
        else
            fail "$name, $(label_of "$storage"): $file left $folder, but is not in the recycle bin"
        fi
    done

    for storage in device cloud share; do
        if is_there "$storage" "$media_folder" "$keep"; then
            pass "$(label_of "$storage"): $keep, beside the deleted media, is still there"
        else
            fail "$(label_of "$storage"): $keep is gone; a delete took more than it was asked to"
        fi
    done
}

# --- one cell -------------------------------------------------------------------------------

cell_number=0

# what: medium / folder. way: bin (into the recycle bin), skip (the confirmation's checkbox),
# off (the bin turned off in the settings)
cell() {
    local storage="$1" what="$2" way="$3"
    local name file folder row dump point
    cell_number=$((cell_number + 1))
    name="57-$(printf '%02d' "$cell_number")"
    file="$(medium_of "$storage" "$what" "$way")"
    if [ "$what" = "medium" ]; then
        folder="$media_folder"
    else
        folder="$(folder_of "$way")"
    fi
    if [ -n "${FIXTURE_ONLY_CELLS:-}" ] && [[ " $FIXTURE_ONLY_CELLS " != *" $cell_number "* ]]; then
        return 0
    fi
    step "a $what on $(label_of "$storage"), deleted: $way"

    app_restart_screen
    sleep 3
    switch_storage_to "$(label_of "$storage")" "$name-storage" || return 0
    sleep 2
    if ! search_for "$folder" "$name-list"; then
        fail "$folder is not in the folder list of $(label_of "$storage")"
        screenshot "$name-no-folder"
        return 0
    fi

    row="$folder"
    if [ "$what" = "medium" ]; then
        ui_tap_exact_text "$folder" "$name-open" || return 0
        sleep 3
        if ! show_the_filenames "$file" "$name"; then
            fail "$file is not in $folder on $(label_of "$storage")"
            screenshot "$name-no-medium"
            return 0
        fi
        row="$file"
    fi

    select_row "$row" "$name-select" || { screenshot "$name-not-selected"; return 0; }
    tap_action "Delete" "$name-delete" || { leave_the_selection; return 0; }
    sleep 2

    dump="$(ui_dump "$name-confirmation")"
    if [ "$way" = "skip" ]; then
        if ! point="$(python3 "$DRIVE_DIR/ui.py" "$dump" --resource-id "skip_the_recycle_bin_checkbox")"; then
            fail "the confirmation has no 'skip the recycle bin' checkbox"
            screenshot "$name-no-checkbox"
            "${ADB[@]}" shell input keyevent KEYCODE_BACK
            leave_the_selection
            return 0
        fi
        # shellcheck disable=SC2086
        "${ADB[@]}" shell input tap $point
        sleep 1
    fi

    screenshot "$name-confirmation"
    ui_tap_text "Yes" "$name-yes" || { leave_the_selection; return 0; }
    pending+=("$storage|$folder|$file|$way|$name")
    note "handed over; what it left behind is judged with the others"
    sleep 3
}

# --- the storages, and the app ---------------------------------------------------------------

step "putting a medium per cell on each storage, and $keep beside them"
mkdir -p "$media_dir" "$FIXTURE_PCLOUD_DIR"
cp "$seed_file" "$media_dir/$keep"
for storage in device cloud share; do
    put_file "$storage" "$media_folder" "$media_dir/$keep"
    for way in bin skip off; do
        file="$(medium_of "$storage" medium "$way")"
        make_medium "$file"
        put_file "$storage" "$media_folder" "$media_dir/$file"
    done
    for way in bin off; do
        file="$(medium_of "$storage" folder "$way")"
        make_medium "$file"
        put_file "$storage" "$(folder_of "$way")" "$media_dir/$file"
    done
done

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

env FIXTURE_PCLOUD_ACCESS_TOKEN="$FIXTURE_PCLOUD_TOKEN" \
    FIXTURE_PCLOUD_API_HOST="$FIXTURE_PCLOUD_STUB_API_HOST" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4

step "walking the share, and listing pCloud, so that their folders have rows"
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "57-walk"
if ! wait_for_log "Walked the share:" 900 "57-walked"; then
    fail "the share was never walked"
    screenshot "57-no-walk"
    finish
fi

switch_storage_to "pCloud" "57-to-pcloud"
sleep 2
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "57-list-pcloud"
if ! ui_wait_exact_text "$media_folder" 120 "57-pcloud-listed"; then
    fail "pCloud's folders did not turn up after a rescan"
    screenshot "57-no-pcloud"
    finish
fi

# --- the table, with the bin on ----------------------------------------------------------------

for storage in device cloud share; do
    cell "$storage" medium bin
    cell "$storage" medium skip
    cell "$storage" folder bin
done
judge_the_deletes

# --- and with it off ---------------------------------------------------------------------------

step "turning the recycle bin off in the settings"
# with the app stopped: it keeps its preferences in memory, and would write them back over the
# file. Everything of the first half has been judged, so nothing of it is still running
app_stop
"${ADB[@]}" shell "run-as $FIXTURE_PACKAGE sh -c 'sed -i \"s|</map>|    <boolean name=\\\"use_recycle_bin\\\" value=\\\"false\\\" />\\n</map>|\" shared_prefs/Prefs.xml'"
if "${ADB[@]}" shell "run-as $FIXTURE_PACKAGE cat shared_prefs/Prefs.xml" | tr -d '\r' | rg -q 'name="use_recycle_bin" value="false"'; then
    pass "the settings say not to use the recycle bin"
else
    fail "the recycle bin could not be turned off in the settings"
    finish
fi
app_start
sleep 4

pending=()
for storage in device cloud share; do
    cell "$storage" medium off
    cell "$storage" folder off
done
judge_the_deletes

note "$cell_number cells driven"
screenshot "57-done"
finish
