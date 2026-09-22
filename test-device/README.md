# Driving the app on a device (#78)

The checks that need a screen, a network and a service running — done against a fixture share that
can be thrown away, on an emulator that nobody is holding.

**Nothing here ever touches the maintainer's own pCloud account or network share.** That is the
point of this directory, more than the automation is. The real share holds thousands of personal
videos; every screenshot, log capture or scan count taken from it is personal data leaving the
device. The fixture holds generated test patterns, so a screenshot of it can be pasted anywhere.

The other half of testing — the plain logic, which needs no device at all — is `app/src/test/`,
issue #79. Anything that can be settled there is settled there, and these scripts are spent only
on what a device can show.

## What it needs

- an **emulator**. `adb devices` may list a phone, and every script refuses to run against one
    - installing it, once:

      ```sh
      sdkmanager --install emulator "system-images;android-35;google_apis;x86_64"
      avdmanager create avd -n gallery-fixture -k "system-images;android-35;google_apis;x86_64" -d pixel_6
      ```

    - it needs KVM, and the user has to be in the `kvm` group for that: `sudo gpasswd -a $USER kvm`,
      then a new login — or `sg kvm -c "<command>"` without one
    - starting it headless:

      ```sh
      sg kvm -c "$HOME/Android/Sdk/emulator/emulator -avd gallery-fixture -no-window -no-audio -no-snapshot -gpu swiftshader_indirect"
      ```

    - with a phone connected as well, every script needs `ANDROID_SERIAL=emulator-5554` so it
      cannot pick the wrong one
    - the refusal can be waved through with `FIXTURE_ALLOW_REAL_DEVICE=1`, which is there for a
      spare phone, not for the one in your pocket
- **docker**, for the Samba container that serves the fixture
- **ffmpeg**, to generate the videos and images
- **python3** and **rg**, used by the driving scripts
- the **debug build** installed: `io.github.aiya000.fossify.gallery.debug`. A separate app with its
  own settings, so pointing it at a fixture cannot disturb the release app — and `run-as` works on
  it, which is how the settings are seeded

## Running it

```sh
cd test-device

# 1. build the share, and serve it
./fixture/seed-share.sh
docker compose -f fixture/docker-compose.yml up -d

# 2. put the app into a known state (wipes the debug app's data)
ANDROID_SERIAL=emulator-5554 ./fixture/seed-app.sh

# 3. drive it
ANDROID_SERIAL=emulator-5554 ./drive/run-all.sh
```

Each script seeds the app itself, so any one of them can be run alone while a change is being
worked on:

```sh
ANDROID_SERIAL=emulator-5554 ./drive/20-storage-switch.sh
```

The whole run takes a few minutes, most of it spent walking the share three times over.

Screenshots, view trees and logs of a run land in `runs/<timestamp>/`, so a failure can be looked
at afterwards.

## What each script checks

- **`10-scan-whole-share.sh`** — a scan of the whole share, and the counts it reports. The fixture
  holds a known number of files, which the real share never could
- **`20-storage-switch.sh`** — rule 1 of #59: which scans a sideways swipe calls off. Three cases,
  each one a scan that has to be interrupted at the right moment, which is the check that costs the
  most by hand
- **`30-download-group.sh`** — #64: a whole group fetched and played in turn, and the order it is
  fetched in. The fixture's group holds a folder and a subgroup on purpose, because the rule worth
  pinning is that a subgroup is walked where it sits rather than after the plain folders
- **`40-share-thumbnails.sh`** — every medium in `Renders/` gets a thumbnail, whatever size the
  file is. The check is on the pixels rather than on the view tree, because the failure it was
  written for drew nothing at all — not even the warning icon — and a view tree says the same
  thing either way. It walks the whole share first, so on its own it is about five minutes
- **`45-properties-on-the-share.sh`** — #60: the (i) of a medium on the share opens a dialog, and
  that dialog says which storage the medium is on. It used to answer with a toast saying the
  source file does not exist and no dialog at all. Both ways in are driven, the viewer and the
  grid's selection, because they are two separate `showProperties()` and the bug was in both.
  What the dialog is recognised by is the **id of its rows**, not the word "Properties": the
  viewer's toolbar carries an icon with that same content description, and matching on the word
  passed on the build that still had the bug
- **`50-copy-off-share.sh`** — #28: a medium of the share copied onto the device, and the file
  actually arriving, whole and with the share's modification time. It also checks what must *not*
  be there: "Move to" is kept out of the selection's menu, moving between a share and anywhere
  else not being built. Copying the same file twice pins the numbering that `AvailableNameTest`
  covers in the small, through the whole path
