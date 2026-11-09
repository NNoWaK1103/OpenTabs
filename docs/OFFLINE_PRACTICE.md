> Historical note: this document describes the earlier native-library prototype. OpenTabs 0.5 exposes only Songsterr and Ultimate Guitar; native samples, catalogs, lists and import controls are no longer visible. Existing private files are preserved. See [current app behavior](../README.md) and [provider saving](PROVIDER_SAVING.md).

# Offline practice in 0.4

OpenTabs has two separate ways to use tabs. The distinction is visible in the app:

- **Local library:** imported text and supported Guitar Pro 7 arrangements are copied into app-private storage. They remain available without internet or access to the original file.
- **Provider websites:** Songsterr and Ultimate Guitar are original online pages. **Save link** saves only a canonical tab-page address. **Saved links** says **Internet required**. It does not download the tab, audio, account information, or a provider player.

## Try it without internet

1. Open **My Tabs** and select one of the three original OpenTabs exercises.
2. In **Now Playing**, use **Play audio** to hear synthesized guitar notes. **Auto-scroll** remains a separate control.
3. Pause, stop, seek, change practice speed, or loop the whole written score.
4. Turn off internet and repeat. Local playback uses an app-private MIDI file and Android's built-in media decoder, not a streaming service.

A supported GP7 `.gp` import offers the same controls after choosing its six-string arrangement. Only import material you own or are authorized to use. Compatible licensed TabForge arrangements also keep their source attribution and optional timed score. Legacy saved TabForge previews can gain timing by saving the same arrangement again, provided the stored text still matches that arrangement.

## What the audio means

This is an **authored-timing practice approximation**, not a complete Guitar Pro performance engine or the original recording.

Supported information includes six-string single-staff frets, tuning, capo, rests, multiple non-conflicting voices, time signatures, note values, dotted notes, ordinary tuplets and authored quarter-note tempo events. Tuplets are rounded within one MIDI tick. A full-measure whole rest respects its measure's time signature. Each string has a separate MIDI channel so a unison on another string does not cut a note off.

The player follows written measure order. It does not perform repeats, alternate endings, jumps, fine, swing/shuffle or fermatas. Gradual tempo changes are immediate changes. Expressive techniques, dynamics and sustain are simplified. Ties are re-picked, grace notes are omitted, and dead/muted notes are silent. These limitations appear in the audio controls.

Pickup/anacrusis timing is not supported in this release. A malformed or unsupported timed score still imports as a readable position preview when the existing text decoder supports it. The app explains why audio is unavailable. It does not invent rhythmic values from text-tab spacing or silently substitute a default tempo. Plain text remains viewable with auto-scroll, but has no timed audio.

The six-line text viewer is **not synchronized to the audio playhead**. Auto-scroll has its own user-selected speed. Current audio controls provide a whole-score loop rather than A–B measure looping.

## Interruption and data safety

Audio pauses on navigation, backgrounding, headphones disconnecting, and audio-focus interruption. It does not resume automatically after those interruptions. Changing songs cancels obsolete loads and releases the previous player. Changing speed regenerates MIDI tempo without changing written pitches.

Timed scores use optional versioned JSON sidecars in the existing local library. Existing text, metadata, favorites and settings are preserved. GP7 imports saved by older versions need re-importing for audio because those versions discarded the source timing. A corrupt sidecar does not make its text tab unreadable. Existing original exercises receive authored sidecars on upgrade. Deleting an imported song removes its private text and timing copies, never the original file.

Saved website links use a separate bounded atomic store. The app saves only recognized tab-page paths; search, sign-in and account pages cannot be bookmarked. Query strings and fragments are discarded to avoid saving authentication/session parameters. A corrupt link store is preserved rather than overwritten.

Android cloud backup remains disabled. Uninstalling or clearing app data removes the local library and saved links. Keep original imports. Backup/export and setlists are useful next milestones, but are not included in 0.4.

## Validation boundary

The JVM suite checks timing, MIDI structure, library persistence and transport lifecycle through a deterministic fake audio output. Instrumentation tests additionally exercise Android's real MIDI decoder and Compose controls when run on a device/emulator. Merely compiling those tests is not a device test or proof of audible output on a particular tablet. See the release validation record for the checks actually executed.
