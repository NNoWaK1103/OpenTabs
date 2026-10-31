# Embedded guitar tuner

Select **Tuner** in the existing sidebar or compact bottom navigation. `TunerScreen` opens `https://guitar-tuner.org/` using Compose `AndroidView` and a native tuning-fork icon. The site's acoustic/electric tuner pages stay embedded on the same trusted origin, in landscape or portrait. Normal operation does not launch an external browser.

## Microphone

Tap the website's microphone switch. If OpenTabs does not already have Android microphone permission, an explanation names the requesting site, followed by Android's standard `RECORD_AUDIO` dialog. `WebChromeClient.onPermissionRequest` grants only `RESOURCE_AUDIO_CAPTURE` after checking both the actual Android grant and the exact trusted origin. No permission is requested at startup. After denial, users may enable microphone access in Android Settings and reload; native tabs and search remain usable without it.

Only `https://guitar-tuner.org` on a visible trusted tuner page qualifies. HTTP, subdomains, lookalike hosts, arbitrary ports, credentials in URLs, other origins, camera, geolocation, MIDI, and unknown resources are not granted. Mixed requests receive audio only, never a blanket grant of `request.resources`. Tab-provider WebViews still deny website capture even when Android has permitted the tuner microphone.

Pending requests are denied on navigation, backgrounding, or disposal. Canceled requests receive no further response. A delayed Android result cannot grant a replacement request or document. The existing voice-command activity remains separate from website microphone access.

## Lifecycle and tab preservation

Only the displayed tuner WebView is created. Leaving Tuner, rotating, or backgrounding destroys it to stop WebRTC microphone capture; `onPause` alone is not treated as a capture-stop guarantee. Returning recreates the last trusted tuner URL and may require tapping the website microphone switch again. The ViewModel retains no Activity, WebView, pending permission, recording, or microphone grant. OpenTabs adds no recorder, foreground microphone service, audio files, or native pitch detector.

Entering Tuner suspends the current online tab request. **Back to tab** or selecting its original destination restores the existing bounded tab URL/history memory. Full website JavaScript state is not retained, and a page may reload. Native tabs retain the current document and saveable scroll position. An explicit new voice search starts the new search instead of restoring a stale tab. Existing imports, favorites/practice, six-line rendering, search, and Spotify/recognition boundaries remain unchanged.

## Website behavior and errors

JavaScript and DOM storage are enabled; file/content access and mixed content are disabled. The genuine WebView User-Agent, normal resources, cookies, ads, and site controls remain unchanged. Production does not inject scripts, intercept responses, or bypass restrictions. Reference audio requires a user gesture. Audio access is provided to the website under its own privacy policy; no claim is made that third-party website code cannot communicate over the network.

Loading progress and a slow-page notice are shown. Connection/TLS/renderer failures stop the view and offer Retry; certificate errors are never ignored. HTTP restriction pages are preserved with a notice. An external browser is offered only through a deliberate action for errors or links leaving the trusted site. Missing WebView or browser handlers produce recovery messages.

## Validation limits

JVM tests cover origin validation, Android grant checks, audio-only grants, denial, cancellations, backgrounding, and delayed permission races. Device tests use real WebView with labeled test-only pages for the permission bridge, Android's actual consent flow, background destruction, retry, sidebar navigation, native scroll restoration, and suspended online-tab requests. Fixtures do not establish successful live audio tuning or accurate pitch detection. Keep System WebView updated; live site behavior, microphone quality, and Meswao B3 Basic tuning accuracy still need a physical-device check.

References: [tuner website](https://guitar-tuner.org/), [PermissionRequest](https://developer.android.com/reference/android/webkit/PermissionRequest), [runtime permission requests](https://developer.android.com/training/permissions/requesting).
