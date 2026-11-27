# Changelog

All notable changes to Ladybird for Android, the unofficial Android port of the [Ladybird](https://ladybird.org)
browser. Versions follow `major.minor.patch` and stay below 1.0 while the port is in alpha.
[Documentation/BuildInstructionsAndroid.md](../../Documentation/BuildInstructionsAndroid.md) explains how releases are
made.

## Unreleased

### Fixed
- About › Licenses opens the licenses of Ladybird and the software it includes, and their notice now says that the
  source code of this app is in this repository.

## 0.1.0 (2026-11-27)

The first public alpha. It runs the Ladybird engine as of November 2026, with a browser interface made for Android.

### Browsing
- Tabs with a tab switcher, incognito tabs, and swiping sideways on the browser bar to go to the tab before or after.
- A new tab page with your most visited sites, plus history, bookmarks, downloads, find in page, sharing and zoom.
- Pull down a page at its top to reload it.
- Scrolling with Android's own fling, pinch to zoom, and frames timed to the display's refresh.
- Text in every script, with Android's system fonts for the characters a page's fonts lack.
- The keyboard for every kind of text field, including email and number fields and fields in embedded frames.

### Appearance
- Light and dark themes. In dark mode, sites with a dark theme of their own use it, and others are darkened.
- The browser bar takes a page's theme colour.

### Settings
- Search engine, theme, and desktop or mobile sites.
- Ad and tracker blocking with EasyList, EasyPrivacy and other filter lists, lists of your own, your own rules, and
  sites to leave alone.
- Global Privacy Control, and encrypted DNS with Cloudflare, Quad9, Google or a server of your own.
- When to unload tabs in the background, whether tabs share a process, and autoplay.

### Fixes in the engine
- Large messages between the browser's processes no longer fail on Linux kernels older than 6.7, which crashed pages.
- Taps on text fields in frames from another site focus them.
- Email and number fields bring up the keyboard.

### Known limitations
- This is alpha software. Expect sites that don't work, and crashes.
- Heavy pages scroll at 30 to 45 frames a second on a phone, depending on the site.
- Only 64-bit ARM phones running Android 11 or later are supported.
- The app doesn't update itself. Install new releases from GitHub over the old one.
