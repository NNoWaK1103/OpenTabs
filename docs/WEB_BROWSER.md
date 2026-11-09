# Embedded tab browsing: OpenTabs 0.5

The provider-only app includes **Save link** and **Saved links** on the website toolbar, with saved links also reachable from Search. These preserve only stable tab-page URLs and are explicitly labeled **Internet required**. Query strings/fragments are stripped, so provider instrument selection may reset when reopening. Saved links do not contain downloaded tabs or audio. They use a bounded, cross-instance-serialized, atomic private store; corrupt stores are preserved. Authentication/search/account pages cannot be bookmarked.


Songsterr and Ultimate Guitar are selectable website sources inside the existing Compose app. This is ordinary website browsing in Android's installed System WebView. It does not add an unofficial tab API, copy proprietary tab bodies into the local library, or change the websites' content or entitlements.

## Use

1. Enter a song, artist, or both in the single Search field.
2. Choose **Songsterr** or **Ultimate Guitar**. With no query, the provider's home page opens. With a query, its original search page opens.
3. The website renders its own results inside OpenTabs, with a source label and current HTTPS address. Select a website result to open its tab.
4. Use Back, Forward, Refresh/Stop, Zoom, Full screen, Screen awake, and Open in browser. Source switching repeats the original query on the other website.
5. **Search** returns to the two-source landing page. Android Back first exits full-screen mode, then navigates website history, then returns to Search. No native catalogs, sample exercises or unrelated lists are exposed.

The public search routes used are `https://www.songsterr.com/?pattern=...` and `https://www.ultimate-guitar.com/search.php?search_type=title&value=...`. Search terms are URL-encoded as query values. Neither route is an internal content-download API. Website result lists remain website UI rather than scraped native cards.

## Website restrictions and fallback

Content, layout, advertisements, cookie consent, sign-in, subscriptions, and playback features are controlled by the respective website. No JavaScript or CSS is injected, no WebView User-Agent is disguised, and no request interception, ad filtering, entitlement modification, or proprietary content extraction is performed in production. Website audio requires a user gesture. Normal first-party and third-party website cookies and DOM storage are enabled for compatibility with site content and advertising.

A site's paid feature remains paid. Authentication and external identity providers may disallow embedded browsers. HTTPS links outside the explicitly supported provider hosts require an explicit external-browser action. App links and unsafe schemes are not executed. Actual provider-offered file downloads have a separate guarded save flow, described in [provider saving](PROVIDER_SAVING.md). Unsupported flows keep the original page visible and offer an explicit browser fallback. No file is imported into a native tab list.

HTTP errors, including 401/403/429 restrictions, show a recovery banner while preserving the delivered page so users can interact with ordinary sign-in or access-control screens. Network and TLS failures show recovery UI with Retry and Open in browser. SSL errors are canceled, never ignored. A slow-page notice appears after 30 seconds; the user may continue waiting, stop loading, or open externally. A JavaScript-only access-denied page can arrive with HTTP 200, so automatic block detection is not promised; **Open in browser is always available**.

If Android System WebView is missing or disabled, OpenTabs shows an unavailable state and external-browser fallback. If no external browser is installed, a clear message is shown. A renderer-process failure releases the broken view and offers explicit recreation/retry. No successful website access or paywall removal is fabricated.

## Lifecycle and low-end tablets

Only one displayed WebView is created, lazily, inside Compose `AndroidView.factory`. The Search screen does not start a hidden browser. Progress and website history state are collected within the browser screen, not published through the whole application state on every progress event. `AndroidView.update` only loads a URL when its explicit request revision changes, preventing reloads during ordinary recomposition.

The WebView pauses when the app stops and resumes when it starts. Leaving the viewer stops loading, detaches clients, removes the view from its parent, and destroys it through `AndroidView.onRelease`. A bounded navigation Bundle (at most 30 history entries / 200 KiB) may be retained in the ViewModel across rotation; it contains no Activity or View reference. If the bound is exceeded, only the last safe provider URL is retained. Process death can return to Search; full website session history is not promised across process death.

