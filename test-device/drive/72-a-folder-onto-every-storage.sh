#!/usr/bin/env bash
# #139, #140: a folder of the folder list, copied or moved with the destination picker's OK, for
# every cell of FolderPlacementTableTest's table -- the storage the folder is on, the storage the
# picker's chips show, copy or move, and the OK at the top or inside the group Trips.
#
# Each cell is driven through the screens and judged by its witness, not by what the app says:
#
# - a new folder: a folder named after the source (numbered when that name is taken) turns up on
#   the destination with the photo in it, read off fixture/share, off the pCloud stub's directory,
#   or off /sdcard/Pictures; inside Trips it is a member of Trips, at the top of none, read off
#   the app's preferences; a copy leaves the source's photo where it was, a move takes it away
# - a regroup (a move that stays on its storage): nothing turns up anywhere, and the source
#   itself becomes a member of Trips, or of no group
# - a refusal: the picker says why, and nothing turns up anywhere
#
# Every move gets a source folder of its own, since it uses its source up; the copies and the
# regroups share one per storage. Trips is given a folder of this device and one of pCloud, and
# holds the share's Osaka from the start, so that it is in the picker on every chip. The share is
# walked once and pCloud listed once, so that their folders have rows.
#
# It replaced two scripts, one for the share and one for pCloud and this device, which drove five
# of these cells between them. It walks the share, so it takes a while: about five minutes for
# the walk and half a minute or so per cell.
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

seed_md5="$(md5sum "$seed_file" | awk '{print $1}')"
photo="$FIXTURE_PLACED_FILE"
prefix="$FIXTURE_PLACED_PREFIX"
device_pictures="/sdcard/Pictures"
device_app_root="/storage/emulated/0/Pictures"
requests_log="$RUN_DIR/pcloud-requests.log"

# the folders each storage starts with. The ones named after a move are used up by that move
device_sources=(DevCopy DevMvCloudTop DevMvCloudGroup DevMvShareTop DevMvShareGroup DevInTrips)
cloud_sources=(CloudCopy CloudMvDevTop CloudMvDevGroup CloudInTrips)
share_sources=(ShareCopy ShareMvDevTop ShareMvDevGroup ShareMvCloudTop ShareMvCloudGroup)

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

# the path the app knows a folder of the storage's root by, which is what a group member is
app_path_of() {
    case "$1" in
        device) echo "$device_app_root/$2" ;;
        cloud) echo "pcloud:/$2" ;;
        share) echo "smb:/$2" ;;
    esac
}

# the folders in the storage's root, one per line
folders_on() {
    case "$1" in
        device) "${ADB[@]}" shell "ls -1 $device_pictures" | tr -d '\r' ;;
        cloud) ls -1 "$FIXTURE_PCLOUD_DIR" ;;
        share) ls -1 "$FIXTURE_SHARE_DIR" ;;
    esac
}

# whether the folder of the storage holds the photo, byte for byte
holds_the_photo() {
    local storage="$1" folder="$2" md5
    case "$storage" in
        device) md5="$("${ADB[@]}" shell "md5sum '$device_pictures/$folder/$photo' 2> /dev/null" | tr -d '\r' | awk '{print $1}')" ;;
        cloud) md5="$(md5sum "$FIXTURE_PCLOUD_DIR/$folder/$photo" 2> /dev/null | awk '{print $1}')" ;;
        share) md5="$(md5sum "$FIXTURE_SHARE_DIR/$folder/$photo" 2> /dev/null | awk '{print $1}')" ;;
    esac
    [ "$md5" = "$seed_md5" ]
}

put_folder() {
    local storage="$1" folder="$2"
    case "$storage" in
        device)
            "${ADB[@]}" shell "mkdir -p '$device_pictures/$folder'"
            "${ADB[@]}" push "$seed_file" "$device_pictures/$folder/$photo" > /dev/null
            "${ADB[@]}" shell content call --uri content://media/external --method scan_file --arg "$device_pictures/$folder/$photo" > /dev/null 2>&1 || true
            ;;
        cloud)
            mkdir -p "$FIXTURE_PCLOUD_DIR/$folder"
            cp "$seed_file" "$FIXTURE_PCLOUD_DIR/$folder/$photo"
            ;;
        share)
            mkdir -p "$FIXTURE_SHARE_DIR/$folder"
            cp "$seed_file" "$FIXTURE_SHARE_DIR/$folder/$photo"
            ;;
    esac
}

