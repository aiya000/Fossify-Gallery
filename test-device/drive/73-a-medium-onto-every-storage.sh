#!/usr/bin/env bash
# #140: a medium copied or moved into a folder with the destination picker, for every cell of
# MediaTransferTableTest's table -- the storage it is on, the storage of the folder tapped, and
# copy or move. 18 cells.
#
# Each cell carries a medium of its own, named after the cell and with bytes of its own, from the
# folder AbFrom of one storage into the folder AbTo of another (or of the same one). So which
# arrival belongs to which cell is told by its name, and a wrong file cannot pass for the right
# one. The witnesses are the storages themselves -- fixture/share, the pCloud stub's directory,
# /sdcard/Pictures -- not what the app says:
#
# - carried: the medium turns up in AbTo, byte for byte; a copy leaves it in AbFrom, a move takes
#   it away
# - turned away (pCloud onto the share, a copy within the share): the picker stays up rather
#   than taking the folder, and the medium is still in AbFrom and nowhere else
#
# The cells only hand their transfers over, and what arrived is judged once they have all been
# driven, against one deadline, the same way 72 does (see test-device/AGENTS.md). The share is
# walked once and pCloud listed once, so that their folders have rows: about five minutes of walk
# and about a minute per cell (20 minutes for the 18, measured). FIXTURE_ONLY_CELLS="3 4" drives
# only those cells.
#
# What the scripts before it pin besides the pair is theirs to keep: the share's modification
# time and the numbering of a taken name (50, 70), the requests pCloud is sent (60), and the rows
# following a move within the share without a walk (95)
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

prefix="$FIXTURE_MEDIUM_PREFIX"
from_folder="${prefix}From"
to_folder="${prefix}To"
device_pictures="/sdcard/Pictures"
requests_log="$RUN_DIR/pcloud-requests.log"
media_dir="$RUN_DIR/73-media"

stub_pid=""
leave_things_as_they_were() {
    if [ -n "$stub_pid" ]; then
        kill "$stub_pid" 2> /dev/null || true
    fi
    rm -rf "$FIXTURE_PCLOUD_DIR"
    rm -rf "$FIXTURE_SHARE_DIR/$prefix"*
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

# the md5 of [file] in [folder] of [storage], empty when it is not there
md5_on() {
    local storage="$1" folder="$2" file="$3"
    case "$storage" in
        device) "${ADB[@]}" shell "md5sum '$device_pictures/$folder/$file' 2> /dev/null" | tr -d '\r' | awk '{print $1}' ;;
        cloud) md5sum "$FIXTURE_PCLOUD_DIR/$folder/$file" 2> /dev/null | awk '{print $1}' ;;
        share) md5sum "$FIXTURE_SHARE_DIR/$folder/$file" 2> /dev/null | awk '{print $1}' ;;
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

# the medium a cell carries: named after the cell, with a few bytes past the end of the JPEG that
# every decoder ignores and no checksum does
medium_of() { echo "$1-to-$2-$3.jpg"; }

make_medium() {
    local name="$1"
    cp "$seed_file" "$media_dir/$name"
    printf '%s' "$name" >> "$media_dir/$name"
    md5sum "$media_dir/$name" | awk '{print $1}'
}

# Types into the search box on screen and waits for [text] to be listed, the same as 72: the
# lists are long, and the folder looked for may be off the screen
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
    # all but its last letter, so that the box itself is not the first thing that says [text]
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

# the grid names its media only with the filename toggle on, and it is a toggle: pressed only
# when the name is not already there
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

# --- judging the transfers, all at once --------------------------------------------------------

pending=()
JUDGE_SECONDS="${FIXTURE_JUDGE_SECONDS:-180}"
declare -A md5_of=()

judge_the_transfers() {
    local deadline=$((SECONDS + JUDGE_SECONDS)) record from to op name file want still
    local waiting=("${pending[@]}")

    step "what the ${#pending[@]} transfers left behind (waiting at most ${JUDGE_SECONDS}s)"
    while [ "${#waiting[@]}" -gt 0 ]; do
        still=()
        for record in "${waiting[@]}"; do
            IFS='|' read -r from to op name file <<< "$record"
            want="${md5_of[$file]}"
            # a move is through once its source is gone as well
            if [ "$(md5_on "$to" "$to_folder" "$file")" = "$want" ] \
                && { [ "$op" = "copy" ] || [ -z "$(md5_on "$from" "$from_folder" "$file")" ]; }; then
                pass "$name, $from to $to, $op: $to_folder on $(label_of "$to") has $file, byte for byte"
                if [ "$op" = "copy" ]; then
                    if [ "$(md5_on "$from" "$from_folder" "$file")" = "$want" ]; then
                        pass "   and $from_folder still has it"
                    else
                        fail "   a copy took $file out of $from_folder"
                    fi
                else
                    pass "   and $from_folder gave it up"
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
        IFS='|' read -r from to op name file <<< "$record"
        if [ "$(md5_on "$to" "$to_folder" "$file")" = "${md5_of[$file]}" ]; then
            fail "$name, $from to $to, $op: $file arrived, but $from_folder still has it after a move"
        else
            fail "$name, $from to $to, $op: $file never turned up in $to_folder on $(label_of "$to")"
        fi
    done
}

# --- one cell -------------------------------------------------------------------------------

cell_number=0

