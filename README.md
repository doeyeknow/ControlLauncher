# Control Launcher

An Android launcher built around controlling what the phone shows you: a
calm home screen with only what you choose, a filter layer that strips ads
and trackers, a built-in browser with Reader mode, a walkie-talkie with
channels, and secure messaging on the Control Relay — no accounts, your
profile key is your identity.

Home page: https://launcher.eyeknow.app

## What it does

- **Launcher** — swipe pages (Browser, Home, Walkie, Chat), an Apps sheet,
  favorites, and a circle menu on long-press (with sub-rings).
- **Ad filter** — Private DNS setup flow plus a local VPN that answers DNS
  for known ad/tracker/malware domains with NXDOMAIN.
- **Browser** — the same blocklist, third-party cookies off, Reader mode.
- **Walkie-talkie** — burst voice + text over the internet relay, Wi-Fi
  Direct mesh, LAN, and Bluetooth; 50 channels with a tuner, private
  channels, channel subscriptions, read-aloud, and History.
- **Chat** — relay messaging with per-contact keys shared by sound.
- **Connections** — radio controls in two tiers (plain switches, and
  governed shell controls through the Deep view bridge).
- **Deep view** — a shell bridge (started over wireless debugging) for
  governed controls, a shell terminal, and hold-anywhere menu detection.
- **Wallet** — card storage under an Android Keystore key, with a Pay
  handoff to Google Pay / Samsung Wallet.
- **Profile** — one versioned, encrypted JSON export carrying favorites,
  mutes, filter, browser, contacts, channel bank, and identity.

## Building

No Gradle. The build is a manual pipeline (aapt2 → javac → d8 →
zipalign → apksigner) driven by `build-apk.sh`. It needs a JDK 17 and the
Android SDK (platform + build-tools 34) — `env.sh` points at them. See
`BUILD_NOTES.md` for the full build log, the version history, and the
decisions behind each feature.

Signing: every update must be signed with the same release key. The
keystore and its credentials are deliberately NOT in this repo.

## Layout

- `src/` — the app (pure Java, no Kotlin, no AndroidX).
- `harness/` — JVM harnesses proving the pure-Java pieces (run before
  every release; see `BUILD_NOTES.md`).
- `libs/` — vendored Bouncy Castle jars (ADB pairing TLS + crypto).
- `native-libs/` — vendored Vosk native libraries (offline speech
  recognition for walkie low-data mode).
- `assets/` — bundled assets (Readability, blocklist).
- `res/` — resources. `AndroidManifest.xml` — the manifest.
