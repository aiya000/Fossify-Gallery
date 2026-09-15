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

### Reordering media by hand

- The sorting dialog of a folder offers "Reorder media by dragging", which keeps the thumbnails in the order they were dragged into
- The order belongs to one folder, so "Use for this folder only" is turned on and locked while that option is selected
- The dragging itself starts from the selection menu, which also offers "Move to top" and "Move to bottom", the same way the folder list already worked

### Thumbnails

- While the selection mode is active, each item carries a "Preview" button that opens it fullscreen without touching the selection
- "Show file names at thumbnails" is a switch in the Thumbnails section of the settings, next to the other thumbnail options, instead of an overflow menu item only

### Builds

- Debug builds use the application id `org.fossify.gallery.debug` and an orange launcher icon, so they can be told apart from a release build installed next to them