# from, to: device / cloud / share. op: copy / move. expected: carried / refused
cell() {
    local from="$1" to="$2" op="$3" expected="$4"
    local name file action
    cell_number=$((cell_number + 1))
    name="73-$(printf '%02d' "$cell_number")"
    file="$(medium_of "$from" "$to" "$op")"
    if [ -n "${FIXTURE_ONLY_CELLS:-}" ] && [[ " $FIXTURE_ONLY_CELLS " != *" $cell_number "* ]]; then
        return 0
    fi
    step "$from to $to, $op: $expected"

    # a fresh folder list with the app left running, so that the transfers handed over before
    # carry on, see app_restart_screen
    app_restart_screen
    sleep 3
    switch_storage_to "$(label_of "$from")" "$name-storage" || return 0
    sleep 2
    if ! search_for "$from_folder" "$name-list"; then
        fail "$from_folder is not in the folder list of $(label_of "$from")"
        screenshot "$name-no-source"
        return 0
    fi

    ui_tap_exact_text "$from_folder" "$name-open" || return 0
    sleep 3
    if ! show_the_filenames "$file" "$name"; then
        fail "$file is not in $from_folder on $(label_of "$from")"
        screenshot "$name-no-medium"
        return 0
    fi

    select_row "$file" "$name-select" || { screenshot "$name-not-selected"; return 0; }
    action="Copy to"
    [ "$op" = "move" ] && action="Move to"
    tap_action "$action" "$name-action" || { leave_the_selection; return 0; }
    sleep 2

    if ! ui_wait_exact_text "$(label_of "$to")" 30 "$name-picker"; then
        fail "the picker has no chip for $(label_of "$to")"
        screenshot "$name-no-chip"
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
        leave_the_selection
        return 0
    fi
    ui_tap_exact_text "$(label_of "$to")" "$name-chip" || true
    sleep 2

    if ! search_for "$to_folder" "$name-picker-folder"; then
        fail "$to_folder is not in the picker on $(label_of "$to")'s chip"
        screenshot "$name-no-destination"
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
        leave_the_selection
        return 0
    fi
    screenshot "$name-picker"
    ui_tap_exact_text "$to_folder" "$name-pick" || true
    sleep 3

    case "$expected" in
        carried)
            pending+=("$from|$to|$op|$name|$file")
            note "handed over; what arrives is judged once every cell has been driven"
            ;;

        refused)
            # the refusal is a toast, gone before a dump can be sure to see it
            # (agents/tests/a-toast-is-gone-before-the-dump.md); the picker still being up is
            # what says the folder was not taken
            if ui_wait_exact_text "$to_folder" 5 "$name-still-up"; then
                pass "the picker stays up rather than taking $to_folder"
            else
                fail "the picker went away, as if $to_folder was taken"
                screenshot "$name-picker-gone"
            fi

            sleep 3
            if [ -n "$(md5_on "$to" "$to_folder" "$file")" ]; then
                fail "$file turned up in $to_folder on $(label_of "$to") all the same"
            elif [ "$(md5_on "$from" "$from_folder" "$file")" != "${md5_of[$file]}" ]; then
                fail "$file is gone from $from_folder, though nothing was carried"
            else
                pass "and $file is still in $from_folder, and nowhere else"
            fi

            "${ADB[@]}" shell input keyevent KEYCODE_BACK
            sleep 1
            ;;
    esac

    leave_the_selection
}

# --- the storages, and the app ---------------------------------------------------------------

# every cell, in the order they are driven: the refused ones among them are judged on the spot
cells=(
    "device device copy carried"
    "device device move carried"
    "device cloud copy carried"
    "device cloud move carried"
    "device share copy carried"
    "device share move carried"
    "cloud device copy carried"
    "cloud device move carried"
    "cloud cloud copy carried"
    "cloud cloud move carried"
    "cloud share copy refused"
    "cloud share move refused"
    "share device copy carried"
    "share device move carried"
    "share cloud copy carried"
    "share cloud move carried"
    "share share copy refused"
    "share share move carried"
)

step "putting a medium per cell into $from_folder, and $to_folder beside it, on each storage"
mkdir -p "$media_dir" "$FIXTURE_PCLOUD_DIR"
for spec in "${cells[@]}"; do
    read -r from to op _ <<< "$spec"
    file="$(medium_of "$from" "$to" "$op")"
    md5_of[$file]="$(make_medium "$file")"
    put_file "$from" "$from_folder" "$media_dir/$file"
done
# a folder with nothing in it has no row, and the picker lists rows
anchor="$media_dir/anchor.jpg"
cp "$seed_file" "$anchor"
for storage in device cloud share; do
    put_file "$storage" "$to_folder" "$anchor"
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
ui_tap_text "Rescan the network share" "73-walk"
if ! wait_for_log "Walked the share:" 900 "73-walked"; then
    fail "the share was never walked"
    screenshot "73-no-walk"
    finish
fi

switch_storage_to "pCloud" "73-to-pcloud"
sleep 2
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "73-list-pcloud"
if ! ui_wait_exact_text "$from_folder" 120 "73-pcloud-listed"; then
    fail "pCloud's folders did not turn up after a rescan"
    screenshot "73-no-pcloud"
    finish
fi

# --- the table --------------------------------------------------------------------------------

driving_started=$SECONDS
for spec in "${cells[@]}"; do
    # shellcheck disable=SC2086
    cell $spec
done

note "the cells were driven in $((SECONDS - driving_started))s"
judging_started=$SECONDS
judge_the_transfers
note "and judged in $((SECONDS - judging_started))s"

note "$cell_number cells driven"
screenshot "73-done"
finish
