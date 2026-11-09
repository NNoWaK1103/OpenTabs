> Historical note: this document describes the earlier native-library prototype. OpenTabs 0.5 exposes only Songsterr and Ultimate Guitar; native samples, catalogs, lists and import controls are no longer visible. Existing private files are preserved. See [current app behavior](../README.md) and [provider saving](PROVIDER_SAVING.md).

# Live music companion roadmap

Updated 2026-11-08. This is a feasibility roadmap, not a claim that the listed integrations already work.

## Foundation: reliable practice, then stage use

1. Verify the local six-line viewer and audio on the actual tablet, including rotation, interruptions and internet loss.
2. Add library backup/export, ordered setlists, per-song notes and easy song changes. Keep on-stage controls large and predictable.
3. Expand notation support and practice controls, including tested A–B loops and optional authored playback-position highlighting. Do not infer precise timing from arbitrary text tabs.
4. Add recognition and chord assistance as optional tools after the core stays dependable offline.
5. Add a singer-oriented lyrics view much later, using authorized content.

## Provider websites and licensed offline content

Songsterr's official help says its web service is online-only and its licensing does not grant permission for API/embedded-player republication. Its own mobile apps offer offline features: https://www.songsterr.com/help

Ultimate Guitar's terms reserve content copying/storage/display rights. The linking/widget provisions prohibit framing by linking websites. No supported public tab-content API or affirmative license for a third-party native wrapper was verified: https://www.ultimate-guitar.com/about/tos.htm

OpenTabs preserves the existing provider-hosted browser, provider branding and access controls. It does not extract, rehost or force-cache provider tab bodies/audio. Compatibility and provider approval for a dedicated wrapper remain unverified. Original pages may require sign-in/subscription or reject an embedded browser. The explicit external-browser fallback remains available; there is no automatic handoff.

Android recommends Custom Tabs for third-party sites and credentials: https://developer.android.com/develop/ui/views/layout/webapps

The product currently honors the user's preference for staying in OpenTabs where the original site works. Any later switch to Custom Tabs or provider agreement should be an explicit product decision, not an invisible workaround for provider restrictions.

## Recognize a recording: ShazamKit first

Apple provides a ShazamKit Android SDK with Kotlin APIs and minSdk 21. Public-catalog recognition requires internet and a developer token. Local custom catalogs are possible, but are not an offline copy of the public Shazam catalog: https://developer.apple.com/shazamkit/android/

A sensible first feature is a user-started **Identify recording** action, displaying a candidate title/artist before searching. Recognition of a released recording is different from transcribing live guitar or identifying arbitrary covers. Developer-account/token setup and an off-device signing-key design remain required. Do not ship secret keys in the APK.

## Spotify: metadata is not an audio-analysis license

Spotify has currently-playing metadata APIs, but its developer policy restricts analysis of Spotify content, ML/AI ingestion and visual synchronization. Do not use Spotify streams as input for chord extraction or promise synchronized tablature without an applicable agreement: https://developer.spotify.com/policy

Development-mode access is limited, including a Premium app owner and five allowlisted users; broader access has additional eligibility requirements: https://developer.spotify.com/documentation/web-api/concepts/quota-modes

A metadata-only account integration needs a separate policy and product review. No Spotify account is connected in this release.

## Detect live chords: local, explicit, uncertainty-aware

Start with clean isolated guitar and a small chord vocabulary. Request microphone access only when the user starts listening; show a persistent listening state and a clear Stop control. Do not retain recordings by default. Display confidence and an **uncertain** result rather than falsely certain chord names. Full-band songs, distortion, reverb, bass dominance and inversions require a separate evaluation set.

Spotify's Apache-2.0 Basic Pitch is a possible research baseline for authorized local audio and works best with one instrument at a time. It is not a ready-made Android chord detector or permission to analyze Spotify streams: https://github.com/spotify/basic-pitch

Essentia has chord-estimation algorithms, but code and model licensing must be reviewed before adoption: https://essentia.upf.edu/tutorial_tonal_chords.html and https://essentia.upf.edu/licensing_information.html

## Singer view: authorized lyrics

Begin with user-authored/licensed imported lyrics if this milestone is requested. A commercial supplier is a later option. Full lyrics, offline storage, synchronization and distribution each need appropriate rights; a song-metadata API does not grant them. No lyric catalog, lyric scraping or singer synchronization is included in 0.4.
