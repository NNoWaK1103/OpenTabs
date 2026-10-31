# OpenTabs 0.3.2

A native Kotlin / Jetpack Compose guitar companion with direct **Songsterr** and **Ultimate Guitar** search, an embedded Android WebView, and an offline six-line tablature viewer. This update reuses the v0.3 browser implementation from Git history and removes the metadata-discovery workflow.

## Find a guitar tab

1. Open **Search** and enter a **Song title**, **Artist**, or both.
2. Select **Songsterr** or **Ultimate Guitar**, then tap **Find tabs** (or the keyboard Search key).
3. The provider's original search results open inside OpenTabs. Select a tab on that page to view it.
4. Use Back/Forward, Refresh/Stop, Zoom, Full screen, Screen awake, or source switching in the existing viewer toolbar. **Native tabs** returns to the search form.

These are the only online discovery sources. Typing in the form does not start network searches. There are no metadata-only song cards, additional online catalogs, private API calls, or scraping. Provider results remain the website's own UI with a persistent source label and current HTTPS address.

If a site blocks embedded viewing, a page fails, or WebView is unavailable, choose **Open in browser**. Website ads, cookie consent, authentication, subscriptions, and access restrictions remain controlled by the provider. No content or access controls are modified. See [browser lifecycle, security, and limitations](docs/WEB_BROWSER.md) and [history inspection and reuse](docs/SEARCH_SIMPLIFICATION.md).

## Guitar tuner

Select **Tuner** in the sidebar or bottom navigation to open [guitar-tuner.org](https://guitar-tuner.org/) inside OpenTabs. Choose a guitar tuner on the website, tap its microphone switch, and allow Android microphone access when prompted. Only the trusted tuner origin can receive microphone permission; other sites and camera requests are denied. Leaving Tuner or backgrounding destroys its WebView to stop capture. **Back to tab** restores the previous native or online tab where possible. See [tuner permissions, lifecycle, and validation](docs/TUNER.md).

## Saved tabs and practice

**My Tabs** retains local title/artist/album/tuning search, favorites, recents, practice lists, and all existing saved files and source/license attribution. Open a saved tab in the native monospace viewer for six-string formatting, horizontal/vertical scrolling, adjustable text size and spacing, auto-scroll, full screen, and screen-awake controls. The play button scrolls text; it does not play audio.

Three original offline exercises remain included. **Import** supports validated `.txt` / `.tab` text and ZIP-based GP7 `.gp` files with authored six-string frets. GP7 previews show written measures; repeats, expressive techniques, rhythmic spacing, and playback are not interpreted. GP3–GP5, GPX, and MusicXML imports remain unsupported. Only import material you are authorized to use. See [sources and native format limits](docs/PROVIDERS.md).

Website pages are not extracted or saved as native tabs. Previous native downloads remain readable offline. The online download/discovery pipeline and its HTTP dependencies have been removed; saved library serialization remains compatible.

## Voice and existing connections

Tap the microphone and choose **Start voice command**. Android's installed speech service handles recognition and microphone access; manual controls remain available when it is missing. No background recording is added.

Supported commands include `Find tabs for [title] by [artist]`, `Open my favorites`, `Show my practice list`, `Start scrolling`, `Stop scrolling`, `Increase tab size`, and `Decrease tab size`. Voice search fills the same manual form and retains the selected tab source.

The existing Spotify and music-identification integration boundaries are unchanged. They remain explicitly unconfigured/unavailable in this repository; this update does not add credentials, SDKs, polling, or simulated connections.

## Install

1. Open [Android APK workflow runs](https://github.com/NNoWaK1103/OpenTabs/actions/workflows/android-build.yml), including the pull request's successful run.
2. Download **OpenTabs-debug-apk** under Artifacts (GitHub sign-in required).
3. Unzip it and copy `app-debug.apk` to your tablet. Open it in Files and allow that file manager to install apps when Android prompts you.

Version 0.3.2 uses version code 5 and the existing debug signing key, so it can update the prior app while retaining the library. `gradle/debug.keystore` is a deliberately public debug-only key with the conventional `android` password; never use it for production releases. Workflow APK artifacts are retained for 30 days.

Android 8.0+ (API 26) and an enabled, updated System WebView are required. Physical Meswao B3 Basic performance and website compatibility have not been verified. Third-party scripts may use more resources than native tabs; use the external browser when necessary.

## Build and validation

With JDK 17 and Android SDK 35 installed, set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Use `gradlew.bat` on Windows. The APK is `app/build/outputs/apk/debug/app-debug.apk`. The existing pinned GitHub Actions workflow runs tests, lint, debug APK compilation, and Android 8 / Android 15 tablet emulator checks for pushes to main and pull requests.

JVM tests cover approved provider search/tab URLs, encoding, source switching, unsafe URLs, fallback/error states, GP7/text imports, old saved-library compatibility, favorites, practice, and voice commands. Device tests exercise the real search form and ViewModel through both providers' WebView search-to-tab navigation using clearly labeled, test-only website fixtures; they also retain browser lifecycle/recovery and native viewer regression coverage. Production never uses fixture interception.

Live website probes are separate opt-in checks: manually dispatch **Android APK** with **live_websites** enabled on API 35+. The stock API 26 WebView stalled on live pages, and repeated Ultimate Guitar probes disconnected the API 35 emulator. Deterministic tests do not establish universal real-site compatibility. See [validation limits](docs/WEB_BROWSER.md).
