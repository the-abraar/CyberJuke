# CyberJuke on iOS: plan

Status: plan only, nothing implemented. Written against v1.1.2.

## 1. What we are porting

CyberJuke is a Capacitor 8 app. The UI and most logic are shared TypeScript (Preact + signals) in `src/`. Everything platform-specific sits behind three Capacitor plugins that are written in Kotlin and registered by name:

| Plugin | TS contract | Android implementation | Size |
|---|---|---|---|
| `JukePlayer` | `src/player/native.ts`, `nativePlayer.ts` | `bridge/JukePlayerPlugin.kt` + `playback/*` (Media3/ExoPlayer, `PlaybackService`) | ~5,000 lines |
| `JukeMusic` | `src/data/ytmusic.ts` ("keep in sync with MusicPlugin.kt") | `bridge/MusicPlugin.kt` + `yt/*` + `lyrics/*` + `net/*` (NewPipeExtractor, InnerTube) | ~2,500 lines |
| `SecureStore` | `src/data/secureStore.ts` | `bridge/SecureStorePlugin.kt` (Keystore AES-GCM) | 432 lines |

Everything else (Firestore reads, Firebase Auth over REST, the catalog, library, themes, search, e2e tests) is plain `fetch` or `@capacitor/*` and should run on iOS unchanged.

**The work is: add `ios/`, then write Swift implementations of those three plugins that honour the existing TS contracts.** The TS side should barely change.

## 2. Go/no-go risks (settle these first)

These can sink the project, so they get spikes before any real work.

1. **YouTube stream extraction has no iOS equivalent of NewPipeExtractor.** NewPipeExtractor is Java and is what resolves a `ytId` to an audio stream URL, including signature and `n`-param deobfuscation. On iOS we would have to do one of:
   - **(a) Reimplement in Swift:** InnerTube clients (the iOS or `ANDROID_VR` client often returns direct, non-ciphered URLs), plus a JS-based `n`-transform solver using `JavaScriptCore`. Most control and no new runtime, but it is a permanent maintenance burden because YouTube breaks it regularly.
   - **(b) Embed a JVM or port via a bridge:** not viable on iOS.
   - **(c) Use a maintained Swift/other library** (YouTubeKit or similar). Needs a license (GPL-3.0 compatibility), maintenance and security review. Cheapest if one is healthy.
   - **Spike:** a standalone Swift CLI or test target that resolves 20 varied `ytId`s (music, age-gated, members-only, region-locked, live) to a playable `googlevideo` URL and plays 30 s in `AVPlayer`. Record the success rate. Below ~90%, stop or rescope.
2. **Distribution and policy.**
   - The app is an unofficial YouTube/Cyberspace client. App Store review will likely reject it (YouTube ToS, background audio from YouTube). Assume **no App Store**.
   - Realistic channels: build-from-source, AltStore/SideStore, or TestFlight to a small group (needs a paid $99 Apple Developer account and still counts as review).
   - EU DMA alternative marketplaces are possible but out of scope.
   - GPL-3.0 and the App Store terms conflict, which is one more reason to avoid the App Store.
   - **Decision needed from the maintainer before the spike is worth doing.**
3. **YouTube blocking from iOS networks.** The Android code has `NetBlock`, `YtGuard`, IPv4 modes and rate-limit handling. Confirm the iOS clients are not bot-checked harder than the Android ones.
4. **Hardware and tooling.** Needs a Mac with Xcode and a physical iPhone. The simulator cannot validate background audio, lock-screen controls or audio-route behaviour. Free provisioning profiles expire after 7 days (relevant for sideloading).

## 3. Target and scope

- iOS 16+ (Capacitor 8 requires iOS 15+; 16 gives a sensible floor). iPhone first. iPad runs the iPhone layout, with no iPad-specific UI in v1.
- Same `appId` (`io.github.iamandelib.cyberjuke`) so the bundle id matches.
- **Parity for v1:** browse, search, playback (background and lock screen), queue/autoplay, Global search, lyrics, sign-in, library, themes, share, update notice.
- **Explicitly out of v1:** CarPlay, widgets, Live Activities, Siri/Shortcuts, App Store release, offline downloads (the app has none today).

## 4. Phases

### Phase 0: Decisions and spikes (1 to 2 weeks)
- Decide on distribution (section 2.2).
- Stream-extraction spike (section 2.1). Output is a go/no-go note in `docs/`.
- Spike: add `ios/` with Capacitor, load the existing `dist/`, and confirm the WebView renders, Firestore `fetch` works from `capacitor://localhost`, and the CSP in `index.html` holds.

