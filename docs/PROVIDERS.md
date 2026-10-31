# Online search and content rights — reviewed 2026-10-31

OpenTabs 0.2 does not promise tablature for every song. It combines a private offline library, worldwide song discovery, and a deliberately small authorized native-content catalog. A metadata match is not evidence that an arrangement exists or that OpenTabs may download it.

## Implemented providers

| Source | Search metadata | Arrangements / native content | Cache and offline saving |
| --- | --- | --- | --- |
| My Tabs | Title, artist, arrangement, tuning from this device | Existing validated text tabs and saved native previews | App-private local library, as in v0.1 |
| MusicBrainz | Live recording title and artist search through the documented public API | **Metadata only. No tablature, arrangement, tuning, or instrument is asserted.** | Only returned core recording/artist metadata is cached; CC0. No music or tab is downloaded. |
| TabForge CC0 | Live retrieval of the author's original **Ashen Meridian** demo, with actual track names, instrument programs, tuning, and capo | Authored string/fret position previews for **Lead Gtr**, **Lead Harmony**, and **Clean Gtr**. Other bass, 7-string, and 8-string arrangements are reported as unsupported. | Original score is explicitly CC0. Compressed source may be cached; Save & open stores the validated text preview with attribution and source/license URLs. |

The TabForge provider is one original demo composition with multiple arrangements, **not a general commercial-song catalog**. It fetches the real published score; it does not generate a replacement for an unavailable song. The source and test fixture are not bundled as production-library songs.

MusicBrainz is free for noncommercial API use without a key, requires an identifying User-Agent, and limits requests. OpenTabs identifies itself and serializes requests at least 1.1 seconds apart. Its core data is CC0; the wider MusicBrainz database also contains differently licensed supplementary data, which this adapter does not request. Commercial distribution must review the API's commercial plans separately from the data license.

Sources:

