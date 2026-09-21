#!/usr/bin/env bash
# Puts the app into the state the driving scripts expect: pointed at the fixture share, with the
# groups of #64 already made, and with nothing cached from an earlier run.
#
# The settings are written straight into the app's own preferences rather than typed through the
# settings screen. Two reasons: typing a host, a share name and a password with `input text` is
# the slowest and most brittle part of driving this app, and the settings export -- which #78
# hoped to use -- does not carry the share's host or credentials. It carries the folder groups and
# the hidden folders only. Look at setupExportSettings() before changing this.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../drive/lib.sh
source "$here/../drive/lib.sh"

require_emulator

if ! "${ADB[@]}" shell pm path "$FIXTURE_PACKAGE" > /dev/null 2>&1; then
    echo "$FIXTURE_PACKAGE is not installed. Build it with the debug-build skill and install it first." >&2
    exit 1
fi

step "clearing $FIXTURE_PACKAGE"
# everything: the preferences, the media cache, the downloaded videos. A scan has to start from
# nothing or the counts it reports are the last run's
"${ADB[@]}" shell pm clear "$FIXTURE_PACKAGE" > /dev/null

step "granting the permissions the first run would otherwise ask for"
for permission in \
    android.permission.READ_MEDIA_IMAGES \
    android.permission.READ_MEDIA_VIDEO \
    android.permission.READ_EXTERNAL_STORAGE \
    android.permission.POST_NOTIFICATIONS; do
    "${ADB[@]}" shell pm grant "$FIXTURE_PACKAGE" "$permission" 2> /dev/null || true
done

prefs="$RUN_DIR/Prefs.xml"
# &quot; because these values are JSON living inside an XML attribute's text
cat > "$prefs" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="smb_host">$FIXTURE_SMB_HOST</string>
    <int name="smb_port" value="$FIXTURE_SMB_PORT" />
    <string name="smb_share">$FIXTURE_SHARE_NAME</string>
    <string name="smb_root_path">$FIXTURE_SMB_ROOT_PATH</string>
    <string name="smb_user">$FIXTURE_SMB_USER</string>
    <string name="smb_password">$FIXTURE_SMB_PASSWORD</string>
    <string name="smb_domain">$FIXTURE_SMB_DOMAIN</string>

    <!-- which scans the settings may start. Off by default: a scan nobody asked for, arriving
         in the middle of a test, is the one thing that makes a run unreadable -->
    <boolean name="smb_rescan_on_storage_switch" value="$FIXTURE_RESCAN_ON_STORAGE_SWITCH" />
    <boolean name="smb_rescan_on_launch" value="$FIXTURE_RESCAN_ON_LAUNCH" />
    <boolean name="smb_rescan_on_folder_open" value="false" />
    <boolean name="smb_rescan_on_pull_to_refresh" value="false" />
    <int name="smb_rescan_interval_minutes" value="0" />
    <!-- an emulator's network counts as metered often enough to be worth ruling out -->
    <boolean name="smb_rescan_on_unmetered_only" value="false" />

    <!-- the groups of #64: a group holding a folder and a subgroup, which is the shape that
         shows whether a subgroup is walked where it sits or after the plain folders -->
    <string name="folder_groups">[{&quot;id&quot;:$FIXTURE_GROUP_PARENT_ID,&quot;name&quot;:&quot;$FIXTURE_GROUP_PARENT_NAME&quot;},{&quot;id&quot;:$FIXTURE_GROUP_CHILD_ID,&quot;name&quot;:&quot;$FIXTURE_GROUP_CHILD_NAME&quot;,&quot;parentId&quot;:$FIXTURE_GROUP_PARENT_ID}]</string>
    <string name="folder_group_members">{&quot;smb:/Trips/Osaka&quot;:$FIXTURE_GROUP_PARENT_ID,&quot;smb:/Trips/Kyoto&quot;:$FIXTURE_GROUP_CHILD_ID}</string>

    <!-- the folder list opens on the share, so that the scans under test are of the share -->
    <int name="storage_filter" value="$FIXTURE_STORAGE_FILTER" />
    <int name="directory_sort_order" value="$FIXTURE_DIRECTORY_SORT" />

    <!-- the welcome and rating prompts are not what is under test -->
    <int name="app_run_count" value="5" />
    <boolean name="is_using_shared_theme" value="false" />
</map>
XML

step "writing the fixture settings into the app"
"${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" sh -c 'mkdir -p shared_prefs && cat > shared_prefs/Prefs.xml' < "$prefs"

written="$("${ADB[@]}" shell run-as "$FIXTURE_PACKAGE" sh -c 'cat shared_prefs/Prefs.xml' | tr -d '\r' | rg -c 'smb_host' || true)"
if [ "$written" != "1" ]; then
    echo "the preferences did not land; run-as may not be allowed on this build" >&2
    exit 1
fi

note "share: //$FIXTURE_SMB_HOST:$FIXTURE_SMB_PORT/$FIXTURE_SHARE_NAME as $FIXTURE_SMB_USER"
note "a copy of what was written is at $prefs"
echo
echo "the app is seeded. Start the fixture share if it is not up:"
echo "    docker compose -f $TEST_DEVICE_DIR/fixture/docker-compose.yml up -d"