### Phase 1: iOS shell
- `npm i @capacitor/ios@^8`, `npx cap add ios`, commit `ios/App` (the Xcode project; ignore `Pods`/`build` as Android's `.gitignore` does).
- `capacitor.config.json`: add an `ios` block (`backgroundColor: "#000000"`, `contentInset: "never"`, `limitsNavigationsToAppBoundDomains`, and `scrollEnabled: false` if the app handles scroll itself).
- `Info.plist`: `UIBackgroundModes = [audio]`, `UIStatusBarStyle`, `UILaunchStoryboardName` (black), orientation lock to match Android, `ITSAppUsesNonExemptEncryption = false` (fixes the export-compliance prompt).
- Make `npm run sync` also run `cap sync ios` (or add `sync:ios`). Add `ios` to the existing `sync` flow in `package.json`.
- Plugins missing on iOS (`JukePlayer`, `JukeMusic`, `SecureStore`) currently reject. Make sure the app boots to a clean error and not a crash while later phases land. Check `src/app/BootError`.
- Exit: app launches on device with the web UI, Library works, and playback is clearly unavailable.

### Phase 2: SecureStore (smallest, do it first as the template)
- Swift plugin `SecureStorePlugin` (CAPPlugin + `CAPBridgedPlugin`), backed by the **Keychain** (`kSecClassGenericPassword`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so background playback and token refresh work and nothing syncs to iCloud).
- Match the TS contract exactly: `get({key}) -> {value|null}`, `set`, `remove`. Read `SecureStorePlugin.kt` for edge cases (error codes, size limits, migration) and mirror them.
- Test with the existing `auth.ts` flow (sign in, kill app, relaunch, still signed in).

### Phase 3: JukeMusic (data: search, artist, lyrics, radio)
- Port `yt/*`, `lyrics/*` and the needed parts of `net/*` to Swift (`URLSession`, `JSONSerialization`/`Codable`).
- Contract is `JukeMusicPlugin` in `ytmusic.ts`: `search`, `more`, `playlist`, `artist`, `lyrics`, `artistPage`, `artistReleases`, `radio`. Error codes (`MusicError`/`CODES`) must be identical strings.
- Port in this order, each against the existing fixtures and TS tests: `InnerTubeJson`/`MusicText` (pure parsing, easy to unit-test) -> `InnerTube`/`YtMusic` -> `ArtistPage` -> `Radio` -> `Lrc`/`Lyrics`.
- Reuse the Kotlin unit-test fixtures as XCTest fixtures so both ports are checked against the same JSON. Consider moving shared fixtures to a neutral dir.
- Port the network policy: `FailureKind`, `LimitPolicy`, `NetEpoch`, `NetBlock` (429/bot-check detection, backoff) so the `blocked`/`unblocked` events behave the same.
- IPv4 modes (`IPV4_MODES` in settings) are an Android-specific workaround. Decide per mode whether iOS needs it (probably start with "system default" only and hide the setting on iOS).

### Phase 4: JukePlayer (the big one)
Architecture: keep the **native side as owner of the queue** (the TS `NativePlayer` is only a mirror). Port that ownership to Swift.

- **Engine:** `AVQueuePlayer`/`AVPlayer` with resolved stream URLs. Requests for googlevideo may need headers, so use an `AVURLAsset` with `AVURLAssetHTTPHeaderFieldsKey` or a resource loader. Handle URL expiry (~6 h) by re-resolving, which is what `StreamResolver`/`StreamCache`/`YtDataSpecResolver` do on Android.
- **Queue logic is the valuable part.** `QueueOrder.kt` (543 lines), `QueueCommands`, `RadioFeeder`, `LastSession`, `SessionPolicy`, `TrackErrorPolicy`, `StateEncoder` are mostly platform-independent. Port to Swift as pure types with no AVFoundation dependency so they can be unit-tested.
- **State and events:** replicate the event stream the TS side parses (`parseNativeState` in `core/guards.ts`; `tracks`, `blocked`/`unblocked`, `queueIdsUnchanged`, `STALE_INDEX` error). Keep the K-numbered invariants (K2 stale index, K6 bridge limits `BRIDGE_MAX_ITEMS`/`BRIDGE_MAX_STRING`) as written.
- **Audio session:** `AVAudioSession` category `.playback`. Handle interruptions (calls, Siri), route changes (headphones unplugged means pause; see `AudioOutputs.kt`), and resume after interruption.
- **Now Playing and remote commands:** `MPNowPlayingInfoCenter` (title, artist, artwork, elapsed, duration, rate) and `MPRemoteCommandCenter` (play/pause/next/previous/seek/shuffle/repeat). This replaces the Media3 `MediaSession` and notification. Artwork needs loading from `ytimg` URLs (CSP only covers the WebView, but native needs its own fetch).
- **Last session restore** ("opens on what you last played, paused at the same spot"): persist to `UserDefaults` or a file in Application Support, and restore on cold launch.
- **Quality setting and `Quality`/`NetStatus`:** map to formats chosen by the stream resolver.
- **Background reliability:** gapless-ish advance with `AVQueuePlayer` item lookahead, and stall/retry per `TrackErrorPolicy`.
- Exit: playback survives screen lock, app switch, Bluetooth/AirPods buttons, an incoming call, and a cold relaunch.

### Phase 5: Platform polish in the web layer
Small, localized TS and CSS changes, behind `Capacitor.getPlatform()` checks (not new abstractions):
- **Safe areas:** `env(safe-area-inset-*)` for notch/home indicator in the mini player, nav, toasts, Now Playing. `viewport-fit=cover` in `index.html`.
- **Status bar:** `@capacitor/status-bar` style/overlay per theme (already used on Android).
- **Back navigation:** Android hardware back (`@capacitor/app`) has no iOS analogue. Check any code relying on it and provide the iOS edge-swipe or on-screen back where needed. Note WKWebView edge-swipe-back can conflict with the in-app gestures (swipe-to-dismiss Now Playing, swipe-away toasts, `setGestureExclusion` in `src/ui`, which is Android-specific).
- **Keyboard:** WKWebView resize/scroll behaviour with the search field (`Keyboard` plugin or `visualViewport`).
- **Overscroll/rubber-banding and pull-to-refresh:** `overscroll-behavior`, and verify the custom pull-to-refresh does not fight native bounce.
- **Text and input:** `-webkit-text-size-adjust`, 16px minimum input font so iOS does not zoom, `-webkit-touch-callout` and the long-press menu behaviour.
- **Share:** `@capacitor/share` works as-is. Check `sharePayload`.
- **Fonts and themes:** verify the eight themes (C64/VT320 pixel fonts) render the same under WebKit. Canvas/CSS differences are the likely issues.
- **Copy:** README/UI strings that say "Android", "APK", "notification", "F-Droid", "Update notices" need platform-aware wording. Update notices (`api.github.com` release check) should be hidden on iOS or point at the right channel.
- **CSP:** re-check `index.html`. `capacitor://localhost` is the origin, so the existing `connect-src`/`img-src` should hold, but confirm `media-src 'none'` is fine given audio is native.

### Phase 6: Testing
- **Unit/contract:** keep `npm run check` green. Add XCTest targets for the Swift ports (parsers, queue order, net policy) using shared fixtures.
- **Contract test:** one test that dumps the plugin method/event names and error codes from TS and asserts Swift defines the same (the TS and Kotlin sides are already "keep in sync" by comment, so this is a good place to automate it).
- **E2E:** existing Playwright suite runs against the browser build with stubs and is unaffected. Add a manual device checklist to `docs/TESTING.md`: lock-screen controls, interruption, AirPods, route change, low-power mode, background for 30+ minutes, token refresh while backgrounded, cold-launch restore, airplane mode, YouTube-block banner.
- **Real-network smoke:** the `@live` Playwright tag covers web. Add a short manual live pass on device.

### Phase 7: Build, CI and release
- **CI:** add a `macos-latest` job: `npm ci`, `npm run build`, `npx cap sync ios`, `xcodebuild build` for the simulator (compile check only), plus XCTest. Don't gate Android CI on it at first.
- **Signing/distribution:** per the decision in section 2.2:
  - Sideload: unsigned `.ipa` artifact on GitHub Releases for AltStore/SideStore, with a source JSON.
  - TestFlight: fastlane `match`/`pilot` (the repo already has `fastlane/`; add an iOS lane).
- **Reproducibility/verification:** Android has `reproducible.yml` and `gradle-verification.yml`. For iOS, pin Xcode version and SwiftPM dependencies (`Package.resolved` committed). Full reproducibility is not realistic, so say so in `SECURITY.md`.
- **Docs:** `README.md` (iOS section and install), `docs/PUBLISHING.md` (iOS release steps), `CONTRIBUTING.md` (Xcode setup), `CHANGELOG.md`, `NOTICE.md` (third-party licenses for any Swift libs).
- Versioning: keep one `package.json` version and derive `CFBundleShortVersionString`/build number from it, as Android does.

## 5. Suggested order and rough effort

| Phase | Effort | Blocks on |
|---|---|---|
| 0 Decisions and spikes | 1 to 2 wk | maintainer decision, a device |
| 1 Shell | 2 to 3 d | 0 |
| 2 SecureStore | 1 to 2 d | 1 |
| 3 JukeMusic | 2 to 3 wk | 1; stream spike for `radio`/resolver parts |
| 4 JukePlayer | 4 to 6 wk | 2, 3 (resolver) |
| 5 Web polish | 1 wk (parallel with 3/4) | 1 |
| 6 Testing | continuous, +1 wk final pass | |
| 7 CI/release | 3 to 5 d | 4 |

Roughly 2.5 to 3.5 months for one developer who knows Swift. Mostly the player and extraction.

## 6. Ongoing cost

- Two native implementations to keep in sync (Kotlin and Swift) for every change to the plugin contracts, queue rules or YouTube parsing. The YouTube breakage-chasing is the biggest recurring cost and now doubles.
- Mitigations: shared JSON fixtures, the contract test, and keeping logic in TS wherever it can live there (prefer moving future logic up into `src/` instead of into either native side).

## 7. Open questions for the maintainer

1. Distribution channel and whether to accept no App Store (section 2.2)?
2. Is there an Apple Developer account and a physical iPhone for testing?
3. Is a third-party Swift YouTube library acceptable, or should extraction be written in-house?
4. Should iOS ship feature-complete, or Jukebox-only first (Firestore content plus playback) with Global search following?
