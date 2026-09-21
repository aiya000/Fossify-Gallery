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
    - the Android SDK here has no emulator installed yet. It is two downloads:
      `sdkmanager --install emulator "system-images;android-34;google_apis;x86_64"`, then
      `avdmanager create avd -n gallery-fixture -k "system-images;android-34;google_apis;x86_64"`
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
./fixture/seed-app.sh

# 3. drive it
./drive/run-all.sh
```

Each script seeds the app itself, so any one of them can be run alone while a change is being
worked on:

```sh
./drive/20-storage-switch.sh
```

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

## What is not covered yet

- **pCloud.** It needs a real account and a client id that cannot be published. Either a throwaway
  account whose credentials stay out of this repository, or a stub answering the slice of the API
  the app uses. Rule 3 of #59 — a transfer cutting into a running scan — is pCloud's, so it waits
  on this
- **Playback itself.** The scripts check that the videos arrive and in what order; watching them
  play through is still done by eye

## Two things worth knowing before changing any of this

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
