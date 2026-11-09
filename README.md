# OpenTabs 0.5

A focused Android tablet browser for **Songsterr and Ultimate Guitar only**. Search a song or artist, choose either provider, and use its original page inside OpenTabs.

## What changed

The app no longer shows sample exercises, native tab catalogs, My Tabs, practice lists, MusicBrainz results, TabForge demos, import controls, or placeholder music integrations. It does not create a sample library on launch. Existing private imported files are left untouched, but they are not part of the visible provider-only experience.

Both websites retain their own sign-in, subscriptions, ads, player, licensing and access rules. No private content API, extraction, paywall bypass or fabricated tab result is used. If a provider refuses embedded browsing, the app explains it and offers an explicit external-browser choice. It never switches browsers automatically.

## Finding and saving tabs

- Enter a song, artist, or both, then choose **Songsterr** or **Ultimate Guitar**.
- Use website Back/Forward, Refresh/Stop, Zoom, Full screen and Screen awake.
- **Save link** stores the current recognized provider tab page. **Saved links** is also available from the search screen. These links need internet and are not downloaded tab copies.
- Actual offline files depend on the provider's export controls and your access. See [saving provider tabs](docs/PROVIDER_SAVING.md) for supported file offers, subscriptions and limitations.

OpenTabs does not promise an offline copy of either interactive web player. Generic WebView caching is not a reliable offline tab library.

## Install the preview

Use the APK attached with the release validation record. Install it **over** the existing OpenTabs app to retain its private data and saved links. Do not uninstall or clear app data if you want to keep them. If Android reports a signature mismatch, leave the old app installed and report the error.

Android 8.0/API 26 or newer is required; target API is 35. The existing deliberately public debug-only signing key is retained so compatible preview builds can update in place. This is a personal-testing debug APK, not a Play Store production release. Never use the public debug key for production.

GitHub's [Android APK workflow](https://github.com/NNoWaK1103/OpenTabs/actions/workflows/android-build.yml) builds published source. An unmerged local preview is not automatically available from a main-branch workflow run. Check the release's exact commit and validation record.

## Privacy and safety

- The browser uses its genuine WebView User-Agent and preserves provider content and branding.
- HTTPS certificate failures are never ignored. Unsafe schemes and automatic external navigation are blocked.
- No microphone, location or file-upload permission is requested by this provider-only surface.
- Saved links contain canonical tab-page URLs, with query strings and fragments removed. Sign-in, account and search pages are not bookmarked.
- Saved links and any retained legacy files stay in app-private storage. Android cloud backup remains disabled. Uninstalling or clearing app data removes them.

See [browser behavior](docs/WEB_BROWSER.md) and [provider saving](docs/PROVIDER_SAVING.md).

## Build and validation

JDK 17, Android SDK/build tools 35 and the pinned Gradle wrapper are required:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. With an Android device or emulator, run `./gradlew :app:connectedDebugAndroidTest`.

Deterministic tests validate navigation, saved links, recovery, privacy boundaries and the provider-only surface. They do not establish that a live provider will accept an embedded browser, that a paid export succeeds, or that a particular tablet has been tested. The release validation record distinguishes executed checks, failures and untested paths.

The old Kotlin native-library implementation and its core regression tests remain in source to preserve existing data compatibility. Historical native-practice/provider documents describe earlier versions, not features exposed by the 0.5 app.
