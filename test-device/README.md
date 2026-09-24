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
- **`12-find-new-folders.sh`** — #127: "Find new folders" puts the folders of the remote
  storages that the app has no row for yet into the list, without the full rescan a share of a
  thousand folders needs. After one walk of the share, a folder with a photo is put on the share
  and the search has to find it -- one folder, one file, and the row in the list -- while a photo
  put into a folder the app already knows must go unread, the search reporting nothing new. The
  witnesses are the counts the service logs when a search is over, and the absence of
  "Walked the share:" in between. pCloud is asked the same way through the stub: on an account
  never listed every folder is new, and asked again straight after, nothing is
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
- **`47-hand-out-a-medium-of-the-share.sh`** — #71: a photo of the share handed to another app
  ("Open with"), offered as a wallpaper ("Set as"), asked for its place on the map, printed, and
  resized -- everything the fullscreen viewer offers that wants a real file to read. Each of
  these used to end in a toast saying the file could not be fetched from the network share. What
  says the file was handed over is somebody else's window coming in front: the system's chooser,
  the print spooler's preview, and the map opened on the place the script wrote into the photo's
  EXIF, which is only asked for after that EXIF was read. The resize is the one with a witness on the share: the
  photo is written back over itself half as wide, read with `ffprobe` off `fixture/share`, with
  the same stash and neighbour checks `85` makes. It brings its own file and takes it away again
- **`50-copy-off-share.sh`** — #28: a medium of the share copied onto the device, and the file
  actually arriving, whole and with the share's modification time. It also checks what must *not*
  be there: "Move to" is kept out of the selection's menu, moving between a share and anywhere
  else not being built. Copying the same file twice pins the numbering that `AvailableNameTest`
  covers in the small, through the whole path
- **`55-delete-on-the-share.sh`** — #112: a medium of the share, and then a whole folder of it,
  deleted into the app's recycle bin *on the share* (`.gallery-recycle-bin` in its root, the same
  folder the app makes on pCloud), then one of them restored out of it and the rest emptied for
  good. Everything is read off `fixture/share`, because the app's own cache cannot be a witness
  to a move. What it checks besides the files turning up in the bin under their original layout
  is the file next to them staying — a delete that reached too far would pass every check that
  only looks at what was asked for — the confirmation offering the bin with the "skip the recycle
  bin" checkbox on it, a rescan of the share counting the fixture's files and not the bin's, the
  one recycle bin tile being in the folder list on the share's own storage filter, and the
  restored file being byte for byte what it was. It brings its own file and its own folder,
  because nothing the counts in `manifest.env` are about may be deleted, and takes the bin away
  with them on its way out
