# Fossify Gallery

<img alt="Logo" src="graphics/icon.webp" width="120" />

<a href='https://play.google.com/store/apps/details?id=org.fossify.gallery'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a> <a href="https://f-droid.org/en/packages/org.fossify.gallery/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.svg" alt="Get it on F-Droid" height=80/></a> <a href="https://apt.izzysoft.de/fdroid/index/apk/org.fossify.gallery"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height=80/></a>

> [!NOTE]
> This is a personal fork of [FossifyOrg/Gallery](https://github.com/FossifyOrg/Gallery), built and
> installed by hand. The store badges above lead to the official app, which does not contain the
> changes listed under [Fork-specific features](#fork-specific-features).

Unleash memories, not personal data. Fossify Gallery is the ultimate photo and video app that's as powerful as it is private. No ads, no unnecessary permissions – just a seamless experience tailored for you.

**🖼️ PHOTO EDITING AT YOUR FINGERTIPS:**  
Enhance your photos with our basic yet powerful photo editor. Crop, resize, rotate, flip, draw, and apply stunning filters, all without compromising your privacy. Take control of your memories like never before.

**🌐 PRIVACY FIRST, ALWAYS:**  
Your privacy matters. Ditch the data-hungry giants. Fossify Gallery puts you in control. Strip away EXIF metadata like GPS coordinates and camera details, keeping your memories yours, and yours alone.

**🔒 SUPERIOR SECURITY:**  
Lock down your memories with pin, pattern, or fingerprint protection. Secure specific photos, videos, or the entire app – you decide who gets access. Peace of mind, guaranteed.

**🔄 RECOVER WITH EASE:**  
Breathe easy, accidents happen! Fossify Gallery's built-in recycle bin lets you recover deleted photos and videos in seconds. No more lost treasures, just pure relief.

**🎨 YOUR GALLERY, YOUR STYLE:**  
Customize the look, feel, and functionality to match your style. From UI themes to function buttons, Fossify Gallery gives you the creative freedom you crave.

**📷 UNIVERSAL FORMAT FREEDOM:**  
JPEG, JPEG XL, PNG, MP4, MKV, RAW, SVG, GIF, AVIF, videos, and more – we've got your memories covered, in any format you choose. No restrictions, just limitless possibilities.

**✨ MATERIAL DESIGN WITH DYNAMIC THEMES:**  
Experience the beauty of intuitive material design with dynamic themes. Want more? Dive into custom themes and make your gallery truly unique.

➡️ Explore more Fossify apps: https://www.fossify.org<br>
➡️ Open-Source Code: https://www.github.com/FossifyOrg<br>
➡️ Join the community on Reddit: https://www.reddit.com/r/Fossify<br>
➡️ Connect on Telegram: https://t.me/Fossify

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>

## Fork-specific features

Everything above describes the upstream app. This fork adds the following on top of it.

### Virtual folder groups

- Folders can be collected into groups that are not real directories, so the folder list can be tidied up without moving a single file
- Groups can be nested; tapping one opens it and the back button goes up a level
- A group behaves like a folder everywhere else: it can be pinned, sorted, dragged into a custom order, locked and found by search
- "Move to" accepts a group as the destination, and groups themselves can be moved into other groups
- A group thumbnail is a 2x2 collage of the first images inside it
- Groups get "Rename" and "Ungroup" (which moves their content one level up) instead of filesystem operations like delete, hide or exclude
- Every folder picker lists groups and walks into them, including the one used by "Move to", "Copy to" and the widget configuration
- The settings export carries the groups and the folders assigned to them, so they survive a move to another install. Importing takes them over as a whole, replacing whatever groups the device had

### Sorting media

- "Grouped by file name, then date taken", and the same for "last modified", keep the files of one batch together: everything whose name is alike up to its first separator (`-` `_` `.` `+`) is one group, ordered by file name and dated by its first file, so the seconds between the files can never tear a batch apart
- "Use for this folder only" sits at the top of the sorting dialog rather than under the list of sortings, which has grown long enough to need scrolling
- "Export sorting" and "Import sorting" in the settings write and read every sorting there is, the per folder ones, both drag orders and the per storage folder list sortings included, which the settings export left out. The export also records which storages have a sorting of their own, so a storage switched back to the shared sorting before the export is switched back after the import as well
- The settings export carries the sorting as well unless the switch next to it is turned off, and a file written without it is named `…_excluding_orders`
- A settings export can be fed into "Import sorting" too: only its sorting lines are taken, so the colours and the rest are left alone

### Reordering media by hand

- The sorting dialog of a folder offers "Reorder media by dragging", which keeps the thumbnails in the order they were dragged into
- The order belongs to one folder, so "Use for this folder only" is turned on and locked while that option is selected
- The dragging itself starts from the selection menu, which also offers "Move to top" and "Move to bottom", the same way the folder list already worked

### Selecting media

- While the selection mode is active, each item carries a "Preview" button that opens it fullscreen without touching the selection
- A fullscreen view opened that way carries a check button over the top right of the image, so the item on screen can be taken into the selection or dropped from it without going back to the grid first
- Deleting an item only takes that one item out of the selection, instead of clearing the whole selection

### Thumbnails

- "Show file names at thumbnails" is a switch in the Thumbnails section of the settings, next to the other thumbnail options, instead of an overflow menu item only

### pCloud, and what it means for "FOSS"

- This fork is growing pCloud support, so unlike the upstream app it declares `INTERNET` and `ACCESS_NETWORK_STATE`
- The app stays free software. It is still GPL-3.0, and the only dependency the network code pulls in is [OkHttp](https://square.github.io/okhttp/) (Apache-2.0); no proprietary SDK or binary blob is linked in, and the pCloud API is spoken to over plain HTTP requests written in this repository
- What is not free is the service on the other end. pCloud is a hosted, proprietary service, so in F-Droid's vocabulary a build of this fork carries the [`NonFreeNet`](https://f-droid.org/en/docs/Anti-Features/) antifeature: a feature that depends on a network service that is not free software
- Nothing about it is forced on anyone. Local folders keep working exactly as they did, and the pCloud side stays asleep until it is signed in to

### What pCloud looks like from the gallery

- Once signed in (Settings → pCloud account), the storage switch in the folder list's menu shows the folders of this device, of pCloud, or both; dragging the folder list sideways switches it too, like turning a page: to the left brings pCloud, to the right this device, and the icon next to the search bar shows which one is on screen. pCloud folders are listed from a cache that is filled by "Rescan pCloud" and kept up to date by cheap diff syncs afterwards: when the app starts, when the storage is switched, when a folder is opened, after a write, each of them a switch in the settings with a minimum interval between them
- Thumbnails, the fullscreen view and video playback stream from pCloud; the file behind a photo is fetched once and kept in a bounded cache on the device
- Deleting, renaming and creating folders act on pCloud itself. A deleted file goes to the trash on pcloud.com, not to the device's recycle bin
- Copying and moving cross the line in both directions: device to pCloud, pCloud to device, or within pCloud. Long transfers run as a foreground service with a progress notification, which is what `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` are declared for
- The destination picker narrows its list to this device, pCloud or both with a row of chips, and its "Other folder" picker walks the storages the same way, pCloud included: any pCloud folder can be picked there, empty or not, and a new one created on the spot
- Favorites, search, slideshows, widgets and virtual folder groups take pCloud media like local media. Editing, rotating, wallpaper and the other tools that want a file on the device stay hidden for it

### Building with pCloud

The pCloud side needs a client id of your own, which does not ship with this repository:

1. Register an app under "My apps" at [docs.pcloud.com](https://docs.pcloud.com/) and add the redirect URIs `pcloud-oauth://io.github.aiya000.fossify.gallery` (release builds) and `pcloud-oauth://io.github.aiya000.fossify.gallery.debug` (debug builds)
2. Put the client id into `local.properties` next to the SDK path: `PCLOUD_CLIENT_ID=your_client_id`
3. Build as usual. The id is compiled into `BuildConfig` and never committed, `local.properties` is ignored by git

No client secret is involved: the app signs in with the implicit grant, so the token comes straight back to it from the browser. A build without a client id still works for local folders and says so when the pCloud sign-in is tried.

### Builds

- This fork builds under the application id `io.github.aiya000.fossify.gallery`, so it installs next to the official Fossify Gallery instead of replacing it
- Its launcher icon is pastel cyan rather than the upstream green, so the two are easy to tell apart on the home screen
- Debug builds use the application id `io.github.aiya000.fossify.gallery.debug` and an orange launcher icon, so they can be told apart from a release build installed next to them
- **This is not the official Fossify Gallery, and it is not supported by the Fossify project.** Report anything that goes wrong here, not to them. The official app lives at [fossify.org](https://www.fossify.org) and under the store badges at the top of this file
    - The upstream app warns at random that a build whose application id does not start with `org.fossify.` is "a fake version". That warning is meant for repackaged APKs handed out by third parties, and it is switched off here because this fork is built from source. This bullet is what takes its place