# the group a path is a member of, read off the app's preferences; empty for none
group_of() {
    local key="&quot;$1&quot;:" members rest
    members="$("${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" cat shared_prefs/Prefs.xml | tr -d '\r' | rg -o 'name="folder_group_members">[^<]*' || true)"
    rest="${members#*"$key"}"
    if [ "$rest" = "$members" ]; then
        return 0
    fi
    echo "${rest%%[,\}]*}"
}

# a folder on [storage] named after [source] that was not in [before], and holds the photo
arrived_folder() {
    local storage="$1" source="$2" before="$3" name
    # onto another storage the folder keeps the source's own name, so only what was there before
    # is left out -- on the source's own storage that is the source itself
    while IFS= read -r name; do
        if [[ "$name" == "$source"* ]] && ! printf '%s\n' "$before" | rg -qxF -- "$name" && holds_the_photo "$storage" "$name"; then
            echo "$name"
            return 0
        fi
    done < <(folders_on "$storage")
    return 1
}

# Types into the search box on screen and waits for [text] to be listed. The lists grow with
# every cell, and a name further down the alphabet than the Aa folders -- Trips, or a source once
# the arrivals sort before it -- falls off the screen, where a dump cannot see it. The box is found
# by its id: its hint is not fixed, the picker of "Move to" says how to move to the top instead
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
    # all but its last letter: the box would otherwise hold the very text looked for, and be the
    # first thing on screen a tap or a long press on that text lands on
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

# --- judging the transfers, all at once --------------------------------------------------------
#
# A transfer is a job of the app's services, and waiting for each one before driving the next
# cell costs a whole timeout per cell that goes wrong. So the cells only hand their transfers
# over, and they are judged together once the driving is done, against one deadline -- the
# transfers run behind the screen while the later cells are driven, and a cell that goes wrong
# costs nothing more than the deadline everyone shares.
#
# Which arrival belongs to which cell is not told by its name: two copies of one folder onto one
# storage are numbered in the order they finish. It is told by the group instead -- the cell at
# the top wants an arrival in no group, the one inside Trips an arrival in Trips -- and an arrival
# is given to one cell only.

pending=()
declare -A claimed=()

# how long the last transfer may take after the last cell was driven. A folder of one photo
# arrives within seconds; this is for the queue the services work through one job at a time
JUDGE_SECONDS="${FIXTURE_JUDGE_SECONDS:-180}"

# the folders each storage had before any cell was driven, one file per storage
snapshot_the_storages() {
    local storage
    for storage in device cloud share; do
        folders_on "$storage" > "$RUN_DIR/72-before-$storage.txt"
    done
}

# an arrival on [to] for [source] in the group [at] asks for, which no other cell has claimed
find_arrival() {
    local to="$1" source="$2" at="$3" name group
    while IFS= read -r name; do
        [[ "$name" == "$source"* ]] || continue
        rg -qxF -- "$name" "$RUN_DIR/72-before-$to.txt" && continue
        [ -n "${claimed[$to/$name]:-}" ] && continue
        holds_the_photo "$to" "$name" || continue
        group="$(group_of "$(app_path_of "$to" "$name")")"
        if { [ "$at" = "group" ] && [ "$group" = "$FIXTURE_GROUP_PARENT_ID" ]; } || { [ "$at" = "top" ] && [ -z "$group" ]; }; then
            echo "$name"
            return 0
        fi
    done < <(folders_on "$to")
    return 1
}

judge_the_transfers() {
    local deadline=$((SECONDS + JUDGE_SECONDS)) record from to op at source name known arrived
    local waiting=("${pending[@]}") still

    step "what the ${#pending[@]} transfers left behind (waiting at most ${JUDGE_SECONDS}s)"
    while [ "${#waiting[@]}" -gt 0 ]; do
        still=()
        for record in "${waiting[@]}"; do
            IFS='|' read -r from to op at source name known <<< "$record"
            # a move is through once its source is empty as well
            if arrived="$(find_arrival "$to" "$prefix$source" "$at")" \
                && { [ "$op" = "copy" ] || ! holds_the_photo "$from" "$prefix$source"; }; then
                claimed[$to/$arrived]=1
                pass "$name, $from to $to, $op at the $at: $(label_of "$to") has $arrived with the photo, in $([ "$at" = "group" ] && echo "$FIXTURE_GROUP_PARENT_NAME" || echo "no group")"
                if [ -n "$known" ]; then
                    note "   this cell is marked as failing because of #$known, and it passed: #$known may be fixed -- take the mark off"
                fi
                if [ "$op" = "copy" ]; then
                    if holds_the_photo "$from" "$prefix$source"; then
                        pass "   and $prefix$source still has its photo"
                    else
                        fail "   a copy took the photo out of $prefix$source"
                    fi
                else
                    pass "   and $prefix$source gave its photo up"
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
        IFS='|' read -r from to op at source name known <<< "$record"
        if [ -n "$known" ]; then
            note "$name, $from to $to, $op at the $at: still failing, as #$known says -- not counted"
        elif find_arrival "$to" "$prefix$source" "$at" > /dev/null; then
            fail "$name, $from to $to, $op at the $at: the folder arrived, but $prefix$source still has its photo after a move"
        else
            fail "$name, $from to $to, $op at the $at: nothing named after $prefix$source with the photo, in $([ "$at" = "group" ] && echo "$FIXTURE_GROUP_PARENT_NAME" || echo "no group"), turned up on $(label_of "$to")"
        fi
    done
}

# --- one cell -------------------------------------------------------------------------------

cell_number=0

# from, to: device / cloud / share. op: copy / move. at: top / group. source: the folder of
# [from] that is carried. expected: new / regroup / refused. known: an Issue number, for a cell
# that fails because of a bug filed there -- it is reported, not failed, until the Issue is fixed,
# and says so when it starts to pass
cell() {
    local from="$1" to="$2" op="$3" at="$4" source="$5" expected="$6" known="${7:-}"
    local name before arrived waited action refusal_text
    cell_number=$((cell_number + 1))
    name="72-$(printf '%02d' "$cell_number")"
    # FIXTURE_ONLY_CELLS="2 3" drives only those cells, by their number in the table below, while
    # a cell is being worked on
    if [ -n "${FIXTURE_ONLY_CELLS:-}" ] && [[ " $FIXTURE_ONLY_CELLS " != *" $cell_number "* ]]; then
        return 0
    fi
    step "$from to $to, $op at the $at: $expected"

    # every cell starts from a fresh start of the app, so that no search, selection or opened
    # group of the cell before is still standing
    app_stop
    app_start
    sleep 3
    switch_storage_to "$(label_of "$from")" "$name-storage" || return 0
    sleep 2
    if ! search_for "$prefix$source" "$name-list"; then
        fail "$prefix$source is not in the folder list of $(label_of "$from")"
        screenshot "$name-no-source"
        return 0
    fi

    select_row "$prefix$source" "$name-select" || { screenshot "$name-not-selected"; return 0; }
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

    if [ "$at" = "group" ]; then
        if ! search_for "$FIXTURE_GROUP_PARENT_NAME" "$name-picker-group"; then
            fail "$FIXTURE_GROUP_PARENT_NAME is not in the picker on $(label_of "$to")'s chip"
            screenshot "$name-no-group"
            "${ADB[@]}" shell input keyevent KEYCODE_BACK
            leave_the_selection
            return 0
        fi
        # opening a group from the search results closes the search
        ui_tap_exact_text "$FIXTURE_GROUP_PARENT_NAME" "$name-open-group" || true
        sleep 2
        if keyboard_is_shown; then
            "${ADB[@]}" shell input keyevent KEYCODE_BACK
            sleep 1
        fi
    fi

    before="$(folders_on "$to")"
    screenshot "$name-picker"
    if ! ui_tap_exact_text "OK" "$name-ok"; then
        screenshot "$name-no-ok"
        "${ADB[@]}" shell input keyevent KEYCODE_BACK
        leave_the_selection
        return 0
    fi

    case "$expected" in
        new)
            # judged later, with every other transfer, by judge_the_transfers()
            pending+=("$from|$to|$op|$at|$source|$name|$known")
            note "handed over; what arrives is judged once every cell has been driven"
            ;;

        regroup)
            sleep 5
            if arrived="$(arrived_folder "$to" "$prefix$source" "$before")"; then
                fail "$arrived turned up on $(label_of "$to"), a move on its own storage carries nothing"
            else
                pass "nothing was carried"
            fi

            local group
            group="$(group_of "$(app_path_of "$from" "$prefix$source")")"
            if [ "$at" = "group" ] && [ "$group" = "$FIXTURE_GROUP_PARENT_ID" ]; then
                pass "and $prefix$source is in $FIXTURE_GROUP_PARENT_NAME now"
            elif [ "$at" = "top" ] && [ -z "$group" ]; then
                pass "and $prefix$source is in no group now"
            else
                fail "$prefix$source is in group '${group:-none}', the OK was at the $at"
            fi

            if holds_the_photo "$from" "$prefix$source"; then
                pass "and it still has its photo"
            else
                fail "$prefix$source lost its photo to a change of group"
            fi
            ;;

        refused)
            if [ "$from" = "cloud" ]; then
                refusal_text="can only come from this device"
            else
                refusal_text="cannot be copied within the share"
            fi

            if ui_wait_text "$refusal_text" 10 "$name-refusal"; then
                pass "the picker says why it will not"
            else
                fail "the picker did not say '$refusal_text'"
                screenshot "$name-no-refusal"
            fi

            sleep 3
            if arrived="$(arrived_folder "$to" "$prefix$source" "$before")"; then
                fail "$arrived turned up on $(label_of "$to") all the same"
            else
                pass "and nothing was carried"
            fi

            "${ADB[@]}" shell input keyevent KEYCODE_BACK
            sleep 1
            ;;
    esac

    leave_the_selection
}

