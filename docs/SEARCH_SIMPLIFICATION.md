# History audit and direct tab search

Git history was inspected before editing application code, including the remote main branch and changes to `SearchScreen`, `WebTabs`, and `WebTabController`.

- Latest main implementation using both Songsterr and Ultimate Guitar: [`8561e27`](https://github.com/NNoWaK1103/OpenTabs/commit/8561e2724c6d6ad6d41dab5ba97cfd1471590891).
- Original secure embedded browsing integration: [`89e2463`](https://github.com/NNoWaK1103/OpenTabs/commit/89e2463).
- Browser history and recovery fixes retained: [`fc5da5e`](https://github.com/NNoWaK1103/OpenTabs/commit/fc5da5e).

`WebTabSource`, `WebTabRequest`, URL validation, `WebTabController`, and `WebTabScreen` are reused without changes. This retains source switching, site search routes, original website results, supported tab links, navigation, zoom, full screen, screen-awake controls, lifecycle management, error states, and external fallback. No alternate provider adapter or discovery service was introduced.

The incremental change is the entry workflow: Search now collects title, artist, and one of the two sources; submitting opens that source's existing search URL. Editing fields does not perform HTTP requests. Voice search fills this same form. My Tabs remains the entry to saved native six-line content.

Reference inspection confirmed that the old merged discovery engine, MusicBrainz adapter, additional catalog adapter, download path, and HTTP/cache implementation were used only by the removed discovery workflow and its tests. They and their OkHttp/MockWebServer dependencies were removed. JSON serialization and coroutines remain required by library persistence and application lifecycle operations. The GP7 decoder's bounded-input helper was retained separately. `Provenance` and `ArrangementTag` retain their existing serialized fields so saved v0.2/v0.3 tabs and imported GP7 tracks remain compatible.

The existing Compose shell, native viewer, GP7/text importers, settings, favorites/practice storage, microphone intent, voice parser, and Spotify/recognition boundaries remain. Tests cover both provider search-to-tab flows with real WebView and test-only fixture responses, form/voice navigation, restricted responses and external fallback, native opening, and legacy attributed library files. Live website access remains dependent on the providers and installed WebView; fixture tests are not claims of access to paid or blocked content.