- [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API)
- [Search syntax](https://musicbrainz.org/doc/Indexed_Search_Syntax)
- [MusicBrainz data licensing](https://musicbrainz.org/doc/About/Data_License)
- [Rate limiting](https://musicbrainz.org/doc/XML_Web_Service/Rate_Limiting)
- [TabForge score source](https://github.com/cobhc95/TabForge/tree/main/samples)
- [TabForge README and original demo dedication](https://github.com/cobhc95/TabForge#readme)
- [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)

The GPIF `Score/Copyright` and `Score/Instructions` in the downloaded Ashen Meridian score also explicitly declare the original composition CC0. The adapter verifies the CC0 declaration on every parsed source, including cache reads. This is a check on this reviewed source, not a general-purpose license detector. No TabForge software or alphaTab implementation was copied.

## Investigated, not connected

| Candidate | Metadata access | Full content / display rights | Cache rights and decision |
| --- | --- | --- | --- |
| Songsterr | Historical API documentation was unavailable at review. The reference project calls an internal JSON endpoint. | Site access and publisher licensing do not establish permission for independent native downloading/display. Download is described as a Plus feature. | No authorized third-party caching contract was established. No requests to internal endpoints, downloads, player automation, or entitlement changes are implemented. |
| Ultimate Guitar | The reference project scrapes search HTML and extracts embedded application JSON. | Its terms restrict content copying and use outside its service; public pages do not grant an OpenTabs content license. | No documented authorized API or caching permission was established. Adapter is not implemented. |
| Mutopia | Publicly browsable classical-guitar catalog and open source archive | Per-score licenses permit reuse subject to their terms; standard source formats are LilyPond, PDF, and MIDI, not authored GP7 positions. | Could cache individual properly attributed licensed scores, but no supported native format path was established in this phase. No PDF display, synthesized fingering, or claim of MusicXML support is added. |
| OpenScore / PDMX | Public-domain or CC0 score collections with per-record rights considerations | A score file's license and underlying composition/arrangement both matter. Many entries are standard notation without authored guitar strings/frets. | Potential future MusicXML providers, after selection and parser work. A large research dataset is not downloaded onto the tablet or misrepresented as a guitar-tab API. |
| Melodica Trainer score library | Catalog includes per-asset provenance, rights notes, and MusicXML / GP7 assets | The inspected original CC0 GP7 scale is a non-fretted **Melodica** track with MIDI pitches and no authored string/fret data. | Its original CC0 exercises are reusable, but OpenTabs does not invent guitar fingerings for them. No native-tab provider is claimed. |
| Classtab | Public classical-guitar text index; site offers a personal whole-site download | Public accessibility and the downloadable archive do not establish blanket third-party redistribution rights for every transcription. | No blanket license is assumed, and no copied whole-site tab archive is included. |
| Other GitHub tab collections / Guitar Pro collections | Some contain usable files | A repository being public, or the code having a license, does not necessarily license its contributed musical works. | Unknown or conflicting rights are excluded. The explicitly original CC0 TabForge demo is the usable GP source selected here. |

Supporting references:

- [Songsterr terms](https://www.songsterr.com/terms) and [automated-agent policy](https://www.songsterr.com/ai.txt)
- [Ultimate Guitar terms](https://www.ultimate-guitar.com/about/tos.htm)
- [Mutopia licenses](https://www.mutopiaproject.org/legal.html) and [source archive](https://github.com/MutopiaProject/MutopiaProject)
- [OpenScore CC0 collections](https://fourscoreandmore.org/openscore/)
- [PDMX project](https://github.com/pnlong/PDMX)
- [Melodica Trainer rights notes](https://github.com/shafranek-js/MelodicaTrainer/blob/main/public/score-library/LICENSES.md)
- [Classtab](https://www.classtab.org/)

These conclusions describe the evidence available at review, not a claim that these services can never offer a licensed integration. A provider can be added when a documented API, a supported format, and explicit use/caching permissions are available.

## Reference project review

[tablatures/tablatures-api](https://github.com/tablatures/tablatures-api) was inspected, including `api/services/sources/songsterr_source.py`, `ultimate_guitar_source.py`, `live_search_service.py`, and `pyproject.toml`. At review its repository tree contained no LICENSE/COPYING/NOTICE file and its project metadata declared no software license. It is not treated as reusable open-source code.

Its Songsterr source calls `/api/songs` with a browser-like User-Agent. Its Ultimate Guitar source requests `search.php` HTML, extracts `.js-store` embedded JSON, and builds download URLs. Its aggregation, download routing, and caching do not establish upstream content rights. None of that code, its scraped database, private endpoints, or its hosted service is used in OpenTabs. The new Kotlin implementation is independent.

## Search behavior

`TabSearchProvider` separates search metadata from authorized retrieval. `TabSearchResult` carries source, arrangement, known tuning, instrument/role tags, a native-content state, and explicit display/cache/save rights. Unknown rights default to denied. MusicBrainz results cannot enter the native download path.

The coordinator runs up to two remote providers concurrently and emits each completed source promptly. Every provider has a 12-second deadline. Cancellation propagates to OkHttp calls. One error, offline source, or timeout leaves other results available. Title and optional artist fields support exact, partial, case/accent-normalized, and bounded one-edit fuzzy matching; exact title/artist matches rank first. Metadata duplicates are collapsed, distinct arrangements remain separate, and a saved arrangement replaces its downloadable counterpart.

The UI shows instrument/role filters only when returned metadata supports them; it does not infer that a recording is guitar, acoustic, lead, rhythm, bass, or chords from a song title. **Native tabs only** removes metadata-only and unsupported arrangements. Song metadata remains explicitly labeled. Empty queries search the offline library without issuing online requests. Search input is debounced by 450 ms; leaving Search cancels its work. No polling or background search is scheduled.

## HTTP, cache, and validation

The small OkHttp client uses HTTPS, explicit allowed hosts, no redirects, no automatic retries, 5-second connection / 8-second read / 10-second total call timeouts, and bounded response streams. Providers parse JSON or validate ZIP/GPIF rather than accepting an HTML error as content. No OAuth credentials, API keys, or hosted proxy is required.

Only explicitly reusable provider responses enter the licensed-response cache after validation. MusicBrainz metadata has a five-minute freshness window; the CC0 score has a one-day window. Cached responses are limited to 12 files and 2 MiB total. A network failure may use an authorized stale response for up to seven days, with a cached/offline notice. Raw server response bodies are not printed in error messages. Cache failure cannot invalidate a successful search. User-saved, attributed tabs are separate persistent library records and remain accessible offline until deleted.

## Native GP7 preview limits

The independently written streaming SAX reader accepts ZIP-based `.gp` files containing `Content/score.gpif`, at most 1 MiB compressed / 8 MiB expanded, 64 archive entries, 200,000 XML elements, 48 levels, 32 tracks, 500 measures, and 25,000 note records. XML DTDs and external entities are rejected. It retains small typed records instead of a whole-score DOM. File parsing and I/O run outside the UI thread.

Only single-staff six-string tracks with explicit authored string and fret values are previewed. Missing positions, out-of-range frets/strings, invalid references, simultaneous conflicting notes on one string, and overly large text output fail visibly. There is no pitch-to-fret guessing. The native text viewer displays the written measures and voices; **repeat expansion, rhythmic spacing, expressive techniques, audio playback, and synchronization are not interpreted**. Capo and tuning are shown in the document. The preview is labeled accordingly and saved in the existing validated plain-text format.

SAF imports of supported `.gp` files offer a native arrangement chooser. GP3–GP5, GPX, MusicXML, PDF, and general score engraving are not implemented. Text imports and all v0.1 viewer controls remain supported.

## Verification

Default CI tests are deterministic and exercise the real CC0 score fixture, mocked HTTP edge cases, search concurrency/cancellation/timeouts, offline fallback, malformed responses, rights enforcement, deduplication, legacy-library compatibility, and saved attribution. Tablet smoke tests exercise offline search and saving/opening the licensed arrangement on API 26 and API 35 using test-only cached provider fixtures.

To independently verify the real online services without cached responses:

```sh
./gradlew :core:test -PliveProviders=true
```

The opt-in live tests search MusicBrainz for a real recording and retrieve the author's live CC0 guitar score. They are separate from default test fixtures so transient third-party outages do not make normal builds nondeterministic. GitHub Actions continues to publish **OpenTabs-debug-apk**.