# --- the storages, and the app ---------------------------------------------------------------

step "putting a folder per cell on each storage"
mkdir -p "$FIXTURE_PCLOUD_DIR"
for folder in "${device_sources[@]}"; do put_folder device "$prefix$folder"; done
for folder in "${cloud_sources[@]}"; do put_folder cloud "$prefix$folder"; done
for folder in "${share_sources[@]}"; do put_folder share "$prefix$folder"; done

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
    FIXTURE_GROUP_EXTRA_MEMBER="$(app_path_of device "${prefix}DevInTrips")|$(app_path_of cloud "${prefix}CloudInTrips")" \
    "$TEST_DEVICE_DIR/fixture/seed-app.sh" > /dev/null

FIXTURE_STORAGE_FILTER=1
logcat_reset
app_start
sleep 4

step "walking the share, and listing pCloud, so that their folders have rows"
open_overflow_menu
sleep 1
ui_tap_text "Rescan the network share" "72-walk"
if ! wait_for_log "Walked the share:" 900 "72-walked"; then
    fail "the share was never walked"
    screenshot "72-no-walk"
    finish
fi

switch_storage_to "pCloud" "72-to-pcloud"
sleep 2
open_overflow_menu
sleep 1
ui_tap_text "Rescan pCloud" "72-list-pcloud"
if ! ui_wait_exact_text "${prefix}CloudCopy" 120 "72-pcloud-listed"; then
    fail "pCloud's folders did not turn up after a rescan"
    screenshot "72-no-pcloud"
    finish