- **`56-delete-on-the-device.sh`** — #106: the same two deletes on this device, which go through
  the app's recycle bin. The device's delete is the one the app was born with and it had no
  script until deleting moved onto `MediaStorage`; a move with no witness is a move nobody can
  vouch for. The file leaving `/sdcard` is read with `adb shell`, and its turning up in the bin
  with `run-as`, the bin being the app's own files directory with the file kept under its full
  original path -- a delete that skipped the bin would pass the first check and fail the second.
  It pins the same confirmation `55` does: it says "recycle bin", and the "skip the recycle bin"
  checkbox is on it. Then the way back out (#112): the one recycle bin opened, the medium
  restored through the same dialog every storage gets, back where it was with its bytes and with
  its copy gone from the bin -- the copy used to stay behind until the bin was emptied -- and
  "Empty the recycle bin" taking the rest away, the tile with it
- **`60-copy-to-pcloud.sh`** — #28 the other way: the same medium copied to pCloud, which goes
  through the app's cache, an upload, and a scan of the destination folder. pCloud is
  `fixture/pcloud-stub.py` rather than an account; see below for why. What arrived is read off this
  machine, and the requests the app made are read out of the stub's log — that it asked for
  `nopartial` and `renameifexists` is the app's half of "a copy never writes over anything"
- **`65-rename-on-the-share.sh`** — #28: a medium of the share, and then a folder of it, given
  another name. Read off `fixture/share` for the same reason the delete is. The check it is really
  written for is the one in the middle: a rename to a name the share already has must be **refused**
  rather than written over, and the file that name belonged to is compared byte for byte
  afterwards — an overwritten file does not pass through the recycle bin, on any storage. The
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
  menu has to list All storages, pCloud, this device and the network share in that order (#128),
  which the app is signed in to pCloud for, so that every row is there. Where the list is gets read off the mark
  in the storage menu, not off the folders on screen — with nothing scanned, both storages draw
  the same empty grid, and a check that cannot tell them apart passes whatever the app does
- **`81-choose-the-storage-order.sh`** — #128, second step: "Storage order" in the settings lists
  all four storages in the default order, All storages is dragged by its handle from the top to
  the bottom, and after OK both the settings row and the storage menu of the folder list read
  pCloud, this device, the network share, All storages. The menu is the witness that matters: a
  dialog that only reordered itself would pass everything before it. The drag is held and moved
  in steps, since ItemTouchHelper swaps a row only once the dragged one passes the next one's middle
- **`83-a-search-and-a-mixed-selection.sh`** — #107: two places the share was left out of a rule
  the other storages had. A video of the share tapped in the search results opens in the app's
  own player rather than being handed to the system player as a pseudo path, which is what the
  video player setting's default after the first run would do; and a selection of folders mixing
  this device and the share is offered no "Move to" (nor "Copy to"), like every other action that
  goes through a storage, with the share's folder on its own still offered it as the control
- **`84-a-new-folder-on-the-share.sh`** — #107: a folder made on the share from inside a folder
  of it, through the grid's "Create new folder", which was kept off the menu for the share after
  the write step it was waiting for had landed. What says the folder exists is the share, read off
  `fixture/share`; what says the app knows about it is the temporary tile in the folder list,
  since an empty folder has no row; and leaving the app has to leave the folder there, the app
  deleting no folder on a remote storage on its own
- **`86-rotate-and-resize-in-place-on-the-share.sh`** — #107: the last two things the grid's
  selection could do to a photo of this device and not of the share. One photo is turned where
  it lies, then both are shrunk at once, each fetched into a copy, changed there and written back
  over itself through the same stash and replace an overwrite uses. The witness is the share,
  read off `fixture/share`: a turned JPEG is one whose EXIF orientation tag reads 6, read with
  Pillow, since the app turns a JPEG by its tag and not its pixels, the same as on the device; a
  shrunk one is three quarters as wide in pixels, read with `ffprobe`; nothing is left under the
  stash name, and the photo beside them is untouched. It brings its own two files and takes them
  away again
- **`90-fetch-only-when-asked.sh`** — #87: the list is fetched again only when the settings say so,
  for both events that can ask and for both storages alike. Each case runs twice, once with its
  setting off and once on, because the "off" half would pass on an app that fetches nothing ever.
  The pCloud half is the one that fails without the fix: a pull used to list the account whenever
  pCloud was on screen, with no policy asked and so no setting to turn it off with. Whether the app
  fetched is read off the requests the stub was sent, which is pCloud's own answer rather than the
  app's
- **`92-ask-before-a-rescan-on-mobile-data.sh`** — #124: on mobile data, with "unmetered only"
  on, a rescan the user started with a gesture asks first instead of being skipped without a
  word. The network is the emulator's own: with its Wi-Fi off, the emulated mobile network takes
  over, which the system counts as metered, and the host is still reachable over it -- so a yes
  has something to walk. The pull is driven against the share, since a walk of a thousand
  folders is what the setting is there to keep off mobile data, and the scan starting is the
  witness; the folder half is driven against the pCloud stub, because a folder to open needs a
  list with rows in it, which is a second on the stub and a five-minute walk on the share. It
  pins that No walks nothing, that Yes walks exactly what the question named (the whole share,
  or this one folder), that a yes is remembered for the next pull on the same trip and a no is
  not, and that on Wi-Fi nothing is asked at all. The Wi-Fi is put back on the way out
- **`93-a-pull-inside-a-group.sh`** — a pull on the folder list reaches what is on screen. At the
  top it walks the whole share and lists the whole pCloud account; inside the group Trips, which
  the seed is given a pCloud folder for (`FIXTURE_GROUP_EXTRA_MEMBER`), it rescans the share's two
  folders of the group one by one without a walk, lists only the pCloud folder of the group and
  never asks the account for its diff, and this device's recheck goes through the group's three
  folders only. The share is read off the app's log, pCloud off the stub's request log. The two
  remote scans share one queue, so pCloud's comes after the walk and the script waits for it.
  Red with the pull inside the group handing on no folders: the share is walked whole
- **`98-save-as-out-of-the-editor.sh`** — #105: the editor's "Save as" asks where and under what
  name on every storage, and sends the edit there. A photo of the share is edited twice, and each
  edit saved under a new name somewhere else: into another folder of the share, read off
  `fixture/share`, and onto this device, read off `/sdcard`. What it pins besides the copies
  arriving is the original staying byte for byte what it was, with nothing stashed beside it -- a
  "Save as" that went back over the photo would pass the first check and fail the second -- and
  the screen behind the editor not warning that the editor "saved elsewhere", which is what it
  used to say whenever the copy came back untouched. `97` keeps the other save, the one that
  writes back over the original
- **`99-save-as-proposes-a-new-name-on-the-device.sh`** — #125: the editor's "Save as" opens on
  `<name>_1` for a photo of this device, the same as for one of pCloud or of the share, so that
  the OK that follows saves beside the original rather than asking about writing over it. It
  was filed as a bug and turned out not to be one on Android 11 and up -- commons hands the
  editor no output uri there, so the name comes off the photo's own path -- but the answer was
  worth pinning, since the content-uri road that proposes the photo's own name is still there
  for a photo handed in by another app. Nothing is saved: the dialog is read and cancelled. The
  system's "Edit with" sheet, which a second editor on the device puts between the viewer and
  the editor, is answered "Just once"

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
