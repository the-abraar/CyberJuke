# 8-bit mode: plan

Status: phase 1 code written (2026-10-16), not yet heard. `playback/RetroAudioProcessor.kt` does section 4 with the starting constants; `RetroAudioProcessorTest` covers bypass, quantise and hold, ramp, flush, formats, and format changes applying only at flush (6 tests, passing). Reviewed the same day against media3 1.11.1: unsupported formats now return `NOT_SET` instead of throwing, rate-dependent values apply in `onFlush`, and the 16-bit round trip is exact. It reaches the player only when `RETRO_LISTEN_TEST` in `PlaybackService.kt` is set to true in a local build, so the shipped app is unchanged. **Next is yours:** build with it on, listen (section 7, phase 1), and decide section 8. Written against v1.1.2.

## 1. The idea

A switch that makes whatever is playing sound like it is coming out of a retro console, live, while streaming. Flip it mid-song and the same song crunches; flip it back and it is clean. It is a **listening effect**, not a conversion: no re-encoding, no files, no per-song work. For "the 2 people in the world who'll appreciate it", so it must be cheap, safe and off by default.

## 2. What `motif` is (and is not)

[b1rdmania/motif](https://github.com/b1rdmania/motif) is a browser toy that finds a **MIDI** file for a song and re-plays it through a fake **Game Boy** sound chip (2 pulse channels, 1 wave, 1 noise) using Web Audio oscillators. Two consequences:

- It is **not Nintendo DS** and it is **not an effect on audio**. It re-synthesises notes from MIDI; it cannot take a YouTube audio stream. We have no MIDI for arbitrary tracks, so the approach does not transfer.
- It has **no license** in the repo (README says "non-commercial, for lols"), and CyberJuke is GPL-3.0. **Do not copy code.** Use it only as inspiration for the sound vocabulary (pulse/wave/noise, limited channels).

Be honest about the limit of any real-time effect: DSP on a finished mix can make it sound **lo-fi retro** (crunchy, band-limited, aliased, grainy). It cannot turn a vocal pop track into real chiptune with separate voices. That is fine, and it is exactly the "nostalgic 8-bit-ish streaming effect" asked for. True re-synthesis (pitch tracking into pulse waves) is a research project and is listed as a non-goal.

## 3. Where it runs: the native player

Playback is native, not Web Audio. `PlaybackService.kt` builds an Media3 `ExoPlayer` (media3 1.11.1) and the page only sends commands through the `JukePlayer` plugin. So the effect belongs in ExoPlayer's audio pipeline as a custom **`AudioProcessor`**, inserted via the audio sink (`DefaultRenderersFactory.buildAudioSink` / `DefaultAudioSink.Builder().setAudioProcessors(...)`). It sits after the decoder, so it works for every stream the extractor returns, costs nothing in bandwidth, and does not touch the stream cache or the extractor.

No new dependency.

## 4. The effect (what the processor does)

Per-sample, on 16-bit PCM (and float if ExoPlayer hands it over), stereo-aware:

1. **Pre-filter:** one-pole low-pass (about 5 kHz) so it sounds muffled-retro, not just noisy.
2. **Sample-rate reduction:** sample-and-hold with a *fractional* step (source rate / target rate, about 11-12 kHz effective). Keep the aliasing; that is most of the character. Fractional accumulator, not integer decimation, so 44.1 and 48 kHz both land on the same pitch-neutral rate.
3. **Bit crush:** quantise to about 6 bits, no dither (stair-steps are the point).
4. **Soft clip / drive** a little, to make the square-ish edge.
5. **Partial mono collapse** (mid/side, keep a little side). Most old consoles were mono or near it.
6. **Gain compensation** so switching does not jump in loudness (crushing adds energy), with a tiny limiter as a guard against clipping.

The numbers above are **starting values to tune by ear**, held as constants in one place. One intensity only in v1 (see section 8).

### Toggle without clicks or glitches
- The processor is **always active** and has a volatile `enabled` flag; when off it passes samples through untouched. Do not flip `isActive()`: that forces the sink to reconfigure and flush, which glitches and can drop a gapless transition.
- Wet/dry mix ramped over about 20-30 ms on toggle, so no pop. Fully off = bit-exact bypass.

### Constraints to respect
- Handle `ENCODING_PCM_16BIT` and `ENCODING_PCM_FLOAT`; anything else: return `AudioFormat.NOT_SET` from `onConfigure` so the processor simply does not engage. Do not throw `UnhandledAudioFormatException`: `DefaultAudioSink` turns it into a playback error. In practice the sink always hands custom processors 16-bit (with float output on, it skips them entirely).
- Apply rate-dependent values in `onFlush`, not `onConfigure`: the sink reuses the same processor while the old format drains.
- Audio offload (where processors are bypassed) is not enabled in this app; keep it that way, and note it next to the builder.
- Reset filter and hold state in `onFlush()` (seek, track change) so there is no stale-sample burst.
- CPU is trivial (a few ops per sample). No allocation on the audio thread.

## 5. Plumbing (follows the existing `quality` setting exactly)

| Layer | Change |
|---|---|
| `src/stores/library.ts` | add `retro: boolean` to `Settings`, default `false`; normalise on load next to the `quality` clamp (~line 166). |
| `src/player/native.ts` | add `setRetro(o: { enabled: boolean })` to the `JukePlayer` contract. This file says "do not change unilaterally": it is the contract with Kotlin, so change both sides in one commit. |
| `src/player/index.ts` | in the settings sync (~line 148-165) send `setRetro` when it changes, like `setQuality`. |
| `src/player/nativePlayer.ts`, `webPlayer.ts`, `safePlayer.ts` | pass-through / no-op on web. |
| `JukePlayerPlugin.kt` | `@PluginMethod fun setRetro(call)` next to `setQuality` (~line 702). |
| `PlayerPrefsStore.kt` | `retro` boolean in the same SharedPreferences. **Required**, not optional: the service can start and play before the page loads, so it must read the saved value at service start (same reason `quality` is stored there). |
| `PlaybackService.kt` | build the sink with the processor (~line 375) and keep a handle so `setRetro` can flip it. |
| new `playback/RetroAudioProcessor.kt` | the effect (section 4). |

## 6. Product thinking: where it lives in the UI

The ⋯ menu was the first idea. Honest take:

- `TrackMenu` (`PlayerUI.tsx` ~line 743) is a **per-track** action sheet: add to queue, share, like, more by artist, open in YouTube. 8-bit mode is a **global listening mode** that stays on across tracks. Putting it there mixes two ideas, and the fun of this feature is **flipping it during a song to hear the difference**. That needs one tap, on screen, with instant feedback, not open sheet, find row, close sheet.
- A buried toggle will also never be discovered, and discovery is the whole value of a novelty feature.

**Recommendation (v1):**

1. **Now Playing screen: a small toggle button** in the transport area (a pixel-style "8" / chip glyph) with a clear on state (accent glow, `aria-pressed`). One tap, no sheet. This is the primary control.
2. **Settings: an "8-bit mode" row** (same segmented/toggle style as Autoplay and Audio quality, `Settings.tsx` ~line 420) with one line of copy: "Make everything sound like a retro console. Live, nothing is converted." This is the discoverable home and where any future intensity control goes.
3. **Feedback:** a toast on toggle ("8-bit on"), plus an optional CSS-only cue on the cover while on (pixelated scaling or scanlines inside the existing `dos-frame`; the player already has a DOS look). No JS animation, respect `reducedMotion`.
4. **Persistence:** sticky across tracks and app restarts, default off. It is a mode, not a per-song flag.

**Cheapest fallback if you still want the ⋯:** one extra `sheet-item` in `TrackMenu` ("Listen in 8-bit"), roughly 10 lines, shown only when the menu was opened from Now Playing. It is acceptable as an addition, but I would not make it the only entry.

Not doing: a mini-player button (too cramped), a per-track memory, a notification action.

## 7. Phases

**Phase 1: prove the sound (half a day).** Write `RetroAudioProcessor` and wire it with a hardcoded flag. Listen on a few genres (bass-heavy, vocals, acoustic, a quiet intro). Tune the constants. Decide whether it is fun before building any UI. If it just sounds broken, stop here.

**Phase 2: wire it end to end.** Plugin method, prefs, settings field, sync, Settings row. Works from Settings alone.

**Phase 3: the Now Playing toggle and cues.** Button, toast, cover cue, `data-testid`s.

**Phase 4 (optional):** intensity (see below), iOS.

## 8. Decisions for you

1. **Placement:** NP toggle + Settings (recommended) vs ⋯ menu only vs all three.
2. **Intensity:** one setting (recommended for v1) vs 3 levels ("Crisp / Crunchy / Cursed"). Levels are only constants, so adding them later is cheap; add only if tuning shows one setting cannot please both ends.
3. **Web build (browser dev, e2e):** hide the control there (recommended). An `AudioWorklet` version is possible but doubles the work for a dev-only path.

## 9. Tests

- **Kotlin unit test** on the processor with a synthetic sine buffer: when on, output has at most 2^bits distinct levels and shows held-sample runs; when off, output equals input exactly; no output after `onFlush()` carries old state.
- **TS:** settings normalisation (missing or non-boolean `retro` becomes `false`), and the sync sends `setRetro` once per change.
- **Manual (required, can't be automated):** listen on a phone speaker and on headphones; toggle mid-song for clicks; seek and skip with it on; background and lock screen playback; Bluetooth.
- Playwright e2e only checks the toggle's presence and `aria-pressed`; the web build has no native audio.

## 10. Risks

- **It may just sound bad on some music.** Phase 1 exists to find out cheaply.
- **Loudness jump or clipping:** handled by gain compensation and the limiter; verify by ear.
- **Output format surprises** (float, other rates): processor reports unhandled and stays out of the way.
- **Battery:** negligible, but check once with a long play.
- **Maintenance:** one self-contained file, no extractor coupling, so it survives YouTube changes untouched.

## 11. Non-goals

True chiptune re-synthesis from audio (pitch tracking into pulse/wave/noise voices), MIDI lookup à la motif, offline/"export as 8-bit", per-song memory, any change to extraction, the stream cache or the quality setting.

## 12. iOS

`iosplan.md` carries the same contract. The equivalent is an `AVAudioEngine` node chain or a tap on the player output; the TS side (setting, toggle, `setRetro`) is already shared, so iOS only needs its own small DSP port later. Not blocking anything.