fi

# --- the table: copies first, then the moves that carry, then the moves that only regroup ------

snapshot_the_storages
driving_started=$SECONDS

cell device device copy top DevCopy new
cell device device copy group DevCopy new
cell device cloud copy top DevCopy new 144
cell device cloud copy group DevCopy new
cell device share copy top DevCopy new
cell device share copy group DevCopy new

cell cloud device copy top CloudCopy new
cell cloud device copy group CloudCopy new
cell cloud cloud copy top CloudCopy new 144
cell cloud cloud copy group CloudCopy new
cell cloud share copy top CloudCopy refused
cell cloud share copy group CloudCopy refused

cell share device copy top ShareCopy new
cell share device copy group ShareCopy new
cell share cloud copy top ShareCopy new 144
cell share cloud copy group ShareCopy new
cell share share copy top ShareCopy refused
cell share share copy group ShareCopy refused

cell device cloud move top DevMvCloudTop new
cell device cloud move group DevMvCloudGroup new
cell device share move top DevMvShareTop new
cell device share move group DevMvShareGroup new

cell cloud device move top CloudMvDevTop new
cell cloud device move group CloudMvDevGroup new
cell cloud share move top CloudCopy refused
cell cloud share move group CloudCopy refused

cell share device move top ShareMvDevTop new
cell share device move group ShareMvDevGroup new
cell share cloud move top ShareMvCloudTop new 145
cell share cloud move group ShareMvCloudGroup new

note "the transfers were driven in $((SECONDS - driving_started))s"
judging_started=$SECONDS
judge_the_transfers
note "and judged in $((SECONDS - judging_started))s"

# after the judging, so that a late arrival of a copy cannot read as a regroup that carried.
# Into Trips first and out again, so that the top's "in no group" is a change and not where the
# folder already was
cell device device move group DevCopy regroup
cell device device move top DevCopy regroup
cell cloud cloud move group CloudCopy regroup
cell cloud cloud move top CloudCopy regroup
cell share share move group ShareCopy regroup
cell share share move top ShareCopy regroup

note "$cell_number cells driven"
screenshot "72-done"
finish