- **`55-delete-on-the-share.sh`** — #28, the first thing the app takes *away* from the share: a
  medium of it deleted, and then a whole folder of it. Both are read off `fixture/share`, because
  the app's own cache cannot be a witness to a delete. What it checks besides the two files going
  is the file next to them staying — a delete that reached too far would pass every check that
  only looks at what was asked for — and the confirmation saying that there is no undo, with no
  "skip the recycle bin" option on it, there being no bin on a share to skip. It brings its own
  file and its own folder, because nothing the counts in `manifest.env` are about may be deleted
- **`60-copy-to-pcloud.sh`** — #28 the other way: the same medium copied to pCloud, which goes
  through the app's cache, an upload, and a scan of the destination folder. pCloud is
  `fixture/pcloud-stub.py` rather than an account; see below for why. What arrived is read off this
  machine, and the requests the app made are read out of the stub's log — that it asked for
  `nopartial` and `renameifexists` is the app's half of "a copy never writes over anything"
- **`65-rename-on-the-share.sh`** — #28: a medium of the share, and then a folder of it, given
  another name. Read off `fixture/share` for the same reason the delete is. The check it is really
  written for is the one in the middle: a rename to a name the share already has must be **refused**
  rather than written over, and the file that name belonged to is compared byte for byte
  afterwards — there is no recycle bin on a share to take an overwritten file back out of. The
  folder half checks that the medium under it travelled with it, since a rename that made an empty
  folder under the new name would pass everything else. Like `55`, it brings its own file and its
  own folder. None of the names it types holds a space, `adb shell input text` not being able to
  type one; the prefix matching that a name like "Trips 2026" makes interesting is asked about in
  `RenamedPathsTest` instead
- **`70-copy-to-the-share.sh`** — #28 in the direction that had no code at all until now: a file
  of the device copied *onto* the share. What arrived is read straight off `fixture/share`, which
  is the share's own answer rather than the app's. It starts with the check that would cost a
  file — the picker refusing a folder of the share as a *move* destination, with the share still
  empty of it, so "the share gained nothing" cannot pass by accident — and ends on the same
  numbering check as `50`, from the other side
- **`75-save-as-storages.sh`** — #92 and #71: "Save as" knows all three storages, and a photo of
  the share can be rotated and saved. The bug it was written for ended in an OS toast reading
  `java.io.FileNotFoundException: sm…`: the viewer handed the share's pseudo path to the file
  APIs, for the source it read as much as for the destination it wrote. It checks the chip row
  (this device, pCloud, the network share — the app is signed in to the stub so that a missing
  pCloud chip means something), that the path box no longer shows a raw `smb:` path glued to
  this device's label, and that the save lands as a file on the device while the photo on the
  share stays byte for byte what it was
- **`80-open-on-the-device.sh`** — where the folder list opens, and the order the storages stand
  in. Both are preferences rather than requirements, so nothing else breaks loudly when one is
  undone: the list has to open on this device whatever storage it was left on, and the storage
  menu has to lead with "All storages", above the device. Where the list is gets read off the mark
  in the storage menu, not off the folders on screen — with nothing scanned, both storages draw
  the same empty grid, and a check that cannot tell them apart passes whatever the app does
- **`90-fetch-only-when-asked.sh`** — #87: the list is fetched again only when the settings say so,
  for both events that can ask and for both storages alike. Each case runs twice, once with its
  setting off and once on, because the "off" half would pass on an app that fetches nothing ever.
  The pCloud half is the one that fails without the fix: a pull used to list the account whenever
  pCloud was on screen, with no policy asked and so no setting to turn it off with. Whether the app
  fetched is read off the requests the stub was sent, which is pCloud's own answer rather than the
  app's

## pCloud, without a pCloud account

`fixture/pcloud-stub.py` answers the slice of the API this app calls — `userinfo`, `diff`,
`listfolder`, `uploadfile`, `getthumb`, `getfilelink` — out of a directory on this machine. The
app is pointed at it by seeding a token and a host into its settings, which is all the OAuth
screen leaves behind.

A throwaway account was the other way to do it, and it was turned down: it would put a token
somewhere outside this repository, make every run depend on a network, and leave the account
drifting between runs. What is under test is the app's path from a share to pCloud, not pCloud.

The stub serves plain http, because an https one would need a certificate the app is built to
trust — and a debug build carrying a certificate out of this repository is a worse thing to have
installed on a phone than this test is worth. `pCloudUrl()` lets the stored host carry its own
scheme for exactly this, and `app/src/debug/res/xml/network_security_config.xml` allows cleartext
to `10.0.2.2` and nowhere else. Nothing pCloud itself hands out carries a scheme, so the release
build is unaffected.

