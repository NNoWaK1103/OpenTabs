# Architecture and integration boundaries

Version 0.3 adds an optional Compose `AndroidView` browser screen for ordinary Songsterr and Ultimate Guitar website navigation. `WebTabRequest` / `WebTabUrls` define search routes and top-level navigation policy; the screen-owned `WebTabController` manages one WebView and its lifecycle. A bounded `WebTabMemory` retains navigation data, never a View or Activity. Existing native providers, repository, decoder, Spotify boundary, and voice commands are preserved. See [WEB_BROWSER.md](WEB_BROWSER.md).

Version 0.3.1 reuses the Songsterr / Ultimate Guitar browser from v0.3 and simplifies Search to direct tab discovery. The former metadata adapters, merged coordinator, and native online download/cache pipeline have been removed. The streaming GP7 import decoder and existing saved-tab attribution remain. See [sources and native format limits](PROVIDERS.md). Spotify and music-recognition boundaries below remain optional and unconfigured.

## Offline application

`LibraryRepository` exposes a `StateFlow<LibraryState>` for metadata and preferences. Every mutation executes on the I/O dispatcher under a mutex. `library.json` is replaced only after its temporary file has been flushed and synced. The new state is published after persistence succeeds. A tab body is written before its metadata is indexed; deletion removes metadata before cleaning up the body. A crash during either operation can leave an unreferenced file, not a new index entry pointing to unwritten content. Corrupt or unsupported metadata versions fail visibly without overwriting the user's index.

Tab bodies are separate files named by generated identifiers, not user-selected paths. Imported content is copied from SAF into private storage; persistent URI permissions are unnecessary. A decoder validates the extension, bounded byte stream, encoding, line count, width, and presence of six-string notation. All built-in exercises use the same decoder. There is no network permission in the first release.

The Android ViewModel separates screen state, persistent library state, transient notifications, and the current document. Metadata changes do not reload the current document. Compose collects state with lifecycle awareness. The tab view uses a vertical lazy list inside a horizontal scroll container. Monospace text measurement allocates the full document width without composing every line. Auto-scroll runs from the frame clock only when enabled, yields during touch scrolling, and stops at the end or on pause/navigation. Full-screen mode hides system bars and provides both an exit button and Android Back handling.

`PlainTextTabDecoder` validates text imports and the final native preview. `GuitarProPreview` now reads a bounded ZIP/GPIF score into typed track, bar, voice, beat, and authored string/fret records. It converts supported six-string arrangements into labeled text position previews for the same viewer. Full score engraving, repeat interpretation, expressive-technique rendering, and MusicXML parsing remain unimplemented; see the explicit limits in PROVIDERS.md.

## Direct tab website search

`SearchScreen` collects song title, artist, and a `WebTabSource`. Submitting delegates to the existing `OpenTabsViewModel.browse` / `WebTabRequest` path. Only Songsterr and Ultimate Guitar public search pages are connected. Their original result lists and supported tab pages render through the existing `WebTabController` / `WebTabScreen`; secure URLs, lifecycle, performance controls, and external fallback are retained. There is no background metadata lookup, merged metadata result list, or additional online catalog. My Tabs retains native local search. Existing serialized provenance and imported GP7 track tags are preserved separately from discovery code. See [the history audit](SEARCH_SIMPLIFICATION.md) and [WebView details](WEB_BROWSER.md).

## Tuner

`TunerScreen` / `TunerController` form a separate WebView and microphone boundary for guitar-tuner.org. `TunerMicrophoneGate` checks the exact origin, visible page, and Android runtime permission, grants audio only, and discards canceled or stale requests. Backgrounding/disposal destroys the tuner view to stop capture. Navigation suspends the previous tab request, restores its existing URL/history memory on return, and retains native saveable scroll state. Tab browser capture policies remain unchanged. See [TUNER.md](TUNER.md).

## Spotify

`NowPlayingProvider` exposes availability, connect/disconnect operations, and a `Flow<TrackInfo?>`. The shipped `UnconfiguredSpotifyProvider` emits no track and returns an explanatory unavailable state. `LocalSearch.matching` implements normalized title-and-artist matching and is tested independently.

A future adapter should:

1. Register a Spotify developer application and an exact supported redirect URI in Spotify's dashboard. Supply a public client ID and redirect URI through build configuration; never embed a client secret.
2. Implement the official [Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), including a random verifier, S256 challenge, checked OAuth state, token refresh, and securely stored tokens. A client ID alone does not enable the current stub.
3. Request only the required [currently-playing scope](https://developer.spotify.com/documentation/web-api/concepts/scopes), initially `user-read-currently-playing`.
4. Use the official [Get Currently Playing Track API](https://developer.spotify.com/documentation/web-api/reference/get-the-users-currently-playing-track). Handle 204/no playback, non-track content, expired authorization, 403/account restrictions, 429/backoff, and offline errors.
5. Observe only while the integration is enabled and the application is in use, detect track changes, search the local repository, and show a user-selectable list of matching arrangements. Clear previous track data on disconnect.
6. Recheck Spotify's current terms and development-mode restrictions before release. Do not synchronize tablature advancement with Spotify audio, redistribute audio, or imply licensed playback. The current auto-scroll is user-controlled and independent of audio.

No Spotify credentials, tokens, API calls, SDK downloads, polling, or connection button that claims success are included in 0.1. The architecture permits a supported adapter without blocking the offline APK.

## Music recognition

`MusicRecognitionProvider` declares availability, suspending one-shot recognition, and cancellation. Results distinguish a real match, no match, and unavailable configuration. `UnconfiguredRecognitionProvider` returns unavailable and never captures audio.

For [ShazamKit for Android](https://developer.apple.com/shazamkit/android/), obtain the supported SDK under its terms, configure an Apple developer media identifier and signing key, and run a backend developer-token service. Implement Apple's `DeveloperTokenProvider`; keep its private signing key on the server. Follow the official Android SDK's audio and matching interfaces. No SDK or signing credentials are bundled in this project.

A configured recognition adapter must request `RECORD_AUDIO` only after the user explicitly presses Identify, handle denial without breaking manual features, capture a bounded session, stop/release recording on cancellation or lifecycle stop, and return actual title/artist metadata. Pass real matches into local matching/search; do not automatically open an arbitrary arrangement. Other providers must meet equivalent consent, cancellation, credential, and result requirements. An unconfigured provider must not request microphone access or show a simulated listening state.

## Voice commands

Voice commands are separate from music identification. The app starts the official Android [`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`](https://developer.android.com/reference/android/speech/RecognizerIntent) activity only after a tap. Its host service owns microphone access. OpenTabs receives text through the Activity Result API and passes it to the deterministic `VoiceCommandParser`. Missing activities and microphone access errors produce actionable messages. No continuous listener, on-device model, custom recording, or microphone permission request at app startup is used.

The parser accepts only documented command patterns; arbitrary spoken text cannot trigger navigation or scrolling. Android service support and network requirements depend on the tablet. Local library search and all manual controls remain available without speech.
