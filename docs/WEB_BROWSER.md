# Embedded tab browsing — OpenTabs 0.3

Songsterr and Ultimate Guitar are selectable website sources inside the existing Compose app. This is ordinary website browsing in Android's installed System WebView. It does not add an unofficial tab API, copy proprietary tab bodies into the local library, or change the websites' content or entitlements.

## Use

1. In Search, enter a title and optionally an artist.
2. Native saved tabs and authorized CC0 arrangements remain the default results and open in the existing six-line viewer.
3. Under **Website results**, select **Songsterr** or **Ultimate Guitar**. The website renders its own search results inside OpenTabs, with a persistent source label and current HTTPS address. Select a result on that page to open its tab.
4. Use the browser toolbar for Back, Forward, Refresh/Stop, Zoom, Full screen, Screen awake, and Open in browser. Source switching repeats the same original title/artist search on the other website.
5. **Native tabs** returns to the existing search screen. Android Back first exits full-screen mode, then navigates website history, then returns to native Search.

The public search routes used are `https://www.songsterr.com/?pattern=...` and `https://www.ultimate-guitar.com/search.php?search_type=title&value=...`. Search terms are URL-encoded as query values. Neither route is an internal content-download API. Website result lists remain website UI rather than scraped native cards.

## Website restrictions and fallback

Content, layout, advertisements, cookie consent, sign-in, subscriptions, and playback features are controlled by the respective website. No JavaScript or CSS is injected, no WebView User-Agent is disguised, and no request interception, ad filtering, entitlement modification, or proprietary content extraction is performed in production. Website audio requires a user gesture. Normal first-party and third-party website cookies and DOM storage are enabled for compatibility with site content and advertising.

A site's paid feature remains paid. Authentication and external identity providers may disallow embedded browsers. HTTPS links outside the explicitly supported provider hosts require an explicit external-browser action. App links and unsafe schemes are not executed. Downloads are delegated to the user's browser through a visible action rather than imported or cached as licensed native tabs.

HTTP errors, including 401/403/429 restrictions, show a recovery banner while preserving the delivered page so users can interact with ordinary sign-in or access-control screens. Network and TLS failures show recovery UI with Retry and Open in browser. SSL errors are canceled, never ignored. A slow-page notice appears after 30 seconds; the user may continue waiting, stop loading, or open externally. A JavaScript-only access-denied page can arrive with HTTP 200, so automatic block detection is not promised; **Open in browser is always available**.

If Android System WebView is missing or disabled, OpenTabs shows an unavailable state and external-browser fallback. If no external browser is installed, a clear message is shown. A renderer-process failure releases the broken view and offers explicit recreation/retry. No successful website access or paywall removal is fabricated.

## Lifecycle and low-end tablets

Only one displayed WebView is created, lazily, inside Compose `AndroidView.factory`. Native screens do not start a hidden browser. Progress and website history state are collected within the browser screen, not published through the whole application state on every progress event. `AndroidView.update` only loads a URL when its explicit request revision changes, preventing reloads during ordinary recomposition.

The WebView pauses when the app stops and resumes when it starts. Leaving the viewer stops loading, detaches clients, removes the view from its parent, and destroys it through `AndroidView.onRelease`. A bounded navigation Bundle (at most 30 history entries / 200 KiB) may be retained in the ViewModel across rotation; it contains no Activity or View reference. If the bound is exceeded, only the last safe provider URL is retained. Process death can return to native app navigation; full website session history is not promised across process death.

Screen-awake uses the existing persisted viewer preference and Android's view flag while viewing tabs; no wake lock or foreground service is added. Landscape full-screen mode hides app navigation and system bars while preserving the compact browser controls. Pinch/button zoom uses WebView's native zoom APIs, subject to the page's limits; the app does not rewrite page viewports or website controls to defeat restrictions.

Website scripts and ads can consume substantially more resources than native text tabs. OpenTabs cannot make a third-party interactive player as lightweight as its own six-line viewer. The native viewer remains preferred for local or explicitly licensed content. No assumptions are made about the Meswao tablet's exact Android version, RAM, or processor. Android 8.0+ and an enabled, updated System WebView are required for embedded browsing.

## Security

- Only exact known Songsterr / Ultimate Guitar top-level HTTPS hosts render internally. Host suffix tricks, credentials in URLs, cleartext URLs, nonstandard ports, malformed authorities, and file/content/javascript/intent/data URLs are rejected.
- External HTTPS links require a user action and an Android browsable VIEW intent; intent strings are never parsed or forwarded.
- File/content access, file-origin universal access, mixed content, and automatic JavaScript popups are disabled. There is no JavaScript interface exposing app storage or native functionality.
- Platform Safe Browsing remains enabled. Certificate failures are never bypassed. Camera, microphone, and geolocation requests from websites are denied; the native optional voice-command activity remains separate.
- Normal page resources, third-party frames, ads, and site scripts are delivered by WebView. Only top-level navigation is constrained; no provider content is harvested through `shouldInterceptRequest` in production.
- System WebView owns its normal browser cache and cookies. These are separate from the existing licensed native-response cache and saved tab library. The app does not export website pages as offline native tabs or represent its browser cache as redistribution permission.

## Validation

JVM tests cover URL construction, title/artist encoding, source switching, trusted-host validation, unsafe schemes, external fallback, and error/restriction states. Instrumentation tests use the real System WebView with clearly named test-only HTTPS fixtures to exercise page links, Back/Forward/Refresh, switching sources, error banners, renderer recovery, and view destruction. Test interception is never passed to the production controller. Existing native search and licensed-arrangement smoke tests remain in the API 26 / API 35 matrix.

Separate real-website probes load both public search pages with the genuine WebView configuration. They record the returned title/address and any HTTP/network restriction, and verify that an explicit external fallback is present. A blocked third-party page is an expected website outcome, not reported as a successful tablature load. Optional live-page screenshots are included with device reports for visual inspection. Probe success validates navigation/fallback handling, not paid-feature access or universal website compatibility.

The live probes run on the API 35 emulator; the stock API 26 emulator's old WebView stalled on modern live pages. API 26 continues to run the native regression tests and deterministic real-WebView navigation/recovery fixtures. This does not restrict production browsing by Android version: an Android 8.0 device with an updated WebView may behave differently from that old emulator. Physical Meswao performance and site compatibility remain to be checked; keep System WebView updated or use the external browser when needed.

References:

- [Compose AndroidView interoperability and release callbacks](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)
- [Android guidance on unsafe URI loading](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading)
- [WebView lifecycle and history APIs](https://developer.android.com/reference/android/webkit/WebView)
- [WebViewClient errors and renderer lifecycle](https://developer.android.com/reference/android/webkit/WebViewClient)
- [WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)
- [Songsterr public search](https://www.songsterr.com/?pattern=Nutshell)
- [Ultimate Guitar](https://www.ultimate-guitar.com/)