What the stub cannot say anything about is pCloud's own behaviour — how it numbers a name that is
taken, what it does with a half-finished upload. Those are asserted on the request the app sent
instead of on the answer.

## What is not covered yet

- **Signing in to pCloud.** The OAuth screen needs a client id that cannot be published, and the
  scripts step around it by seeding the token the screen would have stored
- **Rule 3 of #59** — a transfer cutting into a running scan. It is pCloud's rule, and now that
  there is a stub there is nothing else in the way of writing it
- **The rest of writing to the share** (#28) — moving onto it or off it, and renaming. Neither is
  built, so what the scripts pin is the refusal: `50` that "Move to" is not offered for a medium
  of the share, `70` that a folder of the share is turned away as a move destination. Deleting is
  built and is `55`'s
- **Playback itself.** The scripts check that the videos arrive and in what order; watching them
  play through is still done by eye
- **The refresh spinner itself** (#87). What the spinner is raised *from* is driven by `90`; the
  spinner is not. It is up only for as long as the cache takes to be read, and uiautomator gives it
  no name to look for, so a check on it would be a race dressed as a test. If it ever needs
  pinning, the way in is a pixel check on the top strip of a screenshot, like `40` does for
  thumbnails -- and a way to hold the cache read still long enough to photograph

## Why the fixture is 2000 folders

Four folders and eleven files is what the counts are *about*, and it was the whole fixture to
begin with. It could not be used: a share that small is walked in under a second, so rule 1 of #59
— what a swipe does to a scan that is **running** — had nothing to interrupt. Every attempt ended
with the scan finished before the storage had been left.

`FIXTURE_FILLER_FOLDERS` folders of one video each sit under `Filler/`, named `zz0001` upwards so
they sort after everything the tests look for. They make the walk take long enough to be
interrupted, and they keep the counts exact, because they are counted too.

## Things worth knowing before changing any of this

**The settings export does not carry the share.** #78 hoped to seed the app by importing a settings
file, because the storage configuration was thought to be in it. It is not: `setupExportSettings()`
writes the folder groups and the hidden folders, and no host, share name, user or password. So the
settings are written straight into `shared_prefs/Prefs.xml` through `run-as` instead, which is what
`fixture/seed-app.sh` does.

**A scan started by arriving at a storage is not called off by leaving it.** The ranks are
`AUTO(0) < SWITCH(1) < MANUAL(2) < TRANSFER(3)`, and `leftBehind()` drops only what ranks *below*
`SWITCH`. The rescan the settings start when you switch storage is itself ranked `SWITCH`, so
swiping away again leaves it running. What a swipe does call off is the scan the settings start on
launch or on their interval, which ranks `AUTO`. `20-storage-switch.sh` covers both, and says which
is which.

**No XML comments in the seeded preferences.** Android reads `shared_prefs/Prefs.xml` with a
reader that is not a general XML parser, and a comment in it loses everything after the comment.
It showed up here as a share that scanned perfectly and a folder list that then drew nothing, with
the seeded preferences back at their defaults. `seed-app.sh` explains the blocks in shell comments instead.

**`input swipe` does not change storage.** The gesture a user makes is a sideways drag of the
folder list, and that is the one #59 ranked below a manual scan — but `input swipe` synthesises
too few move events for the list's drag detection, so the swipe does nothing and a script built on
one passes without testing anything. The scripts use the toolbar's Storage chip, which runs the
same `switchStorage()` and so the same ranks.

**The storage a script starts on is driven to, not seeded.** The folder list opens on this device
whatever storage it was left on, so writing `storage_filter` into the preferences decides nothing:
the app overwrites it the moment it starts. `app_start` reads `FIXTURE_STORAGE_FILTER` and taps
its way to that storage through the chip, which is also how the user gets there — so a script
begins in a state the app can actually reach, with whatever scan the arrival carries.

**A long press has to be held with `motionevent`.** `input swipe x y x y 800` is not a press at
all as far as the app is concerned. `select_row` holds DOWN, waits, releases, and then checks that
the toolbar is counting a selection before going on.

**The selection's toolbar is drawn over the ordinary one, and both are in the view tree.** The
three dots that open the selection's menu are the *second* pair, which is what `ui.py --last` is
for.

**The Samba container takes the share over unless it is given the host user's ids.** Without the
uid and gid in its `-u`, `seed-share.sh` cannot add to the tree afterwards.
