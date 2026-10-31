# OpenTabs 0.2

A native Kotlin / Jetpack Compose guitar tablature companion. Built for landscape tablet practice, with a compact portrait layout. No WebView, advertising, accounts, or paid service is needed for the core app. Version 0.2 adds unified local/online search and authorized native guitar-position previews while preserving existing libraries and viewer settings.

## Online search

Search by **Title or keywords** and optionally **Artist**. Results combine saved tabs, MusicBrainz song metadata, and TabForge's small original CC0 guitar catalog. Exact matches rank first; available instrument/role filters and **Native tabs only** help narrow results. Toggle **Search online** off for local results without network requests. Search is canceled when you leave the screen.

**Try `Ashen Meridian`** to retrieve the author's CC0 demo. Select **Details → Save & open** on Lead Gtr, Lead Harmony, or Clean Gtr to display the authored positions in the existing native viewer and save an attributed offline copy. The preview shows written measures, not interpreted repeats, techniques, rhythmic spacing, or playback. Different string counts are clearly marked unsupported.

**MusicBrainz results are metadata only: they do not contain tablature.** Online discovery is worldwide, but native online tab coverage is currently one CC0 demo composition with multiple arrangements. OpenTabs does not promise a tab for every commercial song. Songsterr and Ultimate Guitar are not connected; no protected pages or private APIs are scraped.

See [provider research, licensing, cache permissions, and native format limits](docs/PROVIDERS.md).

## Install on your tablet