Screen-awake uses a dedicated persisted provider-viewer preference and Android's view flag while viewing tabs; no wake lock or foreground service is added. Full-screen mode hides system bars while preserving the compact browser controls. Pinch/button zoom uses WebView's native zoom APIs, subject to the page's limits; the app does not rewrite page viewports or website controls to defeat restrictions.

Website scripts and ads can consume substantial resources. OpenTabs cannot make a third-party interactive player lightweight or provide its offline player through browser caching. No assumptions are made about the Meswao tablet's exact Android version, RAM, or processor. Android 8.0+ and an enabled, updated System WebView are required for embedded browsing.

## Security

- Only exact known Songsterr / Ultimate Guitar top-level HTTPS hosts render internally. Host suffix tricks, credentials in URLs, cleartext URLs, nonstandard ports, malformed authorities, and file/content/javascript/intent/data URLs are rejected.
- External HTTPS links require a user action and an Android browsable VIEW intent; intent strings are never parsed or forwarded.
- File/content access, file-origin universal access, mixed content, and automatic JavaScript popups are disabled. There is no JavaScript interface exposing app storage or native functionality.
- Platform Safe Browsing remains enabled. Certificate failures are never bypassed. Camera, microphone, and geolocation requests from websites are denied. No native voice-command interface is exposed.
- Normal page resources, third-party frames, ads, and site scripts are delivered by WebView. Only top-level navigation is constrained; no provider content is harvested through `shouldInterceptRequest` in production.
- System WebView owns its normal browser cache and cookies. The old native library/cache files are preserved but are not used by the provider-only launch path. The app does not export website pages as offline native tabs or represent its browser cache as redistribution permission.

## Validation

JVM tests cover URL construction, title/artist encoding, source switching, trusted-host validation, unsafe schemes, external fallback, and error/restriction states. Instrumentation tests use the real System WebView with clearly named test-only HTTPS fixtures to exercise page links, Back/Forward/Refresh, switching sources, error banners, renderer recovery, and view destruction. Test interception is never passed to the production controller. The active app tests verify the provider-only surface, saved-link access, rotation and preservation of existing private files without creating sample tabs. Legacy core tests remain for data compatibility, not as visible app features.

Separate real-website probes load both public search pages with the genuine WebView configuration. They record the returned title/address and any HTTP/network restriction, and verify that an explicit external fallback is present. A blocked third-party page is an expected website outcome, not reported as a successful tablature load. Optional live-page screenshots are included with device reports for visual inspection. Probe success validates navigation/fallback handling, not paid-feature access or universal website compatibility.

Live probes are opt-in: manually dispatch **Android APK** with **live_websites** enabled, or pass `-Pandroid.testInstrumentationRunnerArguments.liveWebsites=true` to connected tests on API 35+. Mandatory push checks run the provider-surface tests and deterministic real-WebView navigation/recovery fixtures on both API 26 and 35. The stock API 26 emulator stalled on modern live pages; the API 35 emulator also disconnected during repeated Ultimate Guitar probes. Those outcomes do not establish successful access to Ultimate Guitar tabs. Live network/renderer probes therefore remain separate from deterministic release checks, with bounded execution and diagnostic reports. Production browsing is not OS-gated beyond Android 8.0; updated WebView versions can behave differently. Physical Meswao performance and live tab compatibility remain to be checked; keep System WebView updated or use the external browser when needed.

References:

- [Compose AndroidView interoperability and release callbacks](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)
- [Android guidance on unsafe URI loading](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading)
- [WebView lifecycle and history APIs](https://developer.android.com/reference/android/webkit/WebView)
- [WebViewClient errors and renderer lifecycle](https://developer.android.com/reference/android/webkit/WebViewClient)
- [WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)
- [Songsterr public search](https://www.songsterr.com/?pattern=Nutshell)
- [Ultimate Guitar](https://www.ultimate-guitar.com/)
