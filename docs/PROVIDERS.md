# Tab sources and native formats

## Online discovery

Only Songsterr and Ultimate Guitar are connected. OpenTabs reuses its existing v0.3 public website search routes and embedded viewer, rather than retrieving song metadata or scraping tab content:

| Source | Public search route | Results and tabs | Fallback |
| --- | --- | --- | --- |
| Songsterr | `https://www.songsterr.com/?pattern=...` | Original website rendered in WebView | Explicit external-browser action |
| Ultimate Guitar | `https://www.ultimate-guitar.com/search.php?search_type=title&value=...` | Original website rendered in WebView | Explicit external-browser action |

Title and artist are URL-encoded as query values. Website results may offer different instruments or arrangements; users select the guitar tab they want on the provider's page. OpenTabs does not fabricate native arrangements or label song metadata as playable tablature.

Content, ads, sign-in, subscriptions, and access rules remain under each provider's control. No private endpoint, paywall bypass, User-Agent disguise, injected script, ad filter, or proprietary content extraction is used. Public accessibility is not treated as a redistribution license. WebView's ordinary browser cache is not an offline native-tab library.

The MusicBrainz integration and additional online catalog/download pipeline were removed after auditing their references. No replacement provider was added. The unrelated Spotify and recognition boundaries remain intact; local library title/artist information and saved licensing fields are retained.

## Native viewer and imports

- Plain-text `.txt` / `.tab`: validated UTF-8, preserved monospace layout, six-string groups required. Limits remain 512 KiB, 6,000 lines, and 1,000 columns per line.
- GP7 `.gp`: existing bounded ZIP/GPIF decoder, single-staff six-string arrangements with authored string/fret positions. Native previews show written measures. Timing, expressive techniques, repeats, and playback are not interpreted.
- GP3–GP5, GPX, and MusicXML: still unsupported; no new parser was introduced.
- Existing saved tabs: their text, favorites, practice state, and provider/license attribution are preserved. The library format remains version 1.

The CC0 GP7 score in `core/src/test/resources` is retained solely as an import/parser regression fixture, with its attribution. It is not an online provider and is not bundled in the production app.

See [browser behavior and validation limits](WEB_BROWSER.md) and [the Git history audit](SEARCH_SIMPLIFICATION.md).