1. Open [Android APK builds](https://github.com/NNoWaK1103/OpenTabs/actions/workflows/android-build.yml).
2. Select a successful run for `main` and download **OpenTabs-debug-apk** under Artifacts. GitHub requires you to sign in to download workflow artifacts.
3. Unzip the download and copy `app-debug.apk` to the tablet.
4. On the tablet, open the APK in Files and allow that file manager to install unknown apps when Android prompts you. Install **OpenTabs**.
5. Open **My Tabs** for three original exercises, or tap **Import** to select your own `.txt` / `.tab` file.

The build output is `app/build/outputs/apk/debug/app-debug.apk`. The workflow artifact is retained for 30 days; run the workflow again if it expires. This is a debug build for personal testing, not a Play Store release.

`gradle/debug.keystore` is a deliberately public Android **debug-only** key, using the conventional `android` password. It keeps test APK signatures consistent across CI runs so subsequent builds can update the installed app without clearing your library. Never use this public key for a production release; there are no production signing credentials in the repository.

**Device compatibility:** Android 8.0 (API 26) or newer; compile and target API 35. The Meswao B3 Basic's Android version, processor, RAM, and screen density have not been verified. Check **Android Settings → About tablet** for Android 8.0+ before installation. No specific device hardware specification or physical-device test is assumed. The APK has no app-specific CPU architecture restriction; Compose's optional native graphics helper supplies the standard Android ABIs.

## What works

- Five native destinations: Now Playing, Search, My Tabs, Practice, Settings.
- A dark, high-contrast interface; navigation rail on wide screens, bottom navigation on compact screens. Rotation remains enabled.
- Native six-string text tab display with monospace alignment, horizontal and vertical scrolling, 12–32 sp fonts, adjustable line spacing, full-screen mode, and keep-screen-awake while viewing.
- Adjustable auto-scroll (5–120 density-independent pixels/second), pause/resume, manual scrolling, and return to top. Scrolling pauses on leaving the viewer or backgrounding the app. **The play button scrolls text; it does not play audio.**
- Android Storage Access Framework import. A validated copy is stored privately, so the library works even if the original is moved or its URI permission expires.
- Persistent title, artist, album, tuning, favorites, recents, practice flags, current tab, and viewer settings. Use **Edit details** to enter metadata; the app does not guess an artist from a filename.
- Offline library searches across title, artist, album, and tuning. Unified Search adds local/online title and artist ranking, fuzzy matches, source attribution, arrangement metadata, and supported filters.
- Favorites, recent tabs, practice list, and deletion of imported copies. Deletion preserves your original source file. Original built-in exercises cannot be deleted.
- Optional Android voice commands, when an installed speech recognition service supports them.

Tap the microphone button, then **Start voice command**. Supported commands include:

```text
Find tabs for [title] by [artist].
Open my favorites.
Show my practice list.
Start scrolling.
Stop scrolling.
Increase tab size.
Decrease tab size.
```

Speech is delegated to Android's `ACTION_RECOGNIZE_SPEECH` activity. That service handles microphone access and may send audio to its own servers; availability, permissions, language support, and offline recognition depend on the installed service. OpenTabs does not request microphone permission on launch, capture microphone audio, or run a background recognizer. Manual operation works without a speech service.

## Supported import format

Plain-text `.txt` and `.tab` files, encoded as UTF-8 (optional BOM) or UTF-16 LE/BE with a BOM. Supported ZIP-based GP7 `.gp` files can also be imported; select a six-string arrangement with authored frets in the chooser. GP3–GP5, GPX, and MusicXML imports remain unavailable. At least one group of six consecutive string lines is required in text files, for example:

```text
My original exercise
e|------0---------|
B|----0---0-------|
G|--0-------0-----|
D|2-----------2---|
A|----------------|
E|0---------------|
```

Common ASCII tab techniques (`h`, `p`, `/`, `\`, `b`, `r`, `x`, `~`) are displayed as text. Headers and practice notes are preserved. Tabs expand to four-column stops; line endings are normalized. Limits are **512 KiB** before and after normalization, **6,000 lines**, and **1,000 columns per line**. Unsupported encodings, binary/control data, and files without six-string text notation produce a descriptive error without adding a library entry. `.tab` means plain text, not an arbitrary binary tab format. Copy and import only material you are authorized to use.

## Build without Android Studio

GitHub Actions builds automatically on pushes to `main`, on pull requests, and manually with **Run workflow**. No signing secrets or external-service credentials are needed. The workflow installs JDK 17 and Android SDK 35, validates the Gradle wrapper, caches Gradle, runs core tests and Android lint, compiles the app and instrumentation tests, and uploads the debug APK and reports. Action versions are pinned to verified immutable commits; wrapper downloads have a pinned SHA-256.

After the build job, two tablet emulator jobs run the Compose smoke test on API 26 and API 35, covering the minimum and target Android versions. Their reports are uploaded separately. The APK artifact is uploaded by the build job before device tests run.

For a local build, install JDK 17 and the Android command-line SDK. Set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `gradlew.bat`. No separately installed Gradle is required. The pinned build stack is AGP 8.9.2, Gradle 8.11.1, Kotlin / Compose compiler 2.1.20, Compose BOM 2025.04.01, and Material 3. These are deliberately compatible stable versions, rather than floating dependency versions.

To run the included Compose smoke test on a connected Android device or running emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Tablet smoke tests search offline exercises and save/open a licensed arrangement using deterministic provider fixtures and the real native parser. JVM tests cover provider concurrency, cancellation, deadlines, deduplication, fuzzy ranking, HTTP validation, offline cache fallback, content rights, real GP7 parsing, legacy storage, favorites, practice, text import, settings, and voice commands. Optional live checks run with `./gradlew :core:test -PliveProviders=true`. `:app:testDebugUnitTest` is included for future Android-specific unit tests; current unit tests live in `core`.

## Architecture and data

`core/` is a pure Kotlin JVM module containing domain models, the tested repository, search, decoders, voice parsing, original exercises, and provider interfaces. `app/` contains the Android lifecycle ViewModel, SAF import, delegated speech activity, and native Compose screens. See [architecture and integration setup](docs/ARCHITECTURE.md).

The app stores a small JSON metadata index and separate UTF-8 tab files in app-private storage. Writes use a serialized coroutine mutex, a synced temporary file, and atomic replacement. I/O runs on `Dispatchers.IO`. A corrupt index is reported and preserved rather than overwritten. Tab bodies are loaded only for the selected tab; a lazy list composes visible lines. Online search uses the internet permission only for user-driven requests to the documented metadata API and the reviewed CC0 source. The authorized response cache is bounded to 2 MiB. No periodic work, foreground service, account, or local AI model is included.

Data remains on this device. Android cloud backup and device transfer are disabled. Uninstalling or clearing app data removes imported copies, favorites, and settings; retain your original files. Auto-scroll's running state and full-screen mode are intentionally session-only; font, line spacing, speed, and keep-awake preferences persist.

## Deliberately unavailable in 0.2

- **Spotify:** an explicit unconfigured provider. No account connection or live track monitoring is claimed.
- **Music identification:** an explicit unavailable provider. No Shazam recognition or fabricated recognition results.
- **Songsterr / Ultimate Guitar native tabs:** require documented authorized access; no scraping or invented endpoints. MusicBrainz song discovery supplies no tabs.
- **Full Guitar Pro engraving / playback, GP3–GP5 / GPX, MusicXML, tab synthesis, playback synchronization, and automated transcription:** not implemented. The supported GP7 path is a limited native authored-position preview.

The bundled exercises are original OpenTabs practice material; no commercial song transcription is bundled. A song title used in a voice parser test does not include its tablature or audio.

Next milestone: validate the update on the actual Meswao tablet, add backup/export, and expand the native catalog only through explicitly licensed sources or documented provider agreements. Broad commercial-song tablature requires licensing beyond metadata access.
