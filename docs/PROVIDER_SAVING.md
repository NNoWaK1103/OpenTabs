# Saving Songsterr and Ultimate Guitar tabs

Reviewed 2026-11-08. OpenTabs 0.5 exposes only these two providers. Saving a link, downloading a file offered by a provider, and using a provider's offline player are different features.

## Save link

**Save link** bookmarks a recognized Songsterr or Ultimate Guitar tab page. **Saved links** opens the original page inside OpenTabs. Internet is required; no tab notation, audio, account data or offline player is included in the bookmark. Search/sign-in/account pages cannot be saved, and query strings/fragments are removed from saved URLs.

## Save a file offered by the provider

Songsterr's official [Plus page](https://www.songsterr.com/plus) advertises Guitar Pro, MIDI, MP3 and WAV downloads, along with printing. Its [terms](https://www.songsterr.com/terms) identify these as paid features. Sign in and use the provider's own export control when your account has access; OpenTabs does not unlock it or construct a private download URL.

Ultimate Guitar's official [Android listing](https://play.google.com/store/apps/details?id=com.ultimateguitar.tabs) lists print/export under Pro. This is not evidence that every website tab type has an equivalent downloadable file. The exact live website export paths and entitlements remain unverified.

When the website offers a supported first-party HTTPS file download, OpenTabs asks before saving and lets you choose a destination with Android's file picker. It checks the download and stages it privately before writing the chosen file. Keep the page open until saving completes. Leaving the page or rotating the device cancels the attempt; start the provider’s download again afterward. Failed writes can still leave an incomplete destination; the app reports the failure rather than claiming success. An abandoned file-picker result can leave an empty file, which you can remove from the chosen location. A stale result must never cause the app to delete an existing or completed file.

The handler is deliberately limited:

- Only genuine file offers delivered by the website are considered. No scraping, tab transcription, page capture, private content API or subscription bypass is used.
- Download URLs must use safe HTTPS on a supported provider domain. Redirects stay on the same origin; credentials are never forwarded to an unrelated host.
- Files are bounded to 64 MiB and checked against supported tab/audio document types and file signatures: PDF, MIDI, MP3, WAV, GP, GP3, GP4, GP5 and GPX. HTML login/error pages are not saved as tablature. GP archive validation reads bounded headers without extracting the archive; it rejects ZIP64, more than 512 entries or a central directory over 256 KiB.
- Blob/data URLs, unrelated download domains, unsupported file types and unsupported redirect flows remain unavailable. The original page stays visible and an explicit browser fallback remains available.
- Cookies and signed download URLs are not written into saved links, file metadata or logs. The app does not add broad storage permission.

These saved files are not an offline copy of the interactive provider website. This release does not add a native downloads library, file importer or offline playback screen. Use an appropriate player/viewer for the exported format.

Live paid exports have not been proven by deterministic fixtures. Provider implementation changes, sign-in requirements and WebView restrictions can still prevent a particular export. The browser's navigation allowlist can also block some download links before the file handler receives an offer; support is conditional on the provider's actual flow.

## Offline provider players

Songsterr's [help](https://www.songsterr.com/help) says its web player is online-only and directs users to its mobile apps for offline-player features. Its [Android listing](https://play.google.com/store/apps/details?id=com.songsterr) describes offline access to previously opened tabs. That is separate from Plus file exports.

Ultimate Guitar's [Android listing](https://play.google.com/store/apps/details?id=com.ultimateguitar.tabs) and [iOS listing](https://apps.apple.com/us/app/ultimate-guitar-chords-tabs/id357828853) list offline favorites separately from their Pro features. Do not assume every offline feature requires a subscription; the provider controls current entitlements.

OpenTabs does not silently send you to those apps or another browser. They are separate options if you need the provider's own offline player.

## Printing and integration limits

Android [WebView printing documentation](https://developer.android.com/training/printing/html-docs) says HTML JavaScript cannot directly trigger Android printing. A website's Print button therefore is not guaranteed to work inside OpenTabs. No generic capture button is presented as an authorized provider export.

Ordinary access does not establish a blanket license to extract or rehost the provider's catalog. See [Songsterr help](https://www.songsterr.com/help) and [Ultimate Guitar terms](https://www.ultimate-guitar.com/about/tos.htm). OpenTabs keeps the original pages, branding, access checks and genuine WebView identity; it does not claim an approved native content integration.
