# Control — build notes

Working name **Control**, applicationId `app.control.launcher` (the label can
change later; the applicationId should not). **Version 1.19 (versionCode 23)** —
see the version sections below; min SDK 26, target SDK 34. Pure Java; the
external dependencies are ZXing core 3.5.3 (`libs/zxing-core.jar`, QR —
profile transfer only since v1.8), Bouncy Castle 1.84 (`libs/bc*.jar`,
the TLS 1.3 stack for ADB wireless pairing/connect since v1.2), and since
v1.11 Vosk 0.3.45 + JNA 5.19.1 (`libs/vosk-classes.jar`,
`libs/jna-classes.jar`, native libs in `native-libs/` — on-device speech
transcription for walkie low-data mode).

## Rebuild (exact commands)

Gradle cannot run in this VM (the sandbox garbles JVM IPv4 socket traffic and
the Gradle daemon handshake always fails — see `~/AGENTS.md`). The APK is
assembled manually with the SDK tools, the same approach as the Palm app:

```sh
cd ~/workspace/control-launcher
./build-apk.sh        # = the steps below, end to end
```

`build-apk.sh` runs: `aapt2 compile` (res) → `aapt2 link` (manifest, resources,
assets, generated `R`) → `javac --release 17` (sources in `src/` + generated R,
classpath = android-34 `android.jar` + ZXing) → `d8 --min-api 26` → zip dex
into the APK → `zipalign` → `apksigner sign` → copy to
`~/workspace/control-launcher/control-launcher.apk`, then verifies and prints
badging. Toolchain: Temurin JDK 17 and the Android SDK under
`~/workspace/native-build/` (`env.sh` in this directory sets JAVA_HOME /
ANDROID_HOME / PATH).

## Signing key — do not lose it

- Keystore: `~/workspace/control-launcher/control-release.keystore` (alias
  `control`), created 2026-11-10 by `build-apk.sh`.
- Credentials: `~/workspace/control-launcher/keystore-credentials.txt`
  (mode 0600; the password is **not** reproduced here).
- **Every future update of this app must be signed with this key** or Android
  will refuse to install it over v1. Back both files up with the project.

## v1.1 (2026-11-10) — pages, app-list fix, default home, device admin, ADB self-start

Verified: `./build-apk.sh` completes end to end; `apksigner verify` passes
(v2 + v3) with the **same** signing certificate as v1
(`f646377d…f372a1c52`), so it installs over v1 as an update; badging shows
versionCode **2** / versionName **1.1**; manifest xmltree confirms the HOME
category, all 23 v1 activities, the browser VIEW filter, and the new admin
receiver with its `android.app.device_admin` meta-data
(`res/xml/device_admin.xml` is compiled into the APK). APK: 775,087 bytes.
Still **no on-device test** — everything below is code-complete and
build-verified; Adam's phone remains the first runtime test, as with v1.

### Pages (was: separate activities)
`MainActivity` now hosts a `PagerLayout` with four pages side by side —
**Home · Browser · Walkie · Apps** — starting on Home, with a tappable
dots indicator and the dock below it (Home/Apps/Browser/Walkie dock
entries jump to their page; Chat/Activity/Settings still open as
screens). Home content lives in `HomePage`, the browser in
`BrowserPage`, the walkie-talkie in `WalkiePage`, the app drawer in
`AppsPage`; each is a view with shown/hidden/destroy lifecycle driven by
the pager + host activity. `BrowserActivity`, `WalkieActivity`, and
`DrawerActivity` remain as thin forwarders: external **VIEW http/https
intents still land on the Browser page** (URL carried as an extra), and
bookmarks open the same way.
**Gesture rule**: the pager takes a horizontal swipe only when it does
NOT start on a surface that owns horizontal gestures — the Browser page
yields touches that start on the WebView, the Walkie page yields the
tuner sweep, the hold-to-talk button, the thread list and the composer,
the Apps page yields the search field — and a swipe starting within the
28dp screen-edge strips always belongs to the pager, so any page can
always be left by an edge swipe. Everything else (chrome, headers,
backgrounds) paginates. Back: not on Home → back to the Home page; on
Home → swallowed, as in v1.

### App-list fix — ROOT CAUSE
Adam (on-device, v1): "cant select a favorite or any apps in the
applists." Cause: **`AppPickerActivity` rows embed a `CheckBox` that was
`clickable=false` but still focusable (the default). A focusable
descendant inside an `AbsListView` row suppresses the list's
`OnItemClickListener`, so taps never reached the toggle code** — in all
three picker lists (Favorites, Muted apps, Filter bypass), and the
checkbox itself was unclickable, so there was no working way to select
anything. Fix: the checkbox is now `focusable=false` +
`focusableInTouchMode=false`, and each row carries its own click
listener as well, so selection does not depend on AbsListView click
routing. The new **Apps page** avoids the pattern entirely: plain cells
with their own handlers — tap opens the app (launch intent,
`FLAG_ACTIVITY_NEW_TASK` via `InstalledApps.launch`), long-press
stars/unstars a favorite; favorites continue to live in the profile
(`Prefs.KEY_FAVORITES` → `profile.json`) and render on the Home page,
with a ★ on starred apps in the grid.

### Default-home flow
v1 declared the HOME category but offered no in-app path. v1.1 adds
`HomeRole`: detection via `RoleManager.isRoleHeld(ROLE_HOME)` on API
29+ (HOME-intent resolution below that), request via
`createRequestRoleIntent(ROLE_HOME)` (fallback `ACTION_HOME_SETTINGS`).
Surfaces: a first-run card on the Home page (visible only while another
launcher holds the role) and a **Settings → Home screen** status line
("Control is / is not your home app") with the same request button.

### Device admin (new)
`AdminReceiver` + `res/xml/device_admin.xml` request exactly two
policies: **force-lock** and **wipe-data**. Settings gains a **Device
admin** section (status, activate via `ACTION_ADD_DEVICE_ADMIN` with a
plain-language explanation, deactivate, and a **Lock this phone now**
button). Wiring: with admin active, the SMS **LOCK** command and the
in-app Lock button call `DevicePolicyManager.lockNow()` — a real device
lock. Without admin, LOCK keeps the v1 behavior (Control's own UI lock)
and the reply copy states the difference. **WIPE DEVICE** is a new,
separate SMS command with the same two-step pattern as WIPE PROFILE
(`WIPE DEVICE` arms for 10 minutes for that controller number; `<secret>
CONFIRM WIPE DEVICE` executes `wipeData(WIPE_EXTERNAL_STORAGE)`) and it
only acts while admin is active — otherwise it replies that admin is
off and nothing was erased. There is no in-app wipe button. Every
lock/wipe attempt and outcome is written to the shared audit log with
its origin (`UI`/`SMS`); activation state changes are not logged (per
design). In-app copy is explicit: admin is not root and does not affect
the shell bridge.

### E. Wireless-debugging self-pairing / computer-free bridge start
Goal: start the shell bridge with no computer, Shizuku-style. What is
implemented, and where the wall is — no fakery:

- **ADB identity (`AdbKeyStore`)**: an RSA-2048 keypair is generated
  once and persisted in app-private files (`adb-key.pk8/.pub/.cert`),
  so pairing is one-time. The TLS client certificate is a minimal
  self-signed X.509 certificate DER-encoded by hand (Android exposes no
  cert-minting API to apps; ADB only needs the key inside it).
- **Discovery (`AdbMdns`)**: framework `NsdManager` finds
  `_adb-tls-connect._tcp` (restart flow) and `_adb-tls-pairing._tcp`
  (pairing flow). Fully implemented.
- **Connect + shell (`AdbClient`)**: a minimal ADB-over-TLS client —
  TLS with the client certificate above, CNXN handshake, OPEN of the
  legacy `shell:` service, WRTE/OKAY/CLSE framing — that runs **the
  exact starter line** the computer command runs
  (`ShellBridgeClient.shellStarterCommand`). Authorization is at the
  TLS layer: the phone only accepts keys it has paired with, and the
  client reports "not paired" in plain words when that fails.
- **Restart flow (`AdbStarter`)**: Deep view → **Restart deep view (no
  computer)**: ping → discover connect service → ADB shell starter →
  poll until the bridge answers. Start attempts and outcomes are audit
  logged (origin UI). After a reboot the user steps are: ① Developer
  options → Wireless debugging ON (Android requires the toggle; the UI
  says so) → ② Deep view → Restart deep view. No code, no computer —
  *provided the key is paired* (see next point).
- **Pairing — as of v1.1: guided but not completable; COMPLETED in
  v1.2 (see the v1.2 section below).** v1.1 shipped the guided flow
  (Deep view, steps 1–3, code-entry dialog) and real discovery, but
  stopped at the handshake: the assumption then was that pairing
  needed an external-PSK TLS session, which Android's framework TLS
  (JSSE/Conscrypt) cannot do. Re-reading the AOSP sources for v1.2
  showed the protocol is different (mutual-certificate TLS 1.3 first,
  SPAKE2 *inside* it) and buildable with a vendored Bouncy Castle
  stack. **The computer starter remains as fallback**, unchanged.

## v1.2 (2026-11-10) — real pairing, private-channel QR, circle-menu navigation, Connections, Android-style Settings + backgrounds

versionCode 3 / versionName "1.2", same applicationId and signing key
(installs over v1.1). APK **2,945,967 bytes**, SHA-256
`e0e779993669fd2f30b1f7452a4e874f35ba42084fd692270b24cb3ac7e8279f`
(v1.1 was 775,087 bytes — the growth is the vendored Bouncy Castle
stack, heavily compressible in the zip). One new permission:
**READ_PHONE_STATE** (runtime-requested by the Connections screen
for its cellular readouts; everything degrades honestly without it).
Pairing needed no new permissions.

### Pairing — implemented for real (AdbPairing)

Protocol re-derived from the AOSP sources (system/core/adb:
pairing_connection, pairing_auth; external/boringssl spake25519)
after the v1.1 brief's assumed protocol (SPAKE2 → TLS-PSK session)
turned out to be wrong. The real protocol:

1. **TLS 1.3 mutual-certificate handshake FIRST** (Bouncy Castle
   bctls 1.84; the client accepts any server certificate, exactly
   like AOSP's cert-verify callback returning 1; the client presents
   the existing `AdbKeyStore` RSA identity — adbd's connect-side
   check compares only the RSA key in the cert against authorized
   keys, so Control's hand-made cert is acceptable).
2. **Exporter**: 64 bytes via the TLS exporter with label
   `"adb-label\0"` (9 bytes — BoringSSL passes `sizeof` of the C
   string, NUL included). Password = the 6-digit code's UTF-8 bytes
   with those 64 bytes appended.
3. **SPAKE2 (BoringSSL spake25519 over Edwards25519** — implemented
   in `Ed25519.java` + `Spake2.java`; BC has no such variant):
   mask points M/N from spake25519.cc; the password scalar is
   sc_reduce(SHA-512(password)) plus BoringSSL's low-bits "hack";
   the shared key is SHA-512 over uint64-LE-length-prefixed
   client-name, server-name, client-msg, server-msg, DH, and the
   password hash. A wrong code does NOT fail here — it fails at the
   next step, as in AOSP.
4. **PeerInfo exchange** in 6-byte-header packets ([version=1]
   [type][uint32 BE length]; SPAKE2_MSG=0, PEER_INFO=1), encrypted
   with AES-128-GCM keyed by HKDF-SHA256 over the SPAKE2 key
   (info `"adb pairing_auth aes-128-gcm key"`, sequence nonces from
   0). Client sends type 0 with its ANDROID_PUBKEY string
   (`AndroidPubkey.java`: the 524-byte LE blob, base64 + comment);
   the phone replies type 1 with its GUID.

On success: `KEY_ADB_PAIRED` is set, and the EXISTING connect path
takes over (mDNS `_adb-tls-connect` → `AdbClient` shell starter →
bridge polls up). Pair once → bridge starts with no computer.

Two Bouncy Castle rules cost real debugging and are now encoded in
`AdbPairing`: (a) a `Certificate` built with the legacy
`Certificate(TlsCertificate[])` constructor **cannot be encoded
under TLS 1.3** — the TLS 1.3 shape
`Certificate(byte[] requestContext, CertificateEntry[])` is
required, echoing the request's context; (b) the TLS exporter
secret is **only available inside `notifyHandshakeComplete()`** —
after `connect()` returns, BC has nulled it.

**Diagnostics (Deep view → "Pairing log")**: every attempt shows
stage lines — pairing service discovered / Secure session
established / SPAKE2 exchange / Identity accepted / first
connection / bridge started — with the failing stage and a plain
error (a wrong or already-used code is named as such; the phone
hanging up at the identity stage is mapped to that same message,
because that is what a wrong code looks like from the client side).
The code, the SPAKE2 secret, and key material are NEVER logged.
Attempts are audit-logged (origin UI) like other bridge events.

**Adam's steps**: Developer options → Wireless debugging ON →
"Pair device with pairing code" (leave the dialog open) → Control →
Deep view → "Enter pairing code" → type the 6 digits. The stages
run in the pairing log; on success the bridge starts by itself.

**Off-device verification (25/25 checks, JVM harness)**: Ed25519
M/N/base encodings match BoringSSL's published constants; the Java
SPAKE2 (alice) derives the *identical* 64-byte key as an
independent pure-Python bob implementation, and a different key for
a wrong password; the HKDF matches a Python reference; the
ANDROID_PUBKEY blob verifies structurally in Python (524 bytes,
n0inv·n ≡ −1 mod 2³², rr = 2⁴⁰⁹⁶ mod n); and the real
`AdbPairing` core completes the full protocol against a BC mock
adbd on IPv6 loopback — success + GUID + stage sequence, and the
wrong-code run failing at the identity stage with the plain
message. The tests caught three real bugs before shipping: a
corrupted digit tail in the Ed25519 base-point constant, the TLS
1.3 Certificate shape above, and the exporter-callback timing
above. **What only Adam's phone can prove**: BC↔BoringSSL interop
against a real adbd (exporter bytes in the wild, cert acceptance,
the pairing dialog's timing).

### Walkie — private channels, first-class

What v1.1 actually shipped (audit): private slots existed, but
make-private hid behind a long-press on the Channels grid into
ChannelEditActivity; the invite QR was a small dialog; the
scan-join path **overwrote the invited slot number
unconditionally**; nothing existed on the Walkie page itself. Also
found: mesh/Bluetooth frames filter by `slotIdentity`, which
includes the slot number — so a channel moved to another slot
would silently not interoperate.

v1.2: a "Make this channel private" / "Show join code" action and
a "Scan invite" button right on the Walkie page. Invite payload
(JSON, `ChannelInvite`):
`{"app":"control-channel","v":1,"number":N,"label":"…","mode":"private","id":"priv:<n>:<fp8>","key":"<channel key>"}`
(open channels omit `key`). Scanning one (Walkie page, Channels
grid, or the profile Import scanner) joins **by identity, not by
number**: already-have-it → just tune; wanted slot free → placed
there; occupied by a different channel → next free slot with the
original identity kept as the slot's `wireId` (transports filter
on `wireId` when set, so a moved channel still talks to its
group); bank full → a plain refusal. The user is told where the
channel landed.

**Join-QR placement (Adam's spec)**: when the tuned channel is
private, its join QR sits at the **bottom of the radio screen,
centered, ~30% of screen height** (square), pinned below the
scrolling controls, updating as the tuned channel changes; open
channels show no block. Tapping it opens the full-screen invite
(`ChannelInviteActivity`). The QR block is pager *chrome* (swipes
starting there change pages). Its caption says what the
full-screen version says in full: **this code IS the key — anyone
who has it can join; share it like a key.** Transport honesty is
printed on the invite screen: the key gates Wi-Fi mesh, Bluetooth
and LAN; on the internet road the Matrix room is invite-only, but
Control's Matrix client is not end-to-end encrypted and the key
does not gate the room — membership does.

### Navigation — tiny icons + circle menu

Adam's redesign, superseding v1.1's dots + dock: the pager
indicator is a row of **tiny page icons the size of the old dots**
(~12dp glyphs — house/globe/radio/grid, drawn in `PageGlyphs`),
tappable. The Home screen's dock buttons are **removed** (Home =
clock, status lines, favorites; the set-as-home prompt lives in
Settings only). Navigation is the **circle menu**: tap-and-hold
anywhere on launcher chrome and a ring of 8 icons (Home, Browser,
Walkie, Apps, Chat, Activity, Connections, Settings) appears
centered on the touch point, clamped on-screen; drag onto an icon
and release to choose it, or release and tap; outside-tap or Back
dismisses. Arbitration: the pager posts the long-press at **450ms**
(under the framework's 500ms, so the synthetic cancel beats
AbsListView's own long-press — this is what protects app-cell
starring); moving beyond touch slop cancels; surfaces that own
horizontal gestures (WebView, tuner, PTT button, message thread,
composer, search field) never trigger it. Long-press keeps its
local meanings (Apps-cell star, PTT hold-to-talk).

### Connections (new screen; 8th circle-menu icon)

Live status with no polling (receivers + a NetworkCallback). Two
honest tiers, because **Android 10+ forbids normal apps from
toggling radios** — base tier never fakes a switch:

| Row | Base tier | Bridge tier (Deep view on) |
| --- | --- | --- |
| Wi-Fi | state, SSID/IP/link speed (SSID needs location permission — the screen says so and offers the grant), scan + suggestion-connect, Wi-Fi panel | real switch (`svc wifi`) |
| Bluetooth | state, bonded list, system enable request, discovery + tap-to-pair | real switch (`svc bluetooth`) |
| Cellular | carrier, network type, signal, roaming, data state (read-only) + Internet panel / settings links | mobile-data switch (`svc data`) |
| Airplane mode | state + system settings | switch, **tier-2 press-and-hold confirm** (`cmd connectivity airplane-mode`, fallback `settings put` + broadcast) — copy warns it drops every radio including the walkie's paths |
| NFC | state + settings (or "no NFC radio") | real switch (`svc nfc`) |
| Location | state + settings | — (no bridge switch offered; the system screen is the switch, and the screen says so) |
| VPN & filter | filter VPN state + Private DNS mode read; controls deep-link to the existing filter settings | — |

The five radio ops are new governed `ShellBridgeMain` ops
(VERSION 3): named ops only, fixed argv (`/system/bin/svc …`),
confirmed in-app, audit-logged, and the rows revert to base tier
live when the bridge drops.

### Settings — Android-style hub + Background

Settings is now a hub in Android's shape (dark theme kept): a
pinned "Search settings" field that live-filters rows, a profile
header card (display name · ADB key fingerprint short form ·
chat account state), grouped cards of icon rows ≥56dp with a
**one-line live summary** each. True on/off settings are switches
*on their rows* (show clock, quiet-all, ad filter, SMS control,
JavaScript); everything else opens the sub-screen that keeps the
full controls — nothing from the old screen was deleted:
filter detail + Private DNS (`SettingsFilterActivity`), SMS setup
(`SettingsSmsActivity`), device admin (`SettingsAdminActivity`),
profile & backup (`SettingsProfileActivity`), About
(`SettingsAboutActivity`), plus rows into Connections, Background,
Deep view, governed controls, Activity, Chat, Walkie, and the
default-home role request.

**Background** (Home screen group → Background): solid colors (8
dark swatches), built-in gradients (6, rendered in code — no image
assets), or a **photo** (document picker; copied into app-private
storage downscaled to ≤1600px). A dim slider (scrim 0–70%) keeps
text readable; the screen previews clock + a favorites row over
the real background layer as you adjust. It renders as a shared
layer behind the pager (`BackgroundView`); page roots are
transparent over it — the WebView keeps its own surface. The
choice {type, value, scrim} lives in profile.json and travels
with it for colors/gradients; **a photo is device-local** — the
profile records only the choice + dim (the Matrix-token rule), so
an imported "photo" profile on a phone with no photo falls back
to the default color and the summaries say so.

### Build change — dexer

Build-tools 34's d8 (R8 8.2.2-dev) NPEs on three Bouncy Castle
classes (see build-apk.sh). `build-apk.sh` now dexes with **R8
8.7.18** (`~/workspace/native-build/r8/r8-8.7.18.jar`, from
maven.google.com); aapt2/zipalign/apksigner remain build-tools 34.
The BC jars ship whole (multi-release entries + jar signatures
stripped for dexing) — the same bytes the off-device tests ran
against.

## v1.3 (2026-11-10) — Control Relay replaces Matrix, space theme, spatial layout, bridge start hardening

APK: 2,954,159 B, sha256 `6005437b9e2783c38bf6eb9b1e9f821efcb36fcf9a0cf524faf80aa3b880b25d`,
versionCode 4 / versionName "1.3", same release key (cert SHA-256
f646377d…) — installs over v1.2.

### Relay client (PROTOCOL.md v1, followed exactly)

New pure-Java core (no Android, no org.json — so the off-device JVM
harness runs the exact shipping code): `RelayKeys` (Ed25519 via
Bouncy Castle's lightweight API; base64url-no-pad encodings; §5
fingerprints), `RelayCrypto` (AES-256-GCM, 12-byte nonce,
nonce‖ct‖tag, base64 std — §6), `RelayJson` (a ~200-line response
reader; android.jar's org.json stubs throw on a plain JVM),
`RelayCore` (signed requests: `METHOD\nPATH\nBODY-HASH\nTIMESTAMP`,
ms timestamps, the ENCODED path signed; register / list / addMember
/ post / pull), `RelayIds` (channel-id derivation). Android glue:
`RelayClient` (background pool, main-thread callbacks, per-channel
cursors persisted as `relay_seq:<channelId>` prefs, throttled
idempotent registration).

- **Identity**: an Ed25519 keypair is generated on first profile
  load and stored in a new profile `"relay"` section {seed (base64
  std), pub (base64url), cardDmKey}. **The key IS the account** —
  the deliberate opposite of the old Matrix-token rule: it is
  INCLUDED in the encrypted profile export, because there is no
  re-login. The moment a fresh seed is generated it is persisted
  (Profile.load saves after normalize) so a process death can't
  strand the identity. Profile copy (Settings → Profile & backup,
  Transfer, Import) says it verbatim: "Your identity key travels
  only inside the encrypted profile file; anyone holding it is you."
  Old profiles without a key import fine — normalize mints a fresh
  one; identity-dependent memberships simply start empty.
- **Chat**: ChatActivity is a conversation list built from what the
  phone can open — DMs from contacts carrying relayKey+dmKey, group
  chats whose keys live in the profile (`"chats"`), and relay
  channels the key is a member of but can't decrypt, shown honestly
  as locked. The header reads "You are your profile key · <16-hex
  fingerprint>". RoomActivity is the thread: ~3s polling while open,
  persisted cursor, UUID clientMsgIds, local echo deduped by
  clientMsgId, §6 JSON text envelope. Labeling is exact: "Sealed
  with this conversation's channel key (AES-256-GCM). The relay
  stores ciphertext only… it is not Signal-grade end-to-end
  encryption." No homeserver, login, or password anywhere. New-chat
  flow member-adds selected contacts over the relay; the chat key
  is shareable via a `control-chat` QR (and receivable by scan in
  Chat / Contacts / Import); a key-holder who isn't a member gets a
  plain "the person who shared the key must add your profile key"
  status with the fingerprint to give them.
- **Walkie internet path**: bursts + texts ride relay channels
  (`open:<n>` / `priv:<n>:<fp>`). Burst plaintext is the raw
  IMA-ADPCM bytes exactly (§6), sealed on private channels, plain
  base64 on open ones. Received bursts store their blob on the
  thread entry (`"blob"`) so playback survives restarts. Private
  membership = signed member-adds fired on every channel bind for
  checked members carrying relay keys (the v1.2 QR join flow is
  unchanged — it carries the key; the adds happen over the relay
  when online). Mesh / LAN / Bluetooth are untouched.
- **Contacts**: contacts gain `relayKey` + `dmKey`. The contact
  exchange the protocol §5.1 presumes did not exist as a payload,
  so v1.3 defines it: `{"app":"control-contact","v":2,"name","pub",
  "dm"}`. Two card forms: the profile card (Contacts → "My contact
  card", carries the profile's cardDmKey) and the per-contact
  exchange card (ContactEdit → "Exchange card for <name>", carries
  the DM key stored for that contact, generated on first use).
  **Convergence rule (protocol is silent): first card wins** — a
  DM key already on file is never overwritten, so whichever side's
  card is scanned second carries the already-agreed key back; a
  phone also adopts DM channels keyed by its OWN card key when
  they appear in its relay channel list. Legacy v1-shaped cards
  (name + matrixId, no relay key) still import; the contact then
  shows "No relay key yet — re-exchange a contact card to chat"
  (Contacts list + contact screen).

### Removed: Matrix

`MatrixClient.java` deleted; the login UI, homeserver fields, room
list/sync, media upload/download, and the `KEY_MX_*` prefs are
gone. Migration is graceful: a stored `"matrix"` profile section
is carried along unread (displayName still falls back to it), old
slots' `roomId`/`members` values are ignored (member values that
aren't 43-char base64url keys are skipped by the relay sync), and
pre-v1.3 thread entries with Matrix `mxc` refs explain themselves
when tapped ("rode the old Matrix internet path — can't be fetched
now"). Remaining "Matrix" strings in the tree are exactly those
migration notes.

### Protocol ambiguities found while implementing

1. **Two fingerprints exist.** The protocol fingerprint (§5) is
   SHA-256 over the RAW KEY BYTES, first 8 bytes / 16 hex. The
   app's pre-existing local slot identity fingerprints the key's
   BASE64 STRING, first 4 bytes / 8 hex, and keys mesh/BT/LAN
   sessions and the on-device thread store. Relay ids follow the
   protocol (RelayIds is the only place they're derived); local
   identity is unchanged — so one private channel legitimately has
   two different-looking ids, one per world.
2. **§5.1's DM-key convergence is unspecified** for the case where
   both sides generate before scanning (and the "existing contact
   exchange" it references didn't exist). Resolved with the
   contact-card payload + first-card-wins rule above.
3. **§6 doesn't type walkie-channel payloads**: bursts are defined
   (raw ADPCM) but channel TEXT has no specified form, and a
   receiver must distinguish them in-band. Chosen: bursts stay raw
   per §6; texts are a tiny JSON envelope `{"t":"text","text":…}`
   inside the sealed/plaintext payload; anything that doesn't
   parse as that envelope is treated as a burst.
4. A slot moved by an invite keeps its original number in its
   wireId; the relay id is derived from THAT number (RelayIds
   .wireNumber), so both sides address the same relay channel.

### Theme — the app wears the Space's phone mockup

Tokens extracted from `~/workspace/ts-spaces/control-launcher`
(the phone mockup, not the warm-paper page around it) and applied
through `Ui.*` + targeted screens:

| Space token | Value | App token / use |
|---|---|---|
| `--phone-bg` | #101613 | `Ui.BG` |
| webpage/tuner panel | #141A16 | `Ui.PANEL` (tuner band, readout, thread rows) |
| `--phone-surface` (cards, urlbar) | #171E1A | `Ui.SURFACE` |
| (derived raised tier) | #1E2A21 | `Ui.SURFACE2` |
| `--phone-ink` | #E8EDE9 | `Ui.TEXT` |
| `--phone-soft` | #93A399 | `Ui.DIM` |
| `--phone-accent` | #4ADE80 | `Ui.ACCENT` |
| on-accent ink | #0C120E | `Ui.ACCENT_INK` (accent buttons, circle-menu hot ink) |
| `--phone-line` | #2A332D | `Ui.LINE` |
| `--signal` (dark) | #FBBF24 | `Ui.SIGNAL` (tuner needle, warning lines) |
| hero pine / pine-deep | #14532D / #0C3520 | `Ui.PINE` / `Ui.PINE_DEEP` |
| PTT bg / border | #1A2B20 / #31463A | `Ui.PTT_BG` / `Ui.PTT_LINE` |
| dot ring | #33503F | `Ui.DOT_LINE` (sheet drag handle) |
| Archivo 500–800 | — | system sans, bold for display (`Ui.display`: clock 64sp, walkie big number), −0.02 letterspacing |
| IBM Plex Mono 400–600 | — | system monospace (`Ui.mono`): section kickers (12sp, ls .16, accent), dates, status lines, freq readout, chips/buttons, identity lines, circle-menu labels |
| radii: cards 14–16, buttons 9, pills 999 | — | `Ui.rounded/roundedStroke` everywhere shared widgets are built; Settings group cards + profile header 16dp; URL bar + settings search + sheet search are pills |

Mirrored treatments: Home = huge display clock, mono date/status,
soft 11sp favorite labels; Browser = pill mono URL bar on the page
background + reader CSS recolored to the same hexes; Walkie =
panel readout with mono accent frequency, amber needle, and the
mockup's round PTT (dark disc + ring, held = accent fill + dark
ink); thread/conversation rows are 12–14dp rounded cards.

### Spatial layout

- Pager is THREE pages **[Browser, Home, Walkie]** (constants
  renumbered: BROWSER 0, HOME 1, WALKIE 2); the app starts on Home
  via the pager's pending-page settle; Back from Browser/Walkie
  returns Home; VIEW intents still land on Browser (now index 0).
- **Apps is a bottom sheet** (`AppsSheetView`, full-screen, stage
  overlay between pager and circle menu): raised by (a) swipe-up
  starting in the bottom ~24dp strip of ANY page — PagerLayout
  claims the gesture only once it's clearly an upward drag, so
  strip taps (the Walkie QR block) and the chrome long-press are
  untouched, mirroring the side strips' pager rule; (b) circle
  menu Apps; (c) the Apps tiny icon, which now carries an up-caret
  and lights while the sheet is raised. Lowered by dragging its
  header down, Back (handled before the circle menu / page Back),
  or launching an app. Its list scrolls normally once raised and
  its search field is unchanged. DrawerActivity now forwards with
  an EXTRA_APPS sheet raise instead of a page index.

### Bridge start hardening (field report: v1.2 on Android 16)

Adam's phone, 2026-11-10: **pairing SUCCEEDED against real adbd**
(the SPAKE2/TLS interop is proven on hardware), but the app then
reported the bridge not connected — the failure sat somewhere
between the ADB connect, the starter line, and the bridge's first
answer, and v1.2 couldn't say where. Changes:

1. **Staged start, distinctly logged** (AdbStarter.restartBridge):
   stage 1 DISCOVER → stage 2 CONNECT+STARTER → stage 3
   BRIDGE-ANSWER, each with its own on-screen status (the Deep
   view's log is now "Pairing / start log" and Restart appends to
   it too) and its own audit line naming the failed stage.
2. **Fresh discovery, always**: every start attempt re-discovers
   `_adb-tls-connect` from scratch (a post-pairing start never
   reuses a pre-pairing result), and a connect that fails outright
   earns ONE re-discovery + retry, since ports rotate when
   wireless debugging restarts.
3. **~10s answer poll**: stage 3 polls once a second for 10s
   (was 7 × 1.5s) before declaring failure, for Android 16
   spin-up lag.
4. **Diagnose action** (Deep view, next to Restart/Pair): when the
   bridge is down but an ADB shell channel opens, it runs
   READ-ONLY checks — `getprop` Android version, `tail -n 60
   /data/local/tmp/control-bridge.log`, `ps -A` greped for
   app_process/ShellBridgeMain, and `pm path` (the classpath
   source) — and shows the output VERBATIM in the screen's output
   block so it can be read back or screenshotted. The run is
   audit-logged (`BRIDGE DIAGNOSE (ADB)`).
5. **Starter line hardened** (ShellBridgeClient
   .shellStarterCommand; the named changes, per the brief's
   diagnostics-first rule):
   - *Classpath*: built from ALL `pm path` lines joined with ':'
     (`sed 's/^package://' | tr '\n' ':'`, trailing colon trimmed).
     The old `cut -d: -f2` left newline-joined paths in a single
     variable on split installs — a classpath app_process cannot
     load, i.e. the process dies before writing anything.
   - *Detach completed*: `nohup` added alongside the existing
     `setsid` + full fd redirection (`>log 2>&1 </dev/null &`), so
     the bridge survives the launching ADB shell session closing —
     the in-app client always launches it that way.
   Both changes are behavior-preserving for the single-APK case
   that already worked from a computer.

**Verification (off-device)**: relay harness 28/28 against the
LIVE relay (see `harness/RelayHarness.java`: registration +
idempotent re-register, private chat create/add/round-trip with
exact plaintext recovery, duplicate clientMsgId, channel-list
membership, non-member 403s on pull/post/add, open-channel post +
400 on member-add, tampered signature 401, stale timestamp 401).
`./build-apk.sh` clean. Still unproven until Adam's phone: relay
chat/walkie on a real network, the theme + sheet gestures, and
whether Diagnose's log tail names the actual stage-3 killer.

## v1.4 (2026-11-10) — one TLS stack for pairing AND connect

APK: 2,954,159 B, sha256 `88d90a9847d3457f8fb1464922adfbb581d76df2d669190012579e40fa1e98b7`,
versionCode 5 / versionName "1.4", same release key (cert SHA-256
f646377d…) — installs over v1.3. No other changes: no UI, relay,
walkie, or starter-command changes.

**Field report that drove it** (Adam, Galaxy A16 / Android 16,
v1.3): pairing succeeds, but bridge start fails at stage 2 with
AdbClient's "Could not connect over TLS (…)" — the catch-block
message when the TLS handshake throws BEFORE the ADB CNXN
exchange. Diagnosis (from the code, before this build): discovery
was never the problem (AdbMdns resolves `_adb-tls-connect`, and
AdbStarter already re-discovers fresh with one retry). The two ADB
paths presented the SAME hand-DER certificate bytes
(`AdbKeyStore.certificate`) through two different TLS stacks:
pairing wraps them in Bouncy Castle's TlsClientProtocol and
SUCCEEDS against his adbd (proven on-device in v1.2); the connect
path used the framework SSLContext (Conscrypt) via
`AdbKeyStore.clientContext` — and that handshake is the one that
dies. Two stacks, two behaviors; v1.4 eliminates the second stack.

**What changed**

1. **New shared scaffolding — `AdbTls.java`**: the BC TLS 1.3
   client that used to live privately inside AdbPairing (TLS 1.3
   only, mutual certs, peer cert accepted as-is, TLS 1.3
   Certificate shape echoing the request context,
   rsa_pss_rsae_sha256 signer, exporter captured inside
   notifyHandshakeComplete) is now a package-private helper both
   paths call: `AdbTls.connect(keyPair, certDer, host, port,
   connectTimeoutMs, soTimeoutMs)` → a Connection exposing the
   streams, the exporter secret, and close(). Pairing's
   PairingTlsClient class is deleted; `AdbPairing.pair` opens its
   session through AdbTls with its original timeouts (10 s
   connect / 20 s socket) and is otherwise untouched — protocol
   bytes identical (the regression leg below proves it). The
   shared client also records the peer cert fingerprint into
   `AdbKeyStore.lastPeerFingerprint`, as the old framework trust
   manager did.
2. **`AdbClient` runs on AdbTls**: `runShell` keeps its public
   Context signature, loads the stored identity, and delegates to
   a new core overload `runShell(KeyPair, byte[] certDer, …)` —
   the same two-overload shape as AdbPairing, so the harness
   drives the exact shipping code. The transport is
   `AdbTls.connect(…, timeoutMs, 8000)` (the old connect timeout
   and 8 s socket timeout preserved); the ADB wire protocol —
   CNXN/AUTH/OPEN/WRTE/CLSE handling, framing, deadlines — is
   byte-for-byte the v1.1 code. `AdbKeyStore.clientContext` is
   removed (AdbClient was its only caller), along with the
   framework trust manager it carried.
3. **Error fidelity at stage 2**: a handshake failure now carries
   the BC exception's class + message verbatim
   (`<class>: <message>`, capped at 320 chars) in every case. When
   the failure is certificate-flavored — a fatal alert in the
   certificate family (bad/unsupported/revoked/expired/unknown
   certificate, certificate_required, unknown_ca, access_denied,
   decrypt_error, handshake_failure), received or raised — the
   summary leads with the reading and the remedy instead of the
   generic "usual cause": *"The phone refused Control's key
   during the TLS handshake (…). The phone no longer trusts
   Control's key — pair again from Deep view (Enter pairing
   code), and the bridge start will use the fresh pairing."* The
   abrupt-close presentation of the same rejection (TLS completes
   on our side, then the phone hangs up during the CNXN exchange —
   EOF / socket reset / no close_notify; BoringSSL commonly just
   drops a connection whose client key it won't authorize, the
   alert racing our first write) is classified the same way. A
   refused connection (ConnectException — nothing listening) is
   explicitly NOT: that's the service being off, and it keeps the
   generic wording.

**Verification (off-device)**: new `harness/AdbConnectHarness.java`
— a mock adbd (BC TLS 1.3 server, self-signed cert, client-cert
request) on IPv6 loopback; **15/15 checks, twice in a row**:
the shipping AdbClient completes mutual-TLS + CNXN + a shell echo
round trip, and the mock asserts it received the exact command; a
server that answers the client cert with a fatal
certificate_unknown alert yields not-connected + the verbatim
exception class + the pair-again wording; a dead port yields the
generic wording with ConnectException verbatim and NO pair-again
wording; and the pairing regression — the shipping AdbPairing
completes the full protocol against the mock pairing service
(guid round trip) while a wrong code still fails at the identity
stage. (The v1.2 pairing harness source did not survive in the
tree; this harness restores that coverage and adds the connect
legs.) Existing suite unaffected: **RelayHarness 28/28** against
the live relay. `./build-apk.sh` clean (R8 dexing); badging
versionCode 5 / "1.4"; apksigner verifies (v2+v3) with the v1
certificate. **Still unproven until Adam's phone**: whether the
BC connect handshake completes against his real adbd — that is
precisely the on-device question v1.3 left open, and if it still
fails, the stage-2 line now names the exact BC exception.

## v1.19 (2026-11-16) — channel subscriptions: the walkie keeps listening over the internet relay

Adam's order (2026-11-15): "allow subscriptions to channels so anytime a
message is sent, it will receive it" — with the scope he picked the same
night: INTERNET RELAY ONLY. The mesh and Bluetooth roads stay
single-channel, tuned-channel-only, and are untouched by this build.

### What a subscription is
- A per-slot choice: the phone keeps polling that channel over the
  Control relay even when tuned elsewhere, and messages arrive in
  the channel's thread and are read aloud exactly as if the phone
  were tuned there. Subscribing follows the slot NUMBER, not the
  channel identity — reconfiguring a slot (new key, new label)
  keeps the subscription, and the relay channel id is re-derived
  from the current slot config on every poll cycle.
- Storage: subscriptions live in the portable profile (a top-level
  `"subscriptions"` array of slot numbers in profile.json), next to
  the channel bank. They are the user's channel choices, like
  favorites, so they travel with the encrypted export/import, and
  a restored phone resumes listening to the same channels. The
  relay pull cursors stay device-local prefs, as before.
- Controls: a "Listen to channel N in the background" switch on the
  walkie page (in a "Listen in the background" section under the
  internet section, with the cost stated in the copy) and the same
  switch in the channel editor (applies immediately, not on Save).
  A subscribed channel is marked "● listening" in the channel
  grid, and channels with unplayed received messages show their
  count ("3 new"); the walkie page's "All channels" button carries
  the total unplayed count for OTHER channels, so a background
  arrival is findable without opening the grid.
- Private channels: subscribing runs the same member-add sync the
  tuned-channel path runs (RelayMembership, factored out of
  WalkiePage so both paths share it). A channel the relay refuses
  (not_a_member, channel_not_found) is skipped for 10 cycles and
  retried, and the subscription section names the blocker honestly
  ("The relay says this phone isn't a member of this private
  channel…") instead of claiming to listen.

### The background receiver
- `SubscriptionService`: a specialUse foreground service (subtype
  `channel-subscriptions`, persistent notification naming the
  subscribed count, tap opens the walkie page). A foreground
  service is the lightest mechanism that actually keeps polling
  every few seconds — WorkManager's 15-minute minimum and exact
  alarms are neither allowed nor honest about battery. No
  wakelocks between polls; the worker thread waits. The service
  stops itself when the last subscription is removed, and
  BootReceiver reconciles it after a reboot, so listening
  resumes with no user action. The existing
  FOREGROUND_SERVICE_SPECIAL_USE permission covers it — no new
  permission (26 total).
- Poll schedule (SubscriptionPolicy, pure Java, harness-proven):
  round-robin, one request per step, spacing max(1s, ceil(4s/n)).
  One subscription polls every 4s (the tuned channel's cadence);
  the request rate NEVER exceeds 60/minute — a 10x margin under
  the relay's 600/minute limit at any subscription count. More
  subscriptions means each channel is checked less often; the UI
  copy says so. A 429 refusal pauses the whole poller for a
  minute (a backstop that should be unreachable). Offline waits
  quietly with backoff (15s → 30s → 60s cap), no toasts, no
  spam; cursors persist, so reconnecting resumes without loss
  inside the relay's 30-day retention.
- PRIME STEP: a channel polled for the first time (cursor 0) is
  fast-forwarded to live — the service pulls through retained
  history WITHOUT ingesting (bounded: 5 pulls of 500), so
  subscribing listens from now on instead of flooding the thread
  and the speaker with up to 30 days of old messages. A channel
  with an existing cursor resumes from the cursor.
- SINGLE-POLLER RULE: the service owns polling for every
  subscribed channel, INCLUDING the tuned one. The walkie page's
  loop polls the tuned channel only while it is NOT subscribed,
  or while the service is not alive (the backstop — Samsung may
  kill the service; the page reconciles it on coming forward).
  Both pollers share the persisted per-channel cursors, and both
  check the store-backed cmid dedup before appending, so a
  message pulled twice — by two pollers, across a restart, or
  after a retune — is stored exactly once.
- THE SAME PIPELINE: background arrivals go through the shared
  RelayIngest decode + entry-shaping (factored out of the page's
  ingestRelay, which now delegates to it), land unplayed in the
  channel's WalkieStore thread, are broadcast (app-scoped) so an
  open walkie page reloads the tuned thread, are read aloud by
  the same LowDataSpeaker / SpeakGate path (the read-aloud
  switch, QUIET absolute, the sender's voice profile), and move
  to History when the utterance finishes (the same HistoryRule
  mark). Relay bursts land unplayed and are played by tapping,
  never auto-played from the background — as on the tuned path.
  (Two TTS instances — page and service — can overlap only on a
  handoff race; both utterances are short, and the single-poller
  rule keeps it rare.)

### Verification
- `SubscriptionHarness` (new): 75 checks — set normalization and
  toggling, the schedule bounds (rate ≤ 60/min, period ≥ 4s,
  spacing ≥ 1s, for 0..60 subscriptions), offline backoff,
  blocked-channel retry + error classification, decode
  round-trips (sealed/open/burst/wrong-key/tampered/malformed),
  store-backed dedup, and entry stamping. It caught one real
  bug: plain integer division gave n=3 a 3999ms revisit period,
  under the 4s floor — fixed with ceiling division.
- All 14 prior harnesses re-run on the new tree at identical
  counts (fresh compiles; Relay 28/28 against the live relay).
  RECEIVER_NOT_EXPORTED intact in ConnectionsActivity; zero
  AccessibilityService references in source, manifest,
  resources, and the dex; the new service declaration +
  subtype verified in the compiled manifest.
- No on-device test — background reception while the app is
  closed, read-aloud from the background, multi-channel
  polling, battery/data behavior, and the offline → reconnect
  resume on Adam's A16 are his tests; two-phone relay tests
  need a second device.

## v1.18 (2026-11-16) — played walkie messages move to History

Adam's order (2026-11-15): "After the message is played, it should be
removed from the list and added to a history list." The channel thread now
shows UNPLAYED messages only; once a received message has been played, it
leaves the thread and appears in that channel's History list, replayable
from there. Walkie channels only — the Chat page is untouched.

### The played/history rules, as shipped
- **The move is a state change, not a delete + recreate.** Each thread
  entry carries a `"played"` flag, persisted with the entry in
  `walkie.json` (new entries write `"played": false`; pre-v1.18 entries
  lack the key and read as unplayed — the same rule, by default).
  `HistoryRule` (pure Java, over the store's own entry shape) owns the
  rule: thread = own entries + unplayed received entries; History =
  played received entries only. The split is a partition — nothing is
  dropped, nothing duplicates — and marking is idempotent.
- **Only a COMPLETED playback marks an entry.** Texts: the TTS
  utterance's `onDone` (wired through a new `UtteranceCallbacks`
  registry in `LowDataSpeaker` — one callback per utterance id, run
  exactly once on the main thread). Bursts: the tap-playback thread
  reaching the end of the stored burst. A replay that runs to
  completion counts too, whether tapped in the thread or in History
  (a History replay moves nothing — the entry is already played).
- **QUIET-blocked, switch-off, engine-less, failed, or interrupted
  playbacks never mark.** The gate returns before any utterance
  exists; `onError` drops the callback; `stop()`/`shutdown()` clear
  all pending callbacks without running any. Those messages stay in
  the thread — the honest consequence, since they haven't been
  heard. The thread's empty hint ("Nothing unplayed here. Messages
  you have heard are in History.") only shows when the thread is
  genuinely empty, so it never claims an unheard message was heard.
- **Own messages never move.** Own sent texts and own bursts live in
  the thread today (mine entries) and stay there permanently:
  playback never marks them (the guard is in `HistoryRule` and again
  at the burst-play call site), and own texts never speak at all.
  This is the settled reading of the store's semantics: the user
  never "receives" their own messages.
- **Live mesh/Bluetooth bursts have no entry to move.** A burst
  arriving live over mesh or Bluetooth streams straight to the
  speaker and leaves no thread record (unchanged since v1.0); the
  played → history rule applies to STORED burst entries (relay
  bursts, own bursts) played by tapping. The live `onSpurtEnd`
  path is untouched.
- **History UI:** a "History (N)" button above the thread opens a
  dialog for the TUNED channel, rebuilt from the store on every
  open. History rows render exactly like thread rows (sender, time,
  low-data marker, content) with a "hear it again" tap hint; tap
  replays (text aloud / burst audio), long-press shows the same
  sender/time/road/state detail as the thread. History stays in
  time order even when playbacks complete out of order (sorted
  insert on the move).
- **Retune correctness:** `bindChannel` now seeds `seenCmids` from
  the WHOLE store, not just the thread — a played relay message's
  cmid must still suppress re-ingest after a retune, or it would
  come back as a duplicate.
- **Cross-channel safety:** a TTS completion that fires after a
  retune persists the flag on the entry (the live store object)
  but moves no list — the split picks it up when that channel is
  tuned again. Both in-memory lists hold the same live entry
  objects as the store and are never reassigned, so the History
  dialog's adapter stays consistent across reloads.
- **Unchanged:** the read-aloud paths, QUIET, the switch, the
  low-data marker, burst auto-play, the bridge/watchdog/overlay,
  subscriptions (v1.19, not built), and the walkie page layout
  (the no-scroll redesign is v1.20, queued — not done here). No new
  permissions (26 total).

### Verification
- `HistoryHarness` (new): 78 checks — the flag default, the full
  mine × played membership matrix, mark transitions (incl.
  idempotence and the split before/after a mark), split
  losslessness/duplicates/order, and the utterance-callback
  exactly-once / never-on-error / never-on-interrupt semantics.
- All 13 prior harnesses re-run on the new tree at identical
  counts. No on-device test — the move on real playback,
  persistence across restarts, History replay, and the
  QUIET-stays-unplayed behavior on Adam's A16 are his tests.

## v1.17 (2026-11-16) — the walkie-talkie reads every received message aloud

Adam's order (2026-11-15): "The walkie talkie should play the audio anytime
it receives a message." Voice bursts already auto-played on receipt; text
messages were silent unless they carried the low-data marker. Now every
received walkie text is read aloud in the sender's voice profile, on all
three roads, through the SAME single speech path v1.11 built
(`LowDataSpeaker` / `SpeakGate` / `VoiceProfile`) — no second TTS path.

### The speak decision, as shipped
- **All three receive paths speak**: the mesh/LAN `onText`, the Bluetooth
  `onText`, and the relay `ingestRelay` poll. Each calls the same
  `maybeSpeak(from, senderPub, text)`, which resolves the sender's voice
  profile by relay pubkey on the relay road and by display name on
  mesh/Bluetooth, exactly as v1.11 did for low-data texts.
- **Own messages never speak.** The mesh/Bluetooth listeners never carry
  own traffic (the host's `relay()` excludes the originator connection,
  and own sends write directly to peers while appending a local `mine`
  entry — nothing loops back through the listener). The relay path keeps
  its exact pubkey `!mine` guard. Tap-to-replay likewise excludes `mine`
  entries.
- **QUIET stays absolute**: `SpeakGate` decides every utterance with live
  pref values; QUIET-all silences automatic reads, manual replays, and
  voice previews, with no exception path — unchanged.
- **One switch, default ON**: the read-aloud switch moved out of the
  low-data section (where it was visible only while low-data mode was on)
  into its own always-visible "Read aloud" section, relabeled "Read
  walkie messages aloud". It still defaults ON, and the stored pref key
  (`KEY_LOWDATA_AUTOREAD`) is deliberately unchanged so a user who
  already turned it off keeps that choice; the key name predates the
  wider role. Copy stays honest: synthetic voice profiles, never cloning.
- **Tap-to-replay extends to all received texts** (was low-data-only):
  tapping any received text reads it aloud in the sender's profile,
  bypassing the switch but never QUIET, with the same QUIET / no-engine
  toasts. The thread row hint ("Tap to hear it read aloud…") now shows
  on every received text entry.
- **Unchanged**: the low-data marker and its "low-data" thread label;
  voice-burst auto-play; the Chat page (out of scope — walkie channels
  only); no new permissions (26 total).

### Code changes
- `SpeakGate.decide(autoReadOn, quietAll, manual)` — the `lowData`
  parameter and the `SKIP_NOT_LOWDATA` decision are gone; the gate's only
  inputs are the switch, QUIET, and manual. `LowDataSpeaker.speak(c,
  text, profile, manual)` drops its `lowData` parameter the same way;
  its `NOT_LOWDATA` outcome becomes `EMPTY` (null/blank text — the only
  remaining use). `preview()` is unchanged in signature.
- `WalkiePage`: the three `if (lowData)` speak guards removed; the
  low-data section loses the switch; new `readAloudSection()`; replay
  renamed `replayLowData` → `replayEntry` and extended to all received
  texts.
- `VoiceProfileActivity`: the no-engine line now says "walkie messages"
  instead of "low-data messages".
- `LowDataHarness` gate leg rewritten for the 3-argument gate (8 checks:
  auto speak/mute, QUIET over all switch×manual combinations, manual
  bypasses the switch). Harness total moves 131 → 124; the other legs
  are untouched.

Verified: all 13 harnesses green (counts in the report); `apksigner
verify` passes with the same signing certificate; badging shows
versionCode **21** / versionName **1.17**; 26 permissions, unchanged.
Still **no on-device test** — read-aloud on receipt across all three
roads, QUIET silencing, tap-to-replay, and voice-profile behavior on
Adam's A16 are his tests.

## v1.16 (2026-11-16) — bridge auto-restart watchdog: the bridge comes back by itself

Adam (2026-11-15): "Build the auto-restart." APK: 20,636,985 B,
sha256
`a1129cbb16afb12a6d7e4b0e078af0ed71ec2c0542b9954c32007c9e3d470c8e`,
versionCode 20 / "1.16", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.15.1.
ONE new permission: RECEIVE_BOOT_COMPLETED (normal-level, 26
total) — the boot hook that starts the watch after a reboot.

### What it is, and what it cannot be

The bridge is a separate `app_process` started through
wireless debugging, and it never survives a reboot. Adam asked
whether it could be built into a background process. It cannot
— not with the same powers: anything the app starts itself
runs as the app UID and can never hold shell powers. Android
offers no self-promotion; the only other routes are root or a
system signature, neither of which is on the table (stated to
Adam; he picked the watchdog instead).

So the watchdog does not BE the bridge. It watches the bridge
and re-runs the existing in-app ADB restart whenever the
bridge dies or goes stale. Pair once, and the app keeps the
bridge up by itself while wireless debugging stays on.

### How it works

- `BridgeWatchdogService`: a specialUse foreground service
  (subtype `bridge-watchdog`, persistent notification,
  START_STICKY) with one worker thread. No wakelocks are held
  between pings — the thread waits on a lock. The notification
  opens Deep view.
- The worker pings every 5 minutes (`PING_INTERVAL_MS`), plus
  on screen unlock (`ACTION_USER_PRESENT`, registered
  dynamically with the same API-33 RECEIVER_NOT_EXPORTED guard
  as ConnectionsActivity), plus on Deep view / Settings
  foreground (both call `kick()`), plus at boot (below).
- One ping, three outcomes, via the v1.15.1 `BridgeGate`:
  CURRENT earns nothing but the next ping; NOT_RUNNING earns
  `AdbStarter.restartBridge` (the dead case); STALE earns
  `AdbStarter.restartStaleBridge` (stop-then-start). No
  parallel restart logic was built — the watchdog reuses both
  paths, including their audit logging. A restart already in
  flight earns nothing; the worker blocks on the restart
  callback (120 s safety timeout) before scheduling the next
  check.
- Gating: the service runs ONLY while Deep view is enabled —
  new pref `KEY_DEEP_VIEW_ON`, set when a bridge start
  succeeds (all three AdbStarter success paths) or Deep view
  finds the bridge up (which also covers the computer-starter
  path), cleared by Settings' "Turn off deep control" (which
  also stops the service through `reconcile`). Default false:
  a fresh install never auto-restarts anything, and the
  watchdog never restarts the bridge behind an explicit
  turn-off.
- Backoff (`WatchdogPolicy`, pure Java, harness-tested):
  failed restarts wait 30s, 1m, 2m, 5m, 10m, then NO scheduled
  retry — only an unlock or a foreground visit tries again.
  No prompt spam, no repeated failure toasts.
- The one blocker worth naming is wireless debugging being
  off: the restart's discovery stage says so verbatim ("No
  wireless-debugging service found"), `classifyFailure`
  matches that exact string, and the Settings row repeats it
  plainly ("Waiting — wireless debugging is off. Turn it on
  and the bridge restarts itself."). Every other failure keeps
  its real reason as the blocker text. The row never claims
  the watch is running when Deep view is off.
- Boot: `BootReceiver` (manifest, exported, BOOT_COMPLETED)
  starts the service when Deep view is enabled. It is not
  direct-boot aware, so it runs after unlock, when prefs and
  keys are available. If the foreground-service start is
  refused in that state, the failure is silent by design —
  the unlock/foreground kicks are the backstop.
- Audit: every restart attempt and its outcome is written to
  OwnLog (area "Bridge watchdog") with the real reason on
  failure — one line per attempt, not a duplicate of
  AdbStarter's stage log.
- Battery, honestly: the cost is a foreground service plus a
  socket ping every five minutes. Samsung battery management
  may still kill the service; the unlock and foreground
  triggers bring the watch back, and the Settings row says so
  when the watch is down. No battery-settings guidance was
  added (the app has no existing pattern for it).

### UI

- Settings → Deep view group: a "Bridge auto-restart" status
  row (not a switch — the watch follows Deep view itself)
  plus a short body line stating the cost and the stop path.
  Tap opens Deep view.
- Deep view: a watchdog line under the bridge status, kept
  fresh by `ping()` and `onResume`. The "start it again after
  every reboot" body copy now says auto-restart brings the
  bridge back by itself while wireless debugging stays on,
  and the AdbStarter stage-3 success summary says the same.

### Verification

- New `harness/WatchdogHarness.java`: 58/58 (decide matrix
  incl. Deep-view-off and in-flight, backoff schedule and
  cap, failure classification on the verbatim AdbStarter
  strings, every state line, constants).
- All 12 prior suites re-run fresh with identical counts:
  RingMath 101/101, AppListCodec 22/22, AudioModem 50/50,
  Wallet 56/56, Terminal 29/29, AdbConnect 15/15, Nat
  190/190, LowData 131/131 (documented classpath), HoldWatch
  64/64, Relay 28/28 (live relay via the IPv6 proxy),
  BridgeGate 44/44, Setup 85/85. (One runner note, recorded
  here so it is not repeated: NatHarness compiles with
  `libs/bcprov-jdk18on.jar` on the classpath per its header —
  compiling it with only org-json.jar fails on RelayKeys and
  the run then silently uses stale classes.)
- Badging, apksigner verify + cert, new symbols in
  classes.dex, and the BOOT_COMPLETED receiver + specialUse
  service declarations (subtype `bridge-watchdog`) in the
  compiled manifest all verified. The v1.11
  RECEIVER_NOT_EXPORTED registration is untouched (file not
  modified); zero AccessibilityService references in source,
  manifest, or dex.
- No behavior change to the bridge protocol, the hold watch,
  the overlay ring, or any existing flow. The watchdog is
  purely additive.

### Unproven on-device (Adam's tests)

- Auto-restart after the bridge dies, after a reboot (with
  wireless debugging on), and with wireless debugging off
  (quiet wait + honest row, then self-recovery when it is
  turned back on).
- The boot start actually firing on his A16 (background-start
  restrictions vary), and the unlock/foreground backstop when
  it does not.
- Battery behavior in hand, and whether Samsung kills the
  service between pings.

## v1.15.1 (2026-11-16) — stale-bridge fix: hold-anywhere said "bridge off" when it was on

Adam (2026-11-15, on-device): "The hold anywhere says bridge is off
when it isnt." APK: 20,632,889 B, sha256
`a74e547879089f823b41fdba20feb24be0c1626eb14b539857be1ccd074d00c8`,
versionCode 19 / "1.15.1", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.15.
NO new permissions (25, unchanged).

### The failure chain (diagnosed from the code, then fixed)

1. The bridge is a separate `app_process` that SURVIVES APK
   upgrades: upgrading the app does not restart it. Adam's
   running bridge was the OLD v3 bridge (started before the
   1.14 upgrade), which has no HOLD_WATCH op.
2. The v3 bridge answers PING fine, so Deep view worked and the
   bridge WAS running. But when the v1.14+ client sent
   HOLD_WATCH, the v3 bridge's switch hit `default:` and threw
   "Operation is not on the bridge allowlist." — an ok:false ack.
3. `HoldWatchClient` treated the refused ack as a watch end, so
   `OverlayRingService.onEnded` ran `stopSelf()` and
   `serviceAlive` went false.
4. Settings' hold-anywhere row keyed off `serviceAlive` and
   reported "Off — Deep view is not running" for a bridge that
   was running. Every reconcile repeated the cycle.
5. Root enabler: `ShellBridgeClient.Response` had NO version
   field — the client parsed bridgeUid/bridgePid but dropped
   the "version" the bridge already sends in every response,
   so nothing could tell "stale bridge" from "no bridge."

### The rule this establishes

**Bridge protocol bumps require a bridge restart.** The running
bridge keeps answering with the OLD version until it is
restarted, because the new APK's classes are only picked up by
a fresh `app_process` (the starter builds the classpath from
the current install via `pm path`). Any future op added to
`ShellBridgeMain` must bump `VERSION` AND keep
`BridgeGate.HOLD_WATCH_VERSION` in step, and the app must keep
detecting the stale state and offering the restart — never
silently misreporting a running bridge as down.

### The fix

- **Version parsed** (`ShellBridgeClient.Response.bridgeVersion`,
  via `BridgeGate.parseVersion`): the "version" from every
  bridge response is now kept. A missing version means 0 —
  stale by definition. The two other `Response` construction
  sites (TCP failure, Deep view's fallback) pass 0.
- **Three-state gate** (`BridgeGate`, pure Java): NOT_RUNNING
  (ping fails), STALE (ping ok, version < 4), CURRENT (version
  >= 4). `OverlayRingService.reconcile()` pings fresh on every
  run, caches the state + version, and starts the service ONLY
  on CURRENT — a stale bridge never starts a watch that would
  just be refused and die again. The Settings row reads the
  cached fresh-ping state through `BridgeGate.stateLine`, which
  names the actual failing gate (switch / overlay permission /
  bridge not running / "the bridge is running an old version —
  restart it for hold-anywhere" / on) and never says "Deep view
  is not running" for a running bridge. `reconcile()` gained an
  optional main-thread callback so Settings re-renders its
  summary when the ping lands.
- **One-tap stale restart** (`AdbStarter.restartStaleBridge`):
  the plain `restartBridge` CANNOT fix a stale bridge — it pings
  first and early-returns "already running" without starting
  anything, and merely re-sending the starter would fail anyway
  because the new bridge process could not bind the socket the
  old one holds. So the stale path stops the old bridge first
  (STOP_BRIDGE via `BridgeControl.stopBridge`, the same audited
  op Settings' "Turn off deep control" uses), waits until the
  ping fails (up to ~4s), then runs the EXISTING starter flow —
  no parallel starter. A current bridge is left alone (the
  "already running" early return stays for the
  genuinely-current case), a down bridge goes straight to the
  starter, and success is only reported when a fresh ping shows
  version >= 4. Surfaced in two places: a "Restart the bridge"
  row in Settings' hold-anywhere group (its summary names what
  the tap will do from the last fresh ping), and Deep view's
  existing "Restart deep view" button, which now calls the
  stale-aware path. After a restart, reconcile re-runs and a
  current bridge starts the overlay service.
- **Defense in depth** (`HoldWatchClient`): a refused ack whose
  error is the allowlist error (or whose reported version is
  below 4) ends with the reason "The bridge is too old —
  restart it (Deep view → Restart)." — the log tells the truth
  even if the version gate is somehow bypassed.
- **Deep view shows the version**: the status line now reads
  "bridge v4 running as shell UID …" (via
  `BridgeGate.versionFragment`), with an explicit old-version
  note and restart pointer when the version is below 4, so
  future reports can name the bridge version.

### Verification

- New `BridgeGateHarness` 44/44 (version parsing incl.
  missing→0, three-state classification, want-service matrix,
  stale-refusal detection, exact state-line copy, version
  fragments). All 11 prior suites re-run with identical counts:
  RingMath 101/101, AppListCodec 22/22, AudioModem 50/50,
  Wallet 56/56, Terminal 29/29, AdbConnect 15/15, Nat 190/190,
  LowData 131/131, HoldWatch 64/64, Setup 85/85, Relay 28/28
  (live relay via the IPv6 proxy).
- The stale-bridge detection + stop-then-start restart flow on
  Adam's phone is his test; no on-device test is possible here.
  Workaround until this build lands (also the manual form of
  the fix): stop the bridge in Settings first, THEN restart
  from Deep view — the plain restart no-ops on a running
  bridge.

## v1.15 (2026-11-16) — first-run setup: name, permissions, import, light/dark theme

Adam (2026-11-15): "can we have a setup when the launcher is first
launched. Setting a name, accepting permissions, importing profile,
select light or dark theme." APK: 20,628,793 B, sha256
`4694e7b245f92e31478cb6f5c0888eea4087eb258c7b402f64c6a1d87e968273`,
versionCode 18 / "1.15", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.14. NO new permissions (25,
unchanged).

### The setup flow (`SetupActivity`, four steps, in order)

1. **Name.** One field, pre-filled from the stored profile name
   on a re-run. Continue writes through `Profile.setDisplayName`
   — the same `mesh.displayName` every other screen reads; no
   second name store. Blank input is treated as a skip.
2. **Permissions.** Eleven grant rows, each with one line saying
   what it is for and what breaks without it: notifications,
   notification access, microphone, camera, location, nearby
   devices, phone state, SMS (marked optional), usage access,
   VPN permission, draw-over-other-apps. Runtime groups use the
   same `requestPermissions` mechanism the rest of the app uses;
   the four special grants reuse the existing deep links and
   flows (notification-listener settings, usage-access
   settings, `VpnService.prepare`, `OverlayRingService`
   .overlaySettingsIntent). No parallel grant paths. Statuses
   refresh on resume and on permission results. Every row is
   individually skippable via Continue / Skip all, and declining
   never blocks completion — Settings remains the place to
   grant later. Permissions that don't exist on the running SDK
   (POST_NOTIFICATIONS below 33, nearby-devices below 31) are
   filtered out of the request and read as granted; normal
   install-time permissions (Internet, NFC, vibrate, biometric,
   foreground service) need no grant and are not listed. Device
   admin is deliberately NOT in setup — it is opt-in via
   Settings → Device admin for the SMS LOCK command.
3. **Import.** The existing `ImportActivity` (file + QR, same
   decryption, same fingerprint verification, same overwrite
   rules) launched for a result; it now `setResult(RESULT_OK)`
   on a successful install, which standalone callers ignore. A
   successful import advances to the theme step. The step's copy
   states the ordering rule before the user taps: importing
   replaces the whole profile, so the imported profile's name
   wins over the name typed in step 1. "Start fresh" writes a
   default profile if none exists yet and advances.
4. **Theme.** Dark / Light preview cards rendered from each
   set's own tokens. Tapping a card persists the choice and
   re-themes setup itself live (recreate). Finish completes
   setup.

Flow rules: setup shows exactly once, gated on the SAME
`firstrun_done` flag the old first-run screen set — existing
installs already carry it, so upgrading never re-shows setup.
The trigger in MainActivity is the flag alone (the old
`!Profile.exists()` conjunct is gone: profile creation can
happen through other paths, and the flag is the honest
"setup done" record). The current step is persisted
(`setup_step`) so leaving setup mid-flow resumes at the same
step; Back steps backward, Back from step 1 leaves setup for
the normal launcher — setup never traps the user. Import sets
the completed flag as its standalone behavior; setup clears it
again on a successful import return, because setup isn't
complete until the theme step finishes. Re-runnable from
Settings → Appearance → "Run setup again". The old
two-button `FirstRunActivity` is deleted.

### Themes: the light set is new

`UiTheme.java` (pure Java, no Android imports) holds both token
sets: DARK is byte-identical to the v1.3 constants, LIGHT
mirrors it token for token with light values (radii unchanged).
`Ui`'s token fields are now mutable copies of the active set;
every call site keeps reading `Ui.BG` etc., and
`Ui.refreshTheme(context)` swaps the set underneath.
`ThemedActivity` — the new base class of all 35 activities —
picks the window style (`AppTheme` / `AppTheme.Light`, new in
`res/values/styles.xml` + light colors in `colors.xml`, so the
window background and system bars match) and applies the theme
in every onCreate, and rebuilds the activity on resume when the
theme changed while it was paused. Activities holding transient
state a rebuild would lose (forms, camera/audio sessions, the
terminal transcript, a loaded web page) override
`recreateOnThemeChange()` to return false — 16 exemptions, each
documented at the override — and pick up the theme next time
the screen opens. Views that draw with the tokens at draw time
(the ring, the tuner) re-theme live; the ring's one
construction-time color (the dwell arc) moved to draw time so
the long-lived overlay ring follows a theme change without a
service restart.

Light token set (all opaque ARGB): BG #F2F5F1, PANEL #E9EFE8,
SURFACE #FFFFFF, SURFACE2 #E3EAE2, TEXT #101613, DIM #5A6B5E,
ACCENT #1E7A45 (deeper green for contrast on light grounds),
ACCENT_INK #FFFFFF, LINE #C9D4C9, SIGNAL #B45309 (darkened
amber), PINE #14532D, PINE_DEEP #0C3520 (shared with dark),
PTT_BG #DCE9DC, PTT_LINE #9DB8A4, DOT_LINE #8FA795.

The theme choice is device-local SharedPreferences
(`theme_light`, default false = dark): upgrading keeps the dark
look, and importing a profile never flips the theme. The
Settings switch (Appearance group) persists, applies, and
recreates; the launcher recreates on resume via ThemedActivity.

### Files

New: `UiTheme.java`, `SetupFlow.java` (pure flow decisions:
show/advance/back/clamp + the import-supersedes-name rule),
`ThemedActivity.java`, `SetupActivity.java`,
`harness/SetupHarness.java`. Changed: `Ui.java` (mutable tokens
+ refreshTheme/applyThemeStyle), `Prefs.java` (two keys),
`Profile.java` (`displayNameStored` raw accessor),
`MainActivity.java` (setup trigger), `SettingsActivity.java`
(Appearance group: Light theme switch + Run setup again),
`ImportActivity.java` (RESULT_OK on success),
`CircleMenuView.java` (arc color at draw time),
`AndroidManifest.xml` (SetupActivity in, FirstRunActivity
out), `res/values/styles.xml` + `colors.xml` (light style),
`build-apk.sh` (version bump), all 35 activities (base class;
16 recreate exemptions), these notes. Deleted:
`FirstRunActivity.java`. Untouched per the brief: overlay ring
+ hold detection + gating + fallback menu, Connections (the
v1.11 RECEIVER_NOT_EXPORTED registration is byte-identical —
grep-verified), wallet, NAT/gateway, audio, terminal, pairing,
ADB, relay, chat, walkie, ring geometry/logic. No
AccessibilityService anywhere in the tree or the dex
(verified: zero references).

### Verification (v1.15)

New `SetupHarness`: 85 checks, 0 failures (both token sets
define all 15 tokens, all opaque; dark set byte-identical to
v1.3; light grounds light / ink dark / accent-ink contrast;
flow advance/back/clamp/completion; import-supersedes-name
rule incl. nulls). All 10 prior suites re-run against the exact
shipping classes with identical counts: RingMath 101/101,
AppListCodec 22/22, AudioModem 50/50, Wallet 56/56, Terminal
29/29, AdbConnect 15/15, Nat 190/190, LowData 131/131
(documented classpath: harness classes + org-json.jar only),
HoldWatch 64/64, Relay 28/28 (live relay via the IPv6 proxy).
`./build-apk.sh` clean; badging versionCode 18 / "1.15";
apksigner verifies with the v1 certificate; 25 permissions;
SetupActivity/UiTheme/SetupFlow/ThemedActivity symbols and the
light + dark token constants verified in classes.dex;
SetupActivity verified in the compiled manifest;
AppTheme.Light verified in resources.arsc; FirstRunActivity
absent from the dex. **Still unproven until Adam's phone**:
the setup flow in hand (all four steps, skip paths, resume
after leaving mid-setup, re-run from Settings), the light
theme in hand (every screen, the ring over other apps, the
system bars), and the permission grants on his A16.

## v1.14 (2026-11-16) — overlay ring via Deep-view hold detection + top navigation strip removed

Adam (2026-11-15), after the accessibility correction (the
accessibility API exposes no raw hold position, so the
AccessibilityService route is dead and was NOT built): "Deep-view
hold detection" — hold anywhere for longer than a second, the ring
opens centered on the finger, over any app — plus "remove the
navigation bar on the top of the screen" (the launcher's own
page-icon strip, not the system status bar), plus: "Build the menu
into the launcher as a fallback." APK: 20,624,697 B, sha256
`966a6f5c69cd67dd3991d3cd8ed7ad22fbd7bcaddda9f75808e3b0a8c72d3603`,
versionCode 17 / "1.14", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.13. ONE new permission:
SYSTEM_ALERT_WINDOW (25 total) — the overlay needs it; the grant
is a one-time Settings toggle the app deep-links to. NOT uploaded
to the launcher site.

### Piece 1: the overlay ring

Mechanism (Adam's pick): the Deep-view shell bridge runs
`getevent` on the touchscreen and a local parser detects the
hold. The channel is a NEW STREAMING BRIDGE OP, not a persistent
adb shell session: extending the bridge keeps the hold watch
inside the existing authenticated IPC (peer-UID check + private
token), lets the parser run in the bridge process so raw touch
data never crosses a socket, and makes the watch die with the
bridge instead of orphaning an adb session. Bridge protocol
version 3 → 4.

- `ShellBridgeMain` gains op `HOLD_WATCH`. `handleConnection`
  peeks at the request: HOLD_WATCH authenticates exactly like
  every other op (transport + token), then `streamHoldWatch`
  runs on the connection thread until the client disconnects.
  It runs the fixed `getevent -p`, picks the touchscreen with
  `TouchDeviceProbe` (never a hardcoded node; the path is
  validated against `^/dev/input/event\d+$` before exec), runs
  the fixed `getevent -t <node>`, and streams one JSON line per
  hold: `{"hold":true,"nx":…,"ny":…}` (normalized coordinates).
  A drain thread on the request stream notices the client
  disconnecting (EOF) and kills getevent. The bridge log gets
  start/end lines only.
- `HoldEventParser` (pure Java): parses `getevent -t` lines,
  tracks the PRIMARY slot only (slot 0; other slots ignored),
  handles MT (SLOT + POSITION_X/Y + TRACKING_ID) and ST
  (BTN_TOUCH + ABS_X/Y) with MT winning once seen. A hold is
  one finger down, stationary within the radius (12dp,
  normalized), for strictly more than 1000ms, judged at
  SYN_REPORT frame boundaries. Lift or movement beyond the
  radius cancels the touch outright — a drag that pauses never
  fires. Fires exactly once per touch.
- `TouchDeviceProbe` (pure Java): parses `getevent -p` blocks.
  MT needs 0035+0036+0039, ST needs 0000+0001+014a, ranges must
  be real. Selection prefers MT, then a touch-named device (a
  stylus digitizer must not win over the touchscreen), then
  first-listed.
- `HoldCoordinateMapper` (pure Java): normalized
  native-orientation coords → current display pixels, given the
  rotation and current display size. Convention: rotation is
  clockwise from natural — 0° (nx*w, ny*h), 90° ((1-ny)*w,
  nx*h), 180° ((1-nx)*w, (1-ny)*h), 270° (ny*w, (1-nx)*h).
  The 90/270 pair is the one a wrong convention would mirror;
  the harness pins all four, and the on-device rotation test
  is Adam's. Also computes the normalized hold radius (12dp
  over the shorter display side).
- `HoldWatchClient` (app side): one long-lived connection
  (local socket first, loopback TCP second — the
  ShellBridgeClient order), sends the radius + threshold, reads
  the ack, then maps each hold with rotation/metrics read fresh
  per hold and delivers it on the main thread. Any end is
  reported once via onEnded. Never sees the raw stream, never
  logs coordinates.
- `OverlayRingService`: a specialUse foreground service
  (subtype "overlay-ring", persistent notification, NOTIF_ID 5)
  that owns the watcher and the overlay window. The window is
  TYPE_APPLICATION_OVERLAY, sized to the ring plus a margin so
  touches outside it pass through, with FLAG_NOT_TOUCH_MODAL +
  FLAG_WATCH_OUTSIDE_TOUCH (a tap anywhere outside dismisses)
  and focus (Back goes up one ring level, then dismisses). The
  ring is the EXISTING CircleMenuView — same sub-rings, dwell,
  rotational paging, release-to-open — with an
  `OverlayRingSource` that mirrors MainActivity's ring contents
  from the same stores and routes through intents (tuning and
  the home-role request go through two new MainActivity extras,
  EXTRA_TUNE_CHANNEL / EXTRA_REQUEST_HOME_ROLE; chat leaves are
  profile-local only — server-adopted/locked rows live in the
  Chat page). The opening gesture belongs to the app underneath
  (its DOWN predates the window), so the ring is driven by the
  next touch: lift, then tap or drag. The app underneath may
  also fire its own long-press — accepted by Adam.
- Gating: the service runs only while the feature switch is on
  (Prefs KEY_OVERLAY_RING_ON, default ON — the gates below are
  the real control, and granting the overlay permission is the
  effective opt-in), the overlay permission is granted, AND the
  bridge answers. `OverlayRingService.reconcile` (background
  thread) enforces it from Settings (toggle + resume),
  DeepViewActivity's ping, and the stop-bridge confirm. If the
  bridge dies, the watch ends and the service stops itself —
  no crash, no prompt spam. The Settings row's summary line
  says plainly which gate is unmet. The overlay-permission row
  deep-links to Settings.ACTION_MANAGE_OVERLAY_PERMISSION.
- No double menu: `CircleMenuView` keeps a static open-menu
  COUNT (showAt/close, guarded by each instance's open state —
  a count, not a flag, because the two instances can overlap
  briefly). The overlay ignores holds while any menu is open,
  so a chrome long-press that already opened the built-in menu
  never also opens the overlay. Holds on yield surfaces (the
  WebView, the tuner), where the built-in menu never opens, DO
  open the overlay — including over the launcher itself.

PRIVACY (non-negotiable, and the design enforces it): the touch
stream is parsed inside the bridge process on the device; only
hold points (normalized coordinates + fire time) cross the
socket; coordinates are never logged (bridge log, audit log,
OwnLog), never stored (no prefs, no files), and never leave the
device. The parser retains only the current point and the
anchor, overwritten continuously. The Settings copy states this.

### The fallback: the in-launcher menu stays (Adam's order)

The built-in long-press menu is FULLY INTACT and independent of
the overlay feature: the pager's 450ms chrome long-press, the
circle menu, the sub-rings — all unchanged, working with Deep
view off and no overlay permission, exactly as v1.13. The only
CircleMenuView change is the static open-count (read by the
overlay service only; nothing in the view reads it). Gating
never disables the built-in menu: the overlay service starting,
stopping, or failing has no path into MainActivity or
PagerLayout. Verified: no behavior change to the in-launcher
gesture path (see Verification).

### Piece 2: the top navigation strip is gone

`PageIconsView` (the [Browser, Home, Walkie, Chat, Apps] strip)
is deleted, with every reference in MainActivity: the field,
creation, listener, layout slot, `setAppsRaised` calls, and
`updatePageIcons`. The stage (pager + sheet + menu) already
held weight 1, so it expands into the freed 26dp. Navigation is
pager swipe + the circle menu (in-launcher long-press, plus the
overlay ring). Nothing else was anchored to the strip: no badge
or count lived on it (it showed only the active page and the
raised-sheet state), page headers inside individual pages (the
chat "?" header, browser chrome) are untouched, and the
bottom-edge swipe-up for the Apps sheet is pager-internal and
unchanged. The system status bar is never touched.

### Files

New: `HoldEventParser.java`, `TouchDeviceProbe.java`,
`HoldCoordinateMapper.java`, `HoldWatchClient.java`,
`OverlayRingService.java`, `OverlayRingSource.java`,
`harness/HoldWatchHarness.java`. Changed: `ShellBridgeMain.java`
(VERSION 4, HOLD_WATCH streaming op, handleConnection split),
`CircleMenuView.java` (open-count only — no behavior change),
`MainActivity.java` (strip removal + two overlay extras),
`SettingsActivity.java` (Hold-anywhere menu group),
`DeepViewActivity.java` (reconcile on ping), `Prefs.java` (one
key), `AndroidManifest.xml` (SYSTEM_ALERT_WINDOW +
OverlayRingService), `build-apk.sh` (version bump), these
notes. Deleted: `PageIconsView.java`. Untouched: Connections
(the v1.11 RECEIVER_NOT_EXPORTED registration is byte-identical
— grep-verified), wallet, NAT/gateway, audio, terminal,
pairing, ADB, relay, chat, walkie, ring geometry/logic, Ui
tokens. No AccessibilityService anywhere in the tree or the
dex (verified: zero references).

### Verification (v1.14)

New `HoldWatchHarness`: 64 checks, 0 failures (MT/ST parser
legs, canned getevent lines, probe samples, all four rotation
mappings). All 9 prior suites re-run against the exact shipping
classes with identical counts: RingMath 101/101, AppListCodec
22/22, AudioModem 50/50, Wallet 56/56, Terminal 29/29,
AdbConnect 15/15, Nat 190/190, LowData 131/131 (documented
classpath: harness classes + org-json.jar only), Relay 28/28
(live relay via the IPv6 proxy). `./build-apk.sh` clean;
badging versionCode 17 / "1.14"; apksigner verifies with the v1
certificate; 25 permissions (24 + SYSTEM_ALERT_WINDOW); new
symbols verified in classes.dex; OverlayRingService verified in
the compiled manifest (specialUse + "overlay-ring" subtype);
PageIconsView absent from the dex. **Still unproven until
Adam's phone**: the hold detection on his A16 (getevent device
pick, MT stream shape, the 1s threshold in hand), the overlay
over other apps (position, Back/outside-tap dismiss, the
underlying app's own long-press), the rotation mapping in
landscape, the reconcile/gating in hand (bridge restart, reboot,
permission grant/deny), and the in-launcher fallback with Deep
view off.

## v1.13 (2026-11-16) — Connections redesign: clean list, popup modals, long-press menus

Adam (2026-11-15): "make the connections window cleaner with less
text and each item with a popup modal with a question mark for info
inside the modal. Also a popup menu that opens when you hold down
on the item." APK: 20,612,409 B, sha256
`67c8ce3dc5a38c098204ac92841c3408ce10cae86812f02015717c0059e22020`,
versionCode 16 / "1.13", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.12. NO new permissions (24,
unchanged). NOT uploaded to the launcher site. Only
`ConnectionsActivity.java`, `build-apk.sh` (version bump), and
these notes changed. Untouched: wallet, NAT/gateway, audio,
terminal, pairing, ADB, relay, chat, walkie, ring UI (the
EXTRA_SECTION contract is preserved — see below). Presentation +
structure ONLY — no behavior changed: every control, refresh,
confirmation, and state machine below is the old card's code,
moved.

### The list

The screen is now a title + nine compact rows, in the old cards'
order: Wi-Fi, Bluetooth, Cellular, Airplane mode, NFC, Location,
VPN &amp; filter, Mesh internet, and the Deep-view (bridge) status
line (last; it used to sit at the top). Each row shows the name +
one live status line, nothing else — the long explanatory
paragraphs are off the list entirely. Rows are tappable with a
visible pressed state (SURFACE → SURFACE2 while held). The row
status lines are bound at onCreate and always live; the refresh
methods compute a short row form alongside the full detail string
they always computed (e.g. Wi-Fi "On · \<ssid\>", Cellular
"\<carrier\> · LTE · data on", mesh the state sentence without
the VPN-slot line).

### Tap → popup modal, with "?" inside

Tapping a row opens an AlertDialog whose content is built fresh
on every open: a header (title + a "?" small button, the chat
"?" popup pattern from v1.5), the item's detail status, and
exactly the controls its old card had:

- Wi-Fi: detail status, grant-location button, Scan + Wi-Fi
  panel buttons, scan-results list (cached results render on
  open), bridge switch.
- Bluetooth: detail status, paired list, Turn on / Scan /
  settings buttons, found-devices list with tap-to-pair, bridge
  switch.
- Cellular: detail status, grant-phone-state button, Internet
  panel + mobile-data-settings buttons, bridge switch.
- Airplane mode: detail status, settings button, bridge switch
  (strong press-and-hold confirm for ON, unchanged).
- NFC: detail status, settings button, bridge switch.
- Location: detail status, settings button.
- VPN &amp; filter: detail status (filter + Private DNS),
  Open-filter-settings button.
- Mesh internet: detail status (state sentence + VPN-slot
  truth), share/use switches, Find nearby phones + Grant VPN
  permission buttons, peer dialog, consent flow — all unchanged.
- Deep view: the old top status line verbatim, plus Open Deep
  view + Re-check buttons (new, but only navigate / re-check —
  the row had no controls before).

The "?" button shows an AlertDialog with the item's info text —
verbatim from the old cards where it existed (Location's
no-bridge-switch line, the mesh "Limits, plainly" paragraph, the
filter's reports-only sentence, the airplane warning), composed
from existing copy elsewhere. The modal dismisses on outside tap
and Back (AlertDialog defaults). While a modal is open, its
views are bound to the same fields the cards used, so the
event-driven refreshes update it live; dismiss clears all modal
bindings together, and every refresh null-guards them, so
refreshes with no modal open update rows only.

### Long-press → popup menu

Long-pressing a row opens an anchored `PopupMenu` (the
BrowserPage pattern) with the item's quick actions. Every menu
entry runs the SAME factored action method the modal's buttons
run — panel openers, scans, permission requests, the mesh
setShare/setUse + reconcile, and the bridge flips
(`flipWifi`/`flipBluetooth`/`flipMobileData`/`flipAirplane`/
`flipNfc`, which take the switch or null from the menu;
`bridgeToggle`/`syncSwitch` are null-safe). Bridge-flip menu
items appear only while the bridge is up, and are labeled with
the current state ("Turn Wi-Fi off (bridge)"). Menu contents:
Wi-Fi (panel, scan, grant location if missing, bridge flip);
Bluetooth (settings, turn on, scan, bridge flip); Cellular
(Internet panel, mobile-data settings, grant phone-state if
missing, bridge flip); Airplane (settings, bridge flip); NFC
(settings, bridge flip); Location (settings); VPN &amp; filter
(open filter settings); Mesh internet (find phones, share
toggle, use toggle); Deep view (open Deep view, re-check
bridge).

### Preserved contracts

- **Live refresh**: the onResume receiver + NetworkCallback are
  byte-identical, INCLUDING the v1.11 RECEIVER_NOT_EXPORTED
  guarded registration (grep-verified in the shipped file) — the
  v1.10 crash cannot return through this refactor.
- **Bridge tier**: `pingBridge`, `bridgeToggle`,
  `confirmWrite` (plain + strong), and the governed
  BridgeControl ops are untouched; bridge switches still hide
  until the bridge answers.
- **VPN-slot interaction**: the mesh reconcile + refresh calls
  on every toggle are unchanged (factored into
  `setMeshShare`/`setMeshUse`, called by switches and menu
  alike); filter-start-stops-NAT lives in
  SettingsFilterActivity and was not touched.
- **EXTRA_SECTION**: a ring leaf naming a section still lands
  on that item — the screen scrolls to its row and highlights
  it briefly (accent border, 1.2 s). The modal is NOT
  auto-opened, keeping the ring navigation-only as before. All
  nine titles resolve, including the seven the ring sends.
- **Theme**: existing Ui tokens only (SURFACE/SURFACE2/LINE/
  ACCENT/DIM, 14dp radii, mono status lines). No new
  permissions, no new files.

### Verification (v1.13)

No status/decision logic was factored into pure Java — the
refactor is Android UI only, and the UI cannot be
harness-tested, so no new harness applies; that is stated
plainly rather than faked. All 9 existing suites re-run with
identical counts: RingMath 101/101, AppListCodec 22/22,
AudioModem 50/50, Wallet 56/56, Terminal 29/29, AdbConnect
15/15, Nat 190/190, LowData 131/131, Relay 28/28 (live relay
via the IPv6 proxy). (One harness-invocation note: LowData must
run with its documented classpath — `harness/classes` +
`harness/org-json.jar` only. With android.jar ahead of it, the
stub org.json shadows the real jar and the old-client leg fails
with "Stub!"; that is the runner's classpath, not a regression.)
`./build-apk.sh` clean; badging versionCode 16 / "1.13";
apksigner verifies with the v1 certificate; 24 permissions; the
new `showItemModal`/`highlightRow`/`itemRow` symbols verified in
the shipped classes.dex. **Still unproven until Adam's phone**:
the redesign in hand — tap/long-press feel, modal readability,
live-status updates inside an open modal, the EXTRA_SECTION
scroll + highlight, and the popup menus on his A16.

## v1.12 (2026-11-16) — wallet card-save fix, second attempt: try every cipher source

Adam (2026-11-15, on v1.11): "Nosuchalgorithm — Error when I added
a card." The v1.9.2 wallet fix itself was the failure, and its
premise was wrong. APK: 20,608,313 B, sha256
`4da81aeea003ddcd9bdcbf01418f73db634cf65dbfb2a06e051e640c941d720e`,
versionCode 15 / "1.12", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.11. NO new permissions (24,
unchanged). NOT uploaded to the launcher site. Surgical build:
only `WalletCrypto.java`, `harness/WalletHarness.java`,
`build-apk.sh` (version bump), and these notes changed. Untouched:
WalletStore, WalletActivity, NAT/gateway, audio, terminal,
pairing, ADB, relay, chat, walkie, ring.

### The corrected diagnosis

v1.9.2 assumed an Android Keystore key can ONLY be used through a
cipher from the "AndroidKeyStore" provider, and changed
`WalletCrypto.newCipher()` to ask for that provider first, falling
back to the default provider only on `NoSuchProviderException`.
That premise was wrong: the documented Android pattern is the
no-provider cipher, which delegates Keystore keys via Conscrypt,
and the v1.7 `InvalidKeyException` theory behind v1.9.2 was never
observed on a device — the generic "Couldn't save that card."
toast hid every cause. What Adam DID observe once v1.9.2's
honest-error change made failures visible: on his Samsung A16
(Android 16),
`Cipher.getInstance("AES/GCM/NoPadding", "AndroidKeyStore")`
throws `NoSuchAlgorithmException` — the provider exists, but does
not expose that Cipher lookup on his device — and the v1.9.2 catch
missed it because it handled only `NoSuchProviderException`, so
the exception propagated to the UI. WalletStore's key spec is
textbook (AES, ENCRYPT|DECRYPT, GCM, NO_PADDING, 256,
randomized) and `KeyGenerator.getInstance(AES, "AndroidKeyStore")`
is universal, so the key was never the suspect; the existing
keystore key was NOT deleted or regenerated.

### The fix: try the full operation with each source, in order

`WalletCrypto` no longer bets on one theory. Every encrypt and
decrypt attempts the FULL operation (getInstance + init + doFinal)
with each cipher source in order — the default provider FIRST
(the documented pattern), the AndroidKeyStore provider SECOND
(it works on some devices) — moving to the next on ANY exception,
and throws only if ALL fail. Each attempt is independent (a fresh
IV is generated inside the encrypt attempt), so a failed attempt
emits nothing reusable. If all attempts fail, the thrown
exception's message names EVERY attempt's failure (source +
exception class + short message), e.g.
"No cipher worked: default=InvalidKeyException: \<short\>;
keystore=NoSuchAlgorithmException: \<short\>", hard-capped at 160
characters to fit WalletActivity's error display, with the last
failure as the cause for logcat. A residual failure — e.g. a key
problem, which would fail under BOTH providers at init — is now
diagnosable from the next report instead of another guess. The
cipher sources are a small `CipherSupplier` interface (name +
`get()`); the public methods delegate to package-private
overloads taking an explicit supplier list, so the harness
substitutes failing suppliers through the same shipping path —
no test-only branches. The wire form (IV ‖ ciphertext+tag) is
byte-identical since v1.7, so anything already sealed stays
readable. WalletCrypto remains free of Android types.

### Verification (v1.12)

WalletHarness grew a sixth leg (10 checks): the shipping list is
default-first/keystore-second; a lookup failure (a supplier
throwing exactly the A16's NoSuchAlgorithmException) and an init
failure (a cipher for the wrong transformation) both fall through
to the next supplier and the operation succeeds; a first success
stops the attempts (the second supplier is never invoked); if
every supplier fails, the message names every attempt for both
encrypt and decrypt and fits the 160-char cap; a null exception
message yields a readable entry; a tampered blob names both
attempts and still throws. WalletHarness: **56/56**. All other
suites re-run with identical counts: RingMath 101/101,
AppListCodec 22/22, AudioModem 50/50, Terminal 29/29, AdbConnect
15/15, Nat 190/190, Relay 28/28 (live relay via the IPv6 proxy),
LowData 131/131. `./build-apk.sh` clean (R8 dexing); badging
versionCode 15 / "1.12"; apksigner verifies with the v1
certificate; 24 permissions; the new `WalletCrypto$CipherSupplier`
class and the "No cipher worked" string verified in the shipped
classes.dex. **Still unproven until Adam's phone**: which cipher
source works with a real Keystore key on the A16 — Adam adding a
card is the first real test of the default-provider path on his
phone, and if it still fails, the error message will now say
exactly what each attempt did.

## v1.11 (2026-11-16) — walkie-talkie low-data mode (offline) + Connections crash fix

Adam: "I also want the walkie talkie to have a low data mode which
converts your voice to text and then creates a voice profile of the
persons voice, and then when they receive the text it reads it in
their voice." The straight answer he was given first: real voice
cloning is cloud-only (ElevenLabs) and contradicts the offline
purpose, so the approved scope is the OFFLINE version — the
sender's speech is transcribed on-device, only text crosses the
mesh/relay, and the receiver's phone reads it aloud in a
per-contact synthetic TTS voice profile (pitch + rate + installed
system voice): recognizable, NOT the real voice. No claim of voice
cloning anywhere in the app or these notes. APK: 20,608,313 B,
sha256 `7ed6fcfc9d533f0e1cf6d42bcafa25070a93f6baa4d60b8861bb8a7af06f0b47`,
versionCode 14 / "1.11", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.10. NO new permissions (24,
unchanged). NOT uploaded to the launcher site. Untouched, per the brief: wallet,
audio modem/payload/Player/Listener, NAT/gateway, terminal,
pairing, ADB, relay protocol, ring UI.

### The engine: Vosk 0.3.45 + the small US-English model

The hard requirement was transcription with NO connection.
Android's SpeechRecognizer was rejected on purpose: it is
device-dependent and usually phones home to a cloud service even
with EXTRA_PREFER_OFFLINE — shipping it labeled "offline" would
be a lie. Vosk (Kaldi-based, Apache 2.0) is a real offline engine
with a Java API over JNA, and it vendors into this manual build
the way Bouncy Castle did:

- `libs/vosk-classes.jar` — the five `org.vosk` classes (Model,
  Recognizer, LibVosk, SpeakerModel, LogLevel) extracted from the
  Maven Central jar (desktop natives excluded).
- `libs/jna-classes.jar` — the classes.jar from the JNA 5.19.1
  AAR (Vosk's only dependency; the AAR variant, so the dispatch
  library ships as a native lib rather than a jar resource).
- `native-libs/arm64-v8a/` + `native-libs/armeabi-v7a/` —
  `libvosk.so` (8.9 MB / 8.3 MB, from the vosk-android-0.3.45.zip
  GitHub release) + `libjnidispatch.so` from the JNA AAR.
  build-apk.sh zips them STORED (uncompressed) under `lib/<abi>/`
  and zipalign -p page-aligns them. x86/x86_64 are NOT shipped
  (emulators only); on an x86 phone the engine fails honestly
  with "couldn't start" instead of crashing.
- The model is NOT bundled: `vosk-model-small-en-us-0.15`
  (41,205,931-byte zip, ~68 MB unpacked, 16 kHz) downloads once
  on first enabling the mode, from
  https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
  with the size shown honestly before the download ("39 MB"),
  real progress during it, a Cancel button, and a pinned SHA-256
  check (`30f26242…42498`) — a mismatched download is deleted,
  never unpacked. Unzip strips the top-level folder, guards
  against zip-slip entries, and verifies the markers
  (`am/final.mdl`, `graph/Gr.fst`, `conf/model.conf`) before the
  directory swaps into place at `files/vosk-model/`. The model is
  device-local, like the background photo — never in a profile
  export. `SpeechTranscriber.isModelReady` reads the filesystem,
  never a cached pref.

Off-device engine proof (the one piece of this build that COULD
be run for real): the exact shipping `org.vosk` classes + JNA +
the small model ran on the build JVM against the Vosk project's
own 16 kHz test recording — model loaded in ~0.5 s and the digits
transcribed coherently with confidence 1.0. That proves the
engine, the model, and the JNA path; it does NOT prove the
Android native-loading path (JNA finding `libvosk.so` in the
APK's `lib/` dir on a real phone) — that is Adam's test, and the
code fails honestly if it doesn't load.

### Sender UX (explicit send, honest states)

Low-data is a mode on the walkie screen, not a replacement:
normal audio bursts are untouched. The section sits under the
PTT button with the explanation copy (synthetic profile, offline
after the model download). The mode switch is a runtime pref
(`KEY_LOWDATA_ON`, default off); turning it on with no model
shows the download block and pauses hold-to-talk until the model
is ready. Press-talk in the mode records locally ONLY — nothing
streams to the mesh or Bluetooth while talking (the record loop
skips streaming; no spurt-start is sent). On release the status
reads "Transcribing on this phone…", then:

- OK → a dialog shows the transcription in an editable field
  with Send/Cancel; a low-confidence note appears when the mean
  word confidence is under 0.5. Nothing goes out until Send.
- EMPTY → "No speech heard — try again, closer to the mic."
- FAILED / engine error → the reason is shown, never a silent
  drop. A second press-talk while transcribing is refused with
  a toast. The existing 0.2 s minimum hold applies to both modes.

### Text transport: the same envelope, one marker

No new transport, no new relay protocol. The relay text envelope
gains one boolean — `{"t":"text","text":"…","ld":true}` — built
and parsed by the pure-Java `LowDataEnvelope` (the single
implementation, harness-tested; WalkiePage's relay send/ingest
now use it instead of inline org.json). Old-client tolerance is
by design: pre-v1.11 receivers read only "t"/"text" and ignore
the unknown key, so a low-data message still shows as plain text
on an old build; ordinary typed texts omit the marker entirely,
so those envelopes are byte-identical to v1.3. Mesh and
Bluetooth 'T' packets carry the same `"ld"` boolean
(`MeshNet.sendText` / `BtWalkie.sendText` overloads; the host
relays 'T' packets verbatim, so the flag rides along); their
`onText` listener signatures gained the flag (WalkiePage is the
only implementer of either). The store-and-forward outbox keeps
the flag per queued text, so a flushed message still arrives
marked. Thread entries persist `"lowData":true` plus the relay
sender pubkey (`"sender"`) for profile lookup on replay.

### Voice profiles: per contact, in the profile

`VoiceProfile` (pure Java): voice name + pitch + rate, clamped
to 0.5–2.0, hand-rolled JSON in the AppListCodec style. Stored
as a nested `"voice"` object on the contact record in
profile.json (`Profile.contactVoice` / `setContactVoice`), so it
travels with the encrypted profile export like the rest of the
contact. A voice name that isn't installed on the reading phone
falls back to the system default voice (`resolve()`), so an
imported profile never breaks speech elsewhere. The editor is
`VoiceProfileActivity` (reached from the contact screen): voice
picker from installed voices, pitch/speed sliders, a Preview
button that speaks a sample line, Save, and Reset. The picker
lists only offline-usable voices — voices declaring network
synthesis are excluded, because a low-data message must be
readable with no connection. Receiving a low-data message shows
the text in the thread (marked "low-data", with a "tap to hear
it" hint) AND reads it in the sender's profile: relay senders
resolve by pubkey, mesh/Bluetooth senders by display name
(best-effort), unknown senders get the default profile. Tapping
a low-data entry replays it. When no TTS engine exists on the
phone, speech reports NO_ENGINE and the text simply stays
visible — speech is an enhancement, never the message itself.

### QUIET is absolute

`SpeakGate` is the single decision point, tested as a full truth
table: QUIET-all silences speech in EVERY case — automatic reads,
manual replays, previews — with no exception path. Automatic
reads additionally need a low-data message and the per-mode
auto-read switch (default ON, `KEY_LOWDATA_AUTOREAD`); manual
replays bypass the switch only. `LowDataSpeaker.speak` reads
both prefs live at speak time, never cached, so toggling QUIET
takes effect immediately. Own messages are never auto-read.

### Connections crash fix (separate fix, folded into v1.11)

Adam: Connections crashes on open in v1.10. Root cause:
`ConnectionsActivity.onResume` registered its receiver with the
2-arg `registerReceiver(receiver, f)`. Before v1.10 the filter
held ONLY system broadcasts (exempt from the Android 13 rule);
v1.10 added two CUSTOM actions to that same filter
(`MeshInternet.ACTION_MESH_STATE_CHANGED`, `ACTION_PEERS`). On
API 33+ (the app targets 34; Adam's phone is Android 16) a
context-registered receiver whose filter is not system-only
MUST be registered with an explicit exported flag — the 2-arg
call throws SecurityException in onResume, crashing the page on
every open. Fix: an SDK-guarded call — API 33+ →
`registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)`,
below 33 → the existing 2-arg call. RECEIVER_NOT_EXPORTED is
correct: both custom broadcasts are sent by the app itself, and
same-app broadcasts still deliver to a not-exported receiver
while other apps cannot spoof mesh state. Nothing else about
the filter or receiver logic changed. The WalkiePage and
MeshInternetService p2pReceiver registrations are
system-actions-only and already fine — left alone. The crash
path cannot run on the JVM: Adam opening Connections is the
test.

### Files

New: `VoiceProfile.java`, `VoskResult.java`, `LowDataEnvelope.java`,
`SpeakGate.java` (pure Java), `SpeechTranscriber.java`,
`LowDataSpeaker.java`, `VoiceProfileActivity.java` (Android),
`harness/LowDataHarness.java`, `harness/org-json.jar` (test-only
dependency for the old-client leg — never shipped),
`libs/vosk-classes.jar`, `libs/jna-classes.jar`,
`native-libs/{arm64-v8a,armeabi-v7a}/{libvosk.so,libjnidispatch.so}`.
Edited: `WalkiePage.java` (mode UI, PTT low-data path,
transcription dialog, receive + speak, thread rendering,
tap-to-speak, send paths, relay ingest), `MeshNet.java`,
`BtWalkie.java` (ld flag + listener signatures),
`WalkieStore.java` (outbox flag), `Profile.java` (voice
accessors + sender lookup), `Prefs.java` (2 keys),
`ContactEditActivity.java` (voice-profile button),
`ConnectionsActivity.java` (the crash fix),
`AndroidManifest.xml` (VoiceProfileActivity only),
`build-apk.sh` (version 14 / "1.11", Vosk/JNA jars on the
compile + dex paths, stored native-lib packaging).

### Verification

- `LowDataHarness` (new, runs the exact shipping classes):
  **131 checks, 0 failures** — profile clamping/round-trips/
  hostile-parse tolerance/fallback resolution, Vosk result
  parse incl. empty/failed/partial + confidence scoping,
  envelope round-trips incl. escaping + strict ld flag +
  old-client readability with a real org.json jar, speak-gate
  truth table incl. QUIET silencing every path.
- All 8 existing harnesses re-run on the new tree, counts
  identical to v1.10: RingMath 101/101, AppListCodec 22/22,
  AudioModem 50/50, Wallet 46/46, Terminal 29/29, AdbConnect
  15/15, Nat 190/190, Relay 28/28 (live relay via the IPv6 proxy).
- Same cert (f646377d…a1c52), versionCode 14 > 13, 24 permissions
  unchanged, VoiceProfileActivity verified in the compiled
  manifest, native libs verified STORED under lib/<abi>/ with
  zipalign -c -p passing, org.vosk + com.sun.jna classes verified
  in classes.dex.
- Vosk end-to-end on the JVM (see above): model + engine +
  JNA proven with a real recording; Android native loading is
  NOT proven.

### Still unproven — Adam's phone test

No on-device test is possible in the VM. First real test: enable
low-data mode (model download with progress), hold to talk,
check the transcription, send, and have the other phone read it
aloud in the sender's voice profile — plus: transcription
accuracy on his voice, TTS voice availability on the A16, the
model download on mobile data, the two-phone low-data exchange
over mesh AND relay, QUIET silencing speech, and Connections
opening without crashing (the v1.10 crash fix).

## v1.10 (2026-11-16) — mesh internet: an offline phone gets internet through a nearby phone (userspace NAT)

Adam: "Can we make a function that creates a mesh network automatically and
if a phone doesnt have connection to wifi or mobile data, it can get the data
from a device that is connected to a mobile network." He first picked the
proxy-gateway shape, then asked how NAT would work, and picked the NAT
version instead — the tun2socks pattern below. APK: 3,040,175 B, sha256
`112b342aefa7626e0b0db2ca4e93983f4d0899ab5f4936f218e5ef9cf64be3b4`,
versionCode 13 / "1.10", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.9.2. NO new permissions (24, unchanged).
NOT uploaded to the launcher site. Untouched, per the brief: wallet, audio
modem/payload/Player/Listener, terminal, pairing, ADB, relay protocol, chat
transport, walkie audio, browser, ring UI — except the Connections/Settings
additions named below.

### Architecture (tun2socks)

Two roles, one phone each; a phone is never both at once (a phone that is
using the mesh cannot re-share it — single hop only, by construction).

GATEWAY (the phone with internet): `GatewayRelay` is a SOCKS5 server —
CONNECT for TCP, UDP ASSOCIATE for UDP (DNS/QUIC) — bound to the mesh
interface address only (bind order: P2P group-owner address when we are the
GO, first IPv4 on a `p2p*` interface, Wi-Fi address; never a wildcard) on
port 8992. `GatewayDiscovery` broadcasts an announcement every 2s on UDP
8991 (`CONTROL-GATEWAY/1`, name + identity public key + TCP port +
transport). A phone advertises only while it has a VALIDATED internet route
(`ConnectivityManager` + `NET_CAPABILITY_VALIDATED`, mobile or Wi-Fi).
`MeshInternetService` (single-thread "mesh-coord" executor) owns discovery,
the P2P group, the relay lifecycle, and NAT coordination; it creates a P2P
group when sharing (preferring group owner) and only removes a group on
destroy if it created it — never a walkie session's group.

CLIENT (the offline phone): `NatVpnService` routes default traffic
(`0.0.0.0/0`) into a TUN interface and runs `NatEngine`, a pure-Java
userspace stack: `NatPackets` (IPv4/TCP/UDP/DNS parse + build), `TcpFlow`
(one TCP terminator per connection: answers SYNs, tracks seq/ack + windows,
one SOCKS CONNECT per connection), UDP flows via one UDP ASSOCIATE session,
and DNS handling. `SocksClient` speaks the auth preamble + SOCKS5 to the
gateway; every mesh socket is `protect()`ed so its traffic bypasses the very
TUN it feeds. IPv4 only. IP fragments, ICMP, and non-IPv4 are dropped
silently (no reassembly in v1). TCP simplifications, all deliberate: no
window scaling / SACK / timestamps, fixed 65535 advertised window,
out-of-order segments dropped + re-ACKed (no reassembly buffer),
fast-retransmit only (3 duplicate ACKs, no RTO timer), no TIME_WAIT, data
buffered pre-connect (64 KB cap) and ACKed only once forwarded. Remote-read
backpressure is v1-simple: the pump thread sleeps while a flow holds more
than 256 KB unacked.

DISCOVERY + ELECTION: the offline phone scans gateway advertisements on
both roads at once — the UDP broadcast reaches P2P-group members and
same-Wi-Fi phones alike. Announcements expire after 10s; the first fresh
advertisement wins; a gateway that failed is skipped for 30s. P2P
auto-reconnect to the last-known gateway runs at most every 15s. First-time
Wi-Fi Direct pairing needs the system's accept tap on each phone — Android
requires it, no code can skip it; persistent groups auto-reconnect after
that. The UI says so honestly.

### The auth handshake (never an open proxy)

Before any SOCKS byte flows, the client must prove it holds a Control
profile identity key (`GatewayAuth`, pure Java, harness-exercised):

1. Server sends a challenge: magic `CGW1` + a fresh 32-byte random nonce,
   generated per connection and never reused.
2. Client replies with its 32-byte public key + a 64-byte Ed25519 signature
   over `"control-gateway/v1" || nonce || clientPub || gatewayPub`. The
   gateway's public key comes from its UDP announcement, so the signature is
   bound to THAT gateway — a captured auth cannot be replayed to a different
   gateway, and the fresh nonce means it cannot be replayed to this one.
3. Server verifies with `RelayKeys.verify`; any failure, timeout, or
   malformed reply closes the connection silently.

Trust model, stated plainly: authentication proves "holds a Control profile
identity key" (the same identity the relay uses) — NOT "is a known contact".
Combined with the mesh-interface-only bind, that is the v1 gate: a stranger
on the open internet cannot reach the relay at all, and a device on the mesh
still needs a Control identity to use it. Restricting to contacts is future
work, not v1 scope. No traffic content is ever logged — the only visible
number is the active client count, for the UI ("Sharing with N phones").

Outbound sockets on the gateway need NO `protect()`: the DNS filter VPN
routes only 10.0.0.1/32, so normal outbound sockets are never captured by
it, and the NAT VPN never runs on a gateway.

Virtual DNS: a client request for 10.0.0.1:53 is rewritten by the GATEWAY
to its upstream resolver (`Prefs.upstreamDns`, default 1.1.1.1), for both
UDP and DNS-over-TCP; UDP replies from that resolver have their SOCKS header
rewritten back to 10.0.0.1:53 so the client's DNS-ID demux matches.

### The VPN-slot interaction (precise)

Android allows ONE active VPN. The rules, implemented in `MeshInternet`
(prefs `KEY_MESH_NAT_ON`, `KEY_FILTER_PARKED`):

- Starting NAT while the filter is on PARKS the filter: the filter service
  is stopped, `KEY_FILTER_PARKED` is set, and NAT runs WITH blocklist
  enforcement included — the engine checks EVERY UDP packet to port 53
  against `Blocklist.isBlocked` (not just virtual-DNS ones), answering
  blocked queries with a local NXDOMAIN. Ad blocking is never silently
  dropped. The blocked counter (`KEY_BLOCKED_COUNT`) is shared.
- Stopping NAT RESTORES the filter if it was parked — but only when VPN
  consent is already granted (`VpnService.prepare()` returns null). If
  consent was revoked, the filter stays off and the audit log says so.
- Turning the filter on while NAT runs stops NAT first WITHOUT restoring
  (`SettingsFilterActivity.toggleFilter` calls `MeshInternet.stopNat(...,
  restore=false)`; the filter start that follows takes the slot).
- If the system revokes the NAT VPN, `NatVpnService.onRevoke` clears
  `KEY_MESH_NAT_ON` and restores a parked filter by the same rule.
- `MeshInternet.reconcile()` is the backstop: any entry point that runs
  while NAT is not running restores a parked filter (covers the
  system-kill path). `MainActivity.onResume` calls it.
- The Connections card always states which mode holds the slot, truthfully
  (mesh internet with blocking included / ad filter / free).

NAT applies the same per-app bypass list as the filter, and disallows the
app's own package, so Control's own traffic never loops through the mesh.

### UI + defaults (Connections screen, "Mesh internet" card)

- Gateway status: providing / using (with the gateway phone's name) / none
  found, plus the limits in plain copy: first pairing needs a tap; calls and
  games may suffer; IPv4 only; single hop; both phones need Control running.
- "Share mobile data with the mesh" — DEFAULT OFF, with the cost stated on
  the toggle (spends the owner's data and battery). No confirm dialog; the
  toggle is the opt-in.
- "Use mesh internet when offline" — DEFAULT ON (the automatic behavior
  Adam asked for).
- "Find nearby phones" button (P2P peer dialog; connecting saves the peer
  as the last-known gateway for auto-reconnect) and a "Grant VPN permission"
  button visible only in the NEEDS_CONSENT state.
- Audit lines (`OwnLog`, "Mesh internet"): gateway relay start/stop, NAT
  connect/disconnect with the gateway NAME only, failure reasons — never
  traffic content. No ring leaf was added (ring UI untouched).

### Files

New: `NatPackets.java`, `Socks5.java`, `GatewayAuth.java`, `TcpFlow.java`,
`NatEngine.java`, `SocksClient.java` (pure Java), `GatewayRelay.java`,
`GatewayDiscovery.java`, `MeshInternet.java`, `MeshInternetService.java`,
`NatVpnService.java` (Android), `harness/NatHarness.java`. Edited:
`Prefs.java` (6 keys), `ConnectionsActivity.java` (mesh card + receiver +
VPN consent), `SettingsFilterActivity.java` (stop NAT before filter start),
`MainActivity.java` (one `reconcile` call), `AndroidManifest.xml` (two
services: `NatVpnService` with `BIND_VPN_SERVICE` + specialUse/`vpn`,
`MeshInternetService` with specialUse/`mesh-internet` — mirroring the
v1.9.2 foreground-service fix; `"vpn"` is not a valid type value),
`build-apk.sh` (version 13 / "1.10").

### Verification

- `NatHarness` (new, runs the exact shipping classes): 190 checks, 0
  failures — packet parse/build + malformed/truncated/fragment rejection,
  checksums, TCP transitions (handshake, data both ways, FIN/RST paths,
  fast retransmit, MSS cap, window respect, out-of-order/duplicate drop,
  pre-connect buffering + cap, sequence wraparound, timeout sweep), SOCKS5
  framing, auth accept/reject/replay/wrong-gateway/truncated/tampered,
  client session over piped streams against a fake gateway (no sockets:
  JVM IPv4 loopback is unusable in the build VM), and full engine flows
  with a fake host (RST for stray segments, fragment/ICMP drops, DNS
  NXDOMAIN-vs-forward incl. blocklist hits on ANY resolver, DNS-ID demux,
  idle expiry, shutdown). The harness caught one real bug during the
  build: duplicate-ACK counting was polluted by ACKs arriving while
  nothing was unacked, which would have retransmitted early — fixed in
  `TcpFlow.processAck` (only ACKs arriving with unacked data count).
- All 7 existing harnesses re-run on the new tree, counts identical to
  v1.9.2: RingMath 101/101, AppListCodec 22/22, AudioModem 50/50, Wallet
  46/46, Terminal 29/29, AdbConnect 15/15, Relay 28/28 (live relay via the
  IPv6 proxy).
- Same cert (f646377d…a1c52), versionCode 13 > 12, 24 permissions
  unchanged, both new services verified in the APK's compiled manifest.

### Still unproven — Adam's two-phone test

No on-device test is possible in the VM, and NOTHING in the gateway path
has run on real hardware: an offline phone browsing through the gateway
phone's mobile data, the first-pairing accept tap on each phone,
auto-reconnect after pairing, the relay binding to the real P2P interface,
UDP ASSOCIATE carrying real DNS/QUIC, the VPN-slot handoff in both
directions on a real phone, and battery behavior while sharing. The
harness bounds the packet/state-machine risk; it proves none of the
above. First real test: two phones, one with mobile data and sharing ON,
one offline with use-ON, both running Control.

### Future work (documented, not built)

Multi-hop relay; restricting the relay to known contacts; an RTO timer if
loss ever shows up on the mesh path; IPv6.

## v1.9.2 (2026-11-16) — three on-device bug fixes: ad-filter crash, menu top-corner open, wallet save failure

Three bugs Adam reported the same night on v1.9.1, all fixed in one
build. APK: 3,003,311 B, sha256
`74f73d28fb05abd591a7f2678c0b912f13032d9018191a66b77091b7853c69ea`,
versionCode 12 / "1.9.2", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.9.1. One new permission:
**FOREGROUND_SERVICE_SPECIAL_USE** (normal-level, no runtime
request). NOT uploaded to the launcher site. Nothing else
changed: no ring-behavior, audio, terminal, pairing, ADB, relay,
chat-transport, walkie, browser, or cache changes beyond the
positioning path named below.

### Fix 1 — ad filter crash: missing foreground service type

Adam: "It crashes when I turn on ad filter." Root cause:
`DnsVpnService` calls `startForeground()` but its manifest
declaration had NO `android:foregroundServiceType`, and the app
targets SDK 34. On Android 14+, starting a foreground service
without a declared type throws
`MissingForegroundServiceTypeException` — the crash fires the
moment the filter starts. Latent since v1.0; nothing in v1.9 /
v1.9.1 touched this path. It is the ONLY `startForeground`
caller in the tree (`NotificationHoldService` never calls it),
so the fix is scoped to this one service. The exact manifest
lines shipped:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
...
<service
    android:name=".DnsVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="vpn" />
```

`specialUse` is the type: `vpn` is NOT a valid
`foregroundServiceType` value (AAPT rejects it), so the VPN
nature rides in the subtype property instead. The existing
2-argument `startForeground(id, notification)` call picks up the
manifest-declared type automatically — no Java change was needed
for the type itself. Verified in the built APK via `aapt2 dump
xmltree`: `foregroundServiceType=0x40000000` (SPECIAL_USE) plus
the subtype property, and the new permission in the badging
list.

Hardening in the same fix (`DnsVpnService.establish()` only):
the `b.establish()` call is wrapped in try/catch for
`SecurityException` and `IllegalStateException` (e.g. VPN
consent revoked) — it logs and returns false, so
`onStartCommand`'s existing failure path runs (shutdown, filter
stays OFF) instead of crashing the service. No other behavior
change.

### Fix 2 — circle menu opens in the top corner on first open

Adam: "the menu opens in the top corner initially rather than
where im pressing." Root cause: `CircleMenuView` starts GONE,
and a GONE child of the stage FrameLayout is never measured —
so on the FIRST `showAt()`, `getWidth()`/`getHeight()` are 0,
the clamp `clamp(x, reach, 0 - reach)` collapses to `reach` on
both axes, and the ring centers at (~112dp, ~112dp): the
top-left corner. Later opens were fine because the view had
been measured while visible. The fix resolves the clamp bounds
before clamping: `showAt` now uses `boundsWidth()` /
`boundsHeight()` — the view's own size once laid out, else the
parent's size (the menu is MATCH_PARENT at the parent's origin,
so they are the same, and the parent is laid out long before
any long-press can fire), else the display size as a last
resort. The clamp math itself moved to the pure-Java
`RingMath.clampCenter` (same rule as the old private clamp, so
behavior with real bounds is identical), and `onSizeChanged`
re-clamps the center while open so a rotation can never strand
the ring off-screen. First open and every open: centered on the
press point, clamped fully on-screen, from the first frame. All
v1.9/v1.9.1 ring behavior is untouched (1000ms dwell, rotation
paging, release-to-open, paging/dots, hub-back, fast paths) —
only the positioning/show path changed. The harness gained a
CENTERING leg (14 checks): interior presses keep their exact
position, all four edges and all four corners, overshoot
shifting, the too-small-viewport rule, a grid sweep proving the
full ring stays inside the viewport, and the zero-size case
pinned as the documented trap (the Android-side bounds
resolution itself cannot run on the JVM and is verified by
inspection + build).

### Fix 3 — wallet card save failure: wrong cipher provider

Adam: adding a card failed with "Couldn't save that card."
Root cause: `WalletCrypto` created its cipher with
`Cipher.getInstance("AES/GCM/NoPadding")` and NO provider,
which resolves to the default JCE provider. The wallet key is
an Android Keystore key — opaque, non-extractable — and can
ONLY be used through ciphers from the "AndroidKeyStore"
provider; the default provider's init threw
`InvalidKeyException` on every encrypt and every decrypt,
deterministically, on every device. Key CREATION worked fine;
the failure was at cipher USE. The WalletHarness missed it
because it runs the same code with a JVM software key, which
the default provider accepts. The fix (shape b of the two
offered): `WalletCrypto.newCipher()` tries
`Cipher.getInstance(TRANSFORMATION, "AndroidKeyStore")` first
and falls back to the default provider only on
`NoSuchProviderException` (the plain JVM, where the Keystore
provider does not exist) — so the harness still runs the exact
shipping code. The encrypt/decrypt logic and the wire form
(IV ‖ ciphertext+tag) are byte-identical, so anything already
sealed stays readable; `add()` failed at `seal()` before any
write, so no cards could ever have been stored, no migration
was needed, and the existing keystore key was NOT deleted or
regenerated. Error visibility: the save catch (which covers
both add and edit) and the delete catch in `WalletActivity`
now go through `showStoreError`, which logs and toasts the
REAL exception (class simple name + message, capped at 160
chars) instead of the bare generic toast that hid this bug's
cause completely. Validation toasts, the reveal gate, the Pay
handoff, and the file format are untouched.

### Verification (v1.9.2)

- `./build-apk.sh` clean (aapt2 accepts the manifest property +
  specialUse type); badging versionCode 12 / "1.9.2"; apksigner
  verifies with the v1 certificate; permission list is 24 (23
  + FOREGROUND_SERVICE_SPECIAL_USE).
- All 7 harnesses green on the new tree:
  RingMathHarness **101/101** (was 87/87; +14 centering checks),
  AppListCodecHarness 22/22, AudioModemHarness 50/50,
  WalletHarness 46/46, TerminalHarness 29/29,
  AdbConnectHarness 15/15, RelayHarness 28/28 (live relay).
- NOT verifiable off-device: the filter toggle on Adam's phone
  is the first real test of the foreground-service fix; the
  first menu open on his phone is the first real test of the
  positioning fix; adding a card on his phone is the first real
  test of the Keystore-provider path, which cannot run on the
  JVM at all.

## v1.9.1 (2026-11-16) — tighter rings, rotational paging, release-to-open, 1-second dwell

Four refinements to the v1.9 rings, all from Adam's on-device
feedback the same night: (1) the icons sit closer to the center
circle; (2) going around the ring clockwise advances a page,
counter-clockwise goes back; (3) in a sub-ring, the dwell gesture
opens a leaf on release — no tap required; (4) the dwell hold is
1 second instead of 2. APK: 3,003,311 B, sha256
`168a541285d55962e107106c6e66aa049e4cea9337b92000a9f1faba493564c3`,
versionCode 11 / "1.9.1", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.9. No new permissions (23,
same list; the manifest is untouched). NOT uploaded to the
launcher site. Ring UI + geometry only: no audio, terminal,
pairing, ADB, relay, chat-transport, walkie, browser, wallet, or
app-list-cache changes.

### Tighter geometry (RingMath constants, one source of truth)

Ring radius 106dp → **80dp**, item radius 28dp → **24dp**
(48dp diameter, the Android minimum touch target), page size 10
→ **8**, hub unchanged at 37dp. Solved jointly against the chord
rule: 8 items sit 45° apart on a ~61.2dp chord (13.2dp clear of
the 48dp diameter), the 9-item main ring sits 40° apart on a
~54.7dp chord (6.7dp clear), and 11 items would overlap. The hub
clears the items' inner edge by 19dp, and the 1.6× item hit zones
(38.4dp) stop 4.6dp short of the hub, so hub taps and icon taps
never compete. The radii live as named constants in RingMath
(`RING_RADIUS_DP`, `ITEM_RADIUS_DP`, `HUB_RADIUS_DP`);
CircleMenuView converts them with Ui.dp and the harness
references them directly — no second copy. The dwell progress
arc (item radius + 5dp) stays readable at the new size. 50
channels now take 7 pages, Settings 3.

### Rotational paging (RingMath.Rotation, all paged sub-rings)

Dragging around the hub in the ring band turns pages: clockwise
is next, counter-clockwise is previous, one page per **90°** of
accumulated rotation — a quarter-turn per page, four pages per
revolution, ~126dp of finger travel along the ring. 90° is
deliberate without demanding a full circle, and short of the
135°+ a three-icon slide spans, so nearby-leaf selection slides
stay selections. Each turn fires a haptic tick (the TunerView
detent pattern, 12ms/60) and the page dots update; continued
rotation keeps turning pages, the remainder past a threshold
carrying into the next step. The gesture belongs to the ring, so
it works identically on Apps, channels, Settings, Chat, and
every other paged sub-ring, in both drag mode (the still-down
dwell gesture) and tap mode.

What counts as rotation is decided by the pure-Java helper, fed
every move with the pointer's angle and radius about the hub
(angles in the screen atan2 convention, so increasing angle IS
visually clockwise):
- The pointer must stay in the band — ring radius −16dp to the
  icons' outer edge (64–104dp). Outside it, no accumulation and
  the run re-anchors on re-entry, so radial drags in from the hub
  and the dwell hold contribute nothing.
- The radius must not swing more than 24dp inside one run. A
  finger following the circle holds its radius; a straight chord
  across the ring sweeps a wide radius range, so chords never
  page (verified for 13 horizontal and 4 vertical offsets).
- Direction reversal unwinds the accumulation (net angular
  displacement) instead of banking progress both ways.
- A stationary pointer never fires, even with a remainder banked
  from a fast flick.

Swipe-paging decision: the v1.9 horizontal swipe is REMOVED. A
swipe across the ring is geometrically a partial rotation, so
keeping both would double-fire one gesture two ways; rotation
subsumes the swipe's job on every paged ring and pages further
in one gesture. The page dots stay, tappable, as the precise
alternative. The fast selection paths are untouched.

### Release-to-open in sub-rings (RingMath.Gesture)

One continuous gesture: press and hold 1s (ring morphs), slide
the still-down finger onto a leaf, release — the leaf opens, no
lift-and-retap, no second tap. The same holds for a tap-mode
press-hold-dwell-drag-release. Precedence, enforced by the
Gesture helper the view drives (and the harness drives through
the same sequences):
1. If the gesture turned a page, the release NEVER chooses —
   paging wins over selecting. The ring stays open; the next tap
   chooses.
2. If the gesture morphed and the pointer never moved beyond the
   12dp tolerance after the morph, the release NEVER chooses —
   the leaf that landed under the finger must not open by
   accident. The ring stays open for a drag or a tap.
3. Otherwise the release chooses the leaf under the finger.
A highlight change alone does NOT arm a choice: v1.9 cleared its
morph guard on any highlight change, so a stationary finger
receiving move events could defeat the guard — the Gesture
helper arms only on genuine post-morph movement. Release-at-hub
then tap keeps working (the tap rule applies to gestures that
never morphed), and the main ring's drag-release selection is
unchanged. No new audit lines.

### Dwell: 1000ms

`RingMath.DWELL_MS` is 2000 → **1000**, the one constant shared
by the menu and the progress arc, so the arc still fills exactly
over the hold. The 12dp wobble tolerance and the pager's 450ms
arbitration are untouched — 1000ms stays safely above both, so
quick drag-release selection keeps working exactly as before.
The harness references the constant; only one check pins the
shipped value.

### Verification (v1.9.1)

- `harness/RingMathHarness.java`: **87/87** (was 40/40) —
  geometry at the new radii (chords, hub clearance, hit zones),
  paging at 8/page (counts, ranges, 1..60 coverage, clampPage),
  dwell at DWELL_MS (exact fire, latch, tolerance, tap/drag,
  linear progress), rotation (threshold, direction, wrap,
  reversal, band exit, radius wobble, chords, stationary
  no-fire), gesture sequences (all five required cases plus the
  stationary-move regression and clean-restart cases).
- Regression suites re-run on the new tree, all green:
  AppListCodecHarness 22/22, AudioModemHarness 50/50,
  WalletHarness 46/46, TerminalHarness 29/29,
  AdbConnectHarness 15/15, RelayHarness 28/28 (live relay).
- Same signing cert as v1.9 (SHA-256 f646377d…a1c52),
  versionCode 11 > 10, permission list unchanged (23),
  apksigner verifies; dex spot-checked for the new
  RingMath$Rotation and RingMath$Gesture classes.
- NOT verifiable off-device: the tighter ring's look and feel,
  the rotation gesture's feel (threshold, band, haptic), the
  release-to-open flow in the hand, and the 1-second dwell.
  Adam's Galaxy A16 is the first runtime test for all of it.

## v1.9 (2026-11-16) — circle-menu sub-rings, cached app list, streaming audio playback

Three pieces in one build, all Adam's orders: (1) holding a finger on
a circle-menu icon for 2 seconds morphs the ring into that
destination's children; (2) the installed-app list is scanned once
and cached instead of re-scanned on every open; (3) the v1.8 audio
share sound failed on his Galaxy A16 ("AudioTrack would not
initialize") — the player is rewritten streaming. APK: 3,003,311 B,
sha256 `82a35b0388cc3fc111e4a8f208aa70ddbd7c19ac7a29c22d5232c527cdd19230`,
versionCode 10 / "1.9", same signing key (cert SHA-256 f646377d…a1c52)
— installs over v1.8. No new permissions (23, same list as v1.8; the
new package-change receiver needs none). NOT uploaded to the launcher
site (site still serves v1.5 until Adam directs an upload). No
terminal, pairing, ADB, relay-protocol, chat-transport, walkie
audio-path, browser, or wallet changes beyond the ring leaves that
open those destinations.

### Sub-rings (CircleMenuView + RingMath + RingItems)

Dwell mechanics: a pointer held stationary over a main-ring icon for
2000 ms opens that icon's sub-ring. Stationary means within a 12 dp
tolerance of the anchor point — drifting restarts the clock, a small
wobble does not. A progress arc fills around the held icon over the
2 s (redrawn on a 50 ms tick) so the wait is visible. The dwell
state machine lives in `RingMath.Dwell` (pure Java, caller-owned
clock) and the timer only ever runs while a finger rests on a
main-ring icon. The fast paths are untouched: a quick drag-onto-icon
+ release and release-at-center-then-tap never wait on the timer,
and the pager's 450 ms long-press arbitration (which protects
Apps-cell starring) is not involved. Right after a morph, the still-
down finger is suppressed until it moves 12 dp or lifts — releasing
it must not choose whatever leaf landed under the finger.

Paging decision: PAGING, not a rotating ring. Dozens of icons cannot
fit one ring legibly, and a rotating ring moves targets under the
finger; pages keep every target stationary and tappable. Page size
is 10, set by the chord rule at the shipping radii (ring 106 dp,
item 28 dp): 10 items sit 36° apart on a ~65.5 dp chord against a
56 dp item diameter, while 12 would sit on ~54.9 dp and overlap.
Pages turn by a horizontal swipe across empty ring space (tap mode
only) or by tapping the page dots under the hub. Geometry, paging
math, and the dwell machine are pure Java in `RingMath.java` so the
JVM harness exercises the exact shipping code.

Contents (from each destination's real data — nothing invented;
`MainActivity` implements `RingItems.Source` and every leaf runs the
same code path as doing it from the sheet, tuner, or list):

- Apps: every launchable app from the shared cache, real icons
  resolved lazily per visible page (see below). A background
  refresh re-renders the ring if the list changed while it is open.
- Settings: the hub's 18 navigation rows in hub order with the
  hub's glyphs. Switch rows (clock, quiet-all, ad filter, SMS
  remote control, JavaScript) stay in Settings — the ring
  navigates, it never flips a setting — "Turn off deep control"
  stays with its confirm dialog, and the profile header card is
  folded into "Profile & backup" (same screen).
- Walkie: all 50 channels as numbered chips over 5 pages; private
  slots show their number in accent, labels are the slot names
  (Open/Private when unnamed). Tapping tunes through
  `WalkiePage.tuneToChannel` (the tuner's own calls) and slides
  the pager to Walkie.
- Home: the starred favorites, same store and skip-if-uninstalled
  rule as the Home page.
- Browser: saved bookmarks; choosing one opens it on the Browser
  page via `BrowserPage.openUrl`.
- Chat: the conversation list via `ChatPage.ringConvos`
  (built from local data when the page never showed); locked rows
  show the lock glyph and tap through `openConvoById`, the same
  path as a list tap (locked rows show their note).
- Activity: the Control Room's four tabs (via the new
  `ControlRoomActivity.EXTRA_SECTION`, which lands on the tab) plus
  Deep view and Governed controls, the two destinations the
  Control Room itself links to.
- Connections: the seven radio cards. Navigation only — each leaf
  opens ConnectionsActivity scrolled to its card (new
  `EXTRA_SECTION`), flipping nothing.
- Wallet: stored cards by nickname (or network + last 4). Tapping
  one opens the wallet; nothing is revealed from the ring and the
  number is never unsealed here.

Navigation: the hub shows the ring's name (or the highlighted
leaf's full label) and acts as Back — tap it, or press Back, to
return to the parent ring; Back on the main ring dismisses, as
before. Tapping outside dismisses from any level. Sub-ring
selection works both ways like the main ring (drag-onto + release,
or tap). Empty rings show an honest hint under the hub ("No
favorites yet…", "No bookmarks yet", …). No new audit lines:
sub-ring opens and leaf choices are navigation; launching and
tuning behave identically to the sheet/tuner because they ARE the
sheet/tuner code paths. New files: `RingMath.java`,
`RingItems.java`; `CircleMenuView` rewritten around a parent/page
model with the main-ring behavior preserved; `MainActivity` gains
the source, the leaf router, and the Back-level handling.

### Cached app list (AppListStore + AppListCodec + PackageChangeReceiver)

Adam: "make the app list store into a list so it doesnt have to scan
each time." The PackageManager query (resolve + label) now runs once
and persists to a private JSON file, `app-list-cache.json`: an array
of `{"p": package, "l": label}`. `AppListStore` is the single source
— the Apps sheet, the Apps sub-ring, the pickers, and Deep controls
all read it; there is no second scan anywhere. `cached()` returns
synchronously: the in-memory snapshot, else the file, else one scan
(first run, or a missing/corrupt file — the file is rewritten either
way). After the cached list shows, a single-flight background
refresh re-scans and, only when the diff (added / removed /
relabeled, order-insensitive) is non-empty, rewrites the file, swaps
the snapshot, and notifies listeners on the main thread, so visible
UI updates only on a real change. `PackageChangeReceiver`
(manifest-registered, not exported, for PACKAGE_ADDED / REMOVED /
CHANGED / REPLACED with the `package` scheme) kicks a refresh on any
install, removal, update, or enable/disable — uninstalled apps
disappear, new ones appear, no stale entries left showing (the
REMOVED half of a replace is skipped; REPLACED follows it). The
codec and diff are pure Java in `AppListCodec.java`, including a
hand-rolled strict JSON parser: any malformed shape throws and the
store rescans, so a corrupt cache can never show wrong data.

Icons were never cached before this version — the old scan loaded
every icon eagerly (`loadIcon` per row). Icons are still never
persisted (size + staleness); they now load lazily per row through
`InstalledApps.iconFor`, a 128-entry in-memory cache that hands each
caller a fresh drawable from the shared constant state (same bitmap,
independent bounds, so one icon can sit in a row and on the ring at
once). Package changes evict that package's cached icon. `Entry`
lost its eager `icon` field; the sheet, picker, and Home favorites
all call `iconFor`.

### Streaming audio playback (AudioKeyAudio.Player only)

Adam's v1.8 report: playing a share sound fails with "AudioTrack
would not initialize" on his Galaxy A16. The v1.8 player built one
MODE_STATIC track sized to the whole frame; the static allocation
would not initialize on that device. The Player is rewritten to
MODE_STREAM with the same public contract (`play(short[], Cb)`,
`onProgress`/`onDone`/`onError`, `stop()`, `isPlaying()`), so
`WalkiePage` and `AudioKeyPlayActivity` needed no changes: the
buffer comes from `getMinBufferSize` (floored at 8 KB), `play()` is
called before writing, and the worker thread writes the PCM in 4 KB
chunks, checking EVERY write return — a negative return stops
playback and reports the exact error code. Every failure message
carries the details (track state, buffer size, sample rate, write
code, bytes already written) so the next failure is diagnosable from
the screen text alone. `stop()` during streaming halts promptly
(generation counter suppresses the superseded worker's callbacks;
release only clears the field when it still holds that worker's
track), and `onDone` fires only after the playback head reaches the
final frame. USAGE_MEDIA, CONTENT_TYPE_SONIFICATION, 16 kHz, mono,
PCM16 are unchanged. `AudioModem`, `AudioKeyPayload`, and the
Listener are untouched. AudioTrack cannot run on the JVM — Adam's
phone is the test for this fix.

### Verification (v1.9)

- New `harness/RingMathHarness.java`: 40/40 — geometry (angles,
  chord rule, hit testing), paging (counts, boundaries, clamping,
  1..60 coverage), dwell (fires at exactly 2000 ms, movement
  resets, wobble does not, tap/drag never fire, linear progress).
- New `harness/AppListCodecHarness.java`: 22/22 — round-trips
  (escapes, unicode, control characters), 20 malformed shapes +
  null input rejected, diff (add/remove/relabel, order-insensitive,
  empty edges).
- Regression suites re-run on the new tree, all green:
  AudioModemHarness 50/50, WalletHarness 46/46, TerminalHarness
  29/29, AdbConnectHarness 15/15, RelayHarness 28/28 (live relay).
- Same signing cert as v1.8 (SHA-256 f646377d…a1c52),
  versionCode 10 > 9, permission list unchanged (23).
- NOT verifiable off-device: the dwell feel and progress arc, the
  paging gestures, the ring visuals, cache behavior across real
  installs/removals, and the audio fix itself. Adam's Galaxy A16
  is the first runtime test for all of it.

## v1.8 (2026-11-15) — all shared keys travel by sound, not QR

Adam's order: every key shared between phones moves from QR scanning
to audio. That covers all THREE key flows in the tree — the brief
named two, but the `control-chat` key share in ChatThreadView is a
third key flow and "all" means all: (1) `control-contact` v2 cards
(my card + per-contact exchange cards), (2) `control-channel`
private-channel join codes (pinned block + full screen + Channels
grid), (3) `control-chat` chat keys. The profile QR/file transfer
STAYS — the whole encrypted profile is far too large for audio —
so `ScanActivity`, `QrRender`, and the profile branches of
`ImportActivity`/`TransferActivity` are untouched, and a key QR
scanned at Import now gets guidance ("keys travel by sound now")
instead of joining. APK: 2,991,023 B, sha256
`7859927dc796420cc71184138a04c7009abb0ed9c029538f4a046e19519c0bf0`,
versionCode 9 / "1.8", same signing key (cert SHA-256 f646377d…a1c52)
— installs over v1.7. No new permissions (badging list byte-identical
to v1.7): RECORD_AUDIO was already declared for the walkie. No
terminal, pairing, ADB, relay-protocol, chat-transport, walkie
audio-path, browser, or wallet changes.

### The modem (AudioModem.java, pure Java)

Dual-tone 16-ary FSK, no native code, no libraries: each 32 ms symbol
(512 samples at 16 kHz) carries one nibble as one tone from a low
group (1250/1450/1650/1850 Hz) plus one from a high group
(2150/2350/2550/2750 Hz), with 4 ms raised-cosine edge ramps. Frame:
100 ms silence, 8 alternating preamble symbols (0x0/0xF), sync
(0x6, 0x9), length byte, payload, CRC-16/CCITT, 50 ms silence.
Payloads are compact BINARY (AudioKeyPayload.java): raw 32-byte
keys, length-prefixed names capped at 64 UTF-8 bytes — no JSON or
base64 on the air. On receipt the fields are rebuilt into the exact
JSON text the old QR flows produced, and the existing resolution
runs unchanged: ChannelInvite.join (same wireId/slot placement),
ContactCard.apply (same first-card-wins), the chat-key filing.
Measured air time (harness): contact card 5.7 s, channel invite
3.5 s, chat key 3.5 s — the play screen states the measured
seconds for the payload it carries.

Decoding assumes nothing about the two phones' sample clocks: the
decoder classifies the stream on a 64-sample fine grid with
Goertzel magnitudes (phase-insensitive, so a 1% clock offset only
shifts tones ~1% against 200 Hz spacing), then segments the fine
nibble stream into symbols by RUN SPAN — a run is measured from
its first clear step to the next run's first clear step, so the
unclear boundary windows that echo creates belong to a run
instead of vanishing (counting only clear steps under-measures
symbols under echo and was the one bug the echo leg caught).
Symbol length is re-estimated from the 8 preamble runs (median).
A CRC failure is reported, never a wrong payload; the decoder
keeps listening and the sender replays.

### The screens

- `AudioKeyPlayActivity` (one generic screen, factories per kind):
  title + the kind's warning copy, volume-up guidance, the
  measured seconds, a Play button with a progress bar, replay.
  The channel factory keeps the old invite screen's "this sound
  IS the key" warning and transport-honesty note verbatim in
  substance. Playback stops on pause/destroy.
- `AudioKeyListenActivity` (one generic screen, factories per
  context): mic level bar, "Listening…" status, 30-second timeout
  with a Listen again button, wrong-kind arrivals named honestly
  ("That was a contact card — still listening for a channel
  invite"), CRC failures named honestly with "play it again"
  guidance. The microphone is released on success, timeout,
  pause, and destroy. A denied mic permission degrades to
  guidance, never a silent failure. The decoded key returns to
  the caller as the legacy JSON text.
- The Walkie page's pinned block is now a play affordance in the
  same bottom position (pager chrome, as before): tap plays the
  tuned channel's join sound inline, with progress and a Stop
  state; playback stops when the page hides. "Show join code"
  is now "Share join sound" (full screen); "Scan invite" is
  "Listen for invite". Channels, Contacts, ContactEdit, and Chat
  ("Scan" → "Listen") follow the same pattern; MainActivity
  routes the listen results exactly as it routed scan results.
- Audit: "Key share" area, origin UI, identifiers only — "Played
  Channel join sound for …", "Heard contact card for <name>".
  Key material has no path into the log.

### Removed (keys only)

- `ChannelInviteActivity.java` deleted (replaced by the play
  activity); removed from the manifest.
- `ChannelInvite.payload()`, `ContactCard.myPayload()`,
  `ChatPage.chatKeyPayload()` deleted — the binary builders in
  AudioKeyPayload replace them; `parse`/`join`/`apply`/
  `parseChatKey` stay, fed by the listen results.
- The Import scanner's key branches now guide instead of joining.
- Shared QR infrastructure (`QrRender`, `ScanActivity`, ZXing)
  stays for profile transfer; grep-verified: the only remaining
  QrRender/ScanActivity references are ImportActivity and
  TransferActivity.

### Verification

`./build-apk.sh` clean (R8 dexing); badging versionCode 9 / "1.8";
apksigner verifies with the v1 certificate; manifest xmltree
confirms both audio activities and no ChannelInviteActivity; dex
spot-checked for the five new classes and zero
ChannelInviteActivity references; permission list byte-identical
to v1.7. New `harness/AudioModemHarness.java` **50/50**: bit-exact
round-trips for all three kinds fed in 64/320/8192-sample chunks;
JSON reconstruction byte-identical to the legacy shapes including
a hostile-name escaper leg; decode at 20/10/5/0 dB SNR, 1.0x/0.2x/
0.05x amplitude, ±0.5%/±1% clock offset, 30 ms echo at 0.3 and
0.5 gain, 80 ms echo at 0.2 gain, and all three impairments
combined; noise before/after; silence and loud noise yield no
false frame; a corrupted symbol yields exactly one CRC error and
no payload; a CRC failure followed by a replay in one listen
yields one error plus the frame; two back-to-back frames both
decode; 8 random payloads round-trip; limits (empty/oversize/
malformed/truncated payloads, 64-byte truncation at character
boundaries). Regression, freshly compiled on the new tree:
**WalletHarness 46/46**, **TerminalHarness 29/29**,
**AdbConnectHarness 15/15**, **RelayHarness 28/28** against the
live relay. **No on-device test exists in this VM — Adam's two
phones are the first real acoustic test.** What he should check:

1. Two phones, Control open on both, near each other, quiet room:
   Walkie page on a private channel → tap the bottom block; the
   other phone on Walkie → Listen for invite → joins, tuned, and
   says which slot it landed in.
2. Contacts → My contact card → Play the sound; other phone →
   Listen for a card → contact saved with relay key + DM key.
3. Chat thread → Share key → Play; other phone → Chat → Listen
   → chat key saved (member-add still needed, as before).
4. Wrong-kind: play a contact card while listening for a channel
   invite — the listener names it and keeps listening.
5. Noisy room / phones farther apart: expect a CRC retry message
   and a clean decode on replay, closer and quieter.
6. Import → Scan a QR code still takes profile codes; a key QR
   there now explains keys travel by sound.
7. Activity log shows the play/hear events with names/numbers
   only — no key material.
8. Known limits, by design: ~4–6 seconds per key; both phones
   must have Control open; the mic permission gates listening;
   profile transfer still uses QR/file.

The launcher site still serves v1.5 — it updates only at Adam's
direction, per the standing release step.

## v1.7 (2026-11-15) — wallet page: card storage + Pay handoff

Adam's scope, picked 2026-11-15 after the straight answer: tap-to-pay at
store terminals needs card-network tokenization no independent app can
self-serve, so the wallet stores cards securely and hands the actual
payment to the phone's real wallet app. **No HCE / NFC payment code
exists in this app** — the NFC permission in the manifest predates the
wallet (profile transfer) and nothing wallet-related touches NFC. APK:
2,974,639 B, sha256
`dce9a3a76966bdc85b1c685a431df7d1578229dce568414b7a814bbba4bc173c`,
versionCode 8 / "1.7", same signing key (cert SHA-256 f646377d…a1c52)
— installs over v1.6. One new permission: **USE_BIOMETRIC**
(normal-level, no runtime request) for the credential gate. No
terminal, pairing, ADB, relay, chat, walkie, or browser changes.

### Placement — 9th circle-menu icon

Wallet is `CircleMenuView.ITEM_WALLET` (8th index, 9th icon), with a
new billfold glyph in `PageGlyphs`. The ring geometry is generic
(360° / count), so nine icons sit 40° apart: chord ≈ 72.5dp against
a 56dp item diameter — no overlap, and hit-testing picks the
nearest icon. MainActivity routes the choice to WalletActivity.
No fallback was needed.

### Storage — device-local, keystore-sealed

`WalletStore` keeps one JSON file, `wallet-cards.json`, in
app-private storage. It is NOT part of the portable profile:
profile export/import/wipe touch only `profile.json` (verified in
the code), so cards never travel with a profile and a profile wipe
leaves the wallet alone; the screen says so. Each card stores
nickname, cardholder name, network, expiry, and last 4 in the
clear; the full number is stored ONLY as AES-256-GCM ciphertext
(12-byte random IV per card, IV‖ct‖tag, base64) under a
Keystore-backed key (alias `control-wallet`, 256-bit, GCM,
randomized encryption required). The cipher core is the pure-JCE
`WalletCrypto` (key supplied by the caller), so the harness proves
the exact shipping encrypt/decrypt with a JVM key while the app
supplies the Keystore key. Writes are atomic (tmp + rename, the
OwnLog pattern). **CVV is never collected** — no field for it
exists anywhere. The key is deliberately NOT bound to user
authentication: the reveal gate is enforced by the app at display
time (below), and key-level auth would add CryptoObject plumbing
whose failure modes cannot be proven off-device; the keystore
still means the ciphertext is useless off this device. A stored
file that cannot be parsed surfaces an honest error with a
"Start fresh" recovery instead of silently dropping the wallet.

### The screen (WalletActivity)

Space-theme tokens, same visual language as the rest of the app.
Header copy (verbatim): "Your cards live only on this phone,
sealed with a key Android's keystore holds. Control never stores
the security code, and cards never leave in a profile export —
this wallet is device-local. Showing a full card number asks for
your device credential first." Add-card form validates: nickname
and name required, number digits-only + Luhn, expiry a real month
and not past (good through the end of its month). Network is
detected from the number (Visa / 5- and 2-series Mastercard /
Amex / Discover) with a spinner override. Edit changes
nickname/name/network/expiry only — the number is immutable once
stored (delete + re-add to change it). Delete is a two-step
confirm dialog. Rows show `•••• 1234`, name, and expiry.

### Reveal gate

Tapping Show requires the device credential first. API 29+ uses
the framework `BiometricPrompt` (Builder API — PromptInfo belongs
to androidx, which this app does not vendor) with
device-credential allowed (`setAllowedAuthenticators(BIOMETRIC_STRONG
| DEVICE_CREDENTIAL)` on API 30+,
`setDeviceCredentialAllowed(true)` on API 29); API 26–28 uses the
keyguard confirm intent. With no screen lock set
(`KeyguardManager.isDeviceSecure` false), reveal is blocked with
an honest message — never shown without the check. A shown number
re-masks after 30 seconds or when the screen is left
(`onPause`), and only one card is ever revealed at a time.

### Pay handoff

One shared Pay button (not per-card — the handoff cannot carry a
card choice, so per-card buttons would imply a selection Control
cannot make). Pay copy (verbatim): "Pay opens your wallet app —
Google Pay or Samsung Wallet — where the tap happens. Control
can't choose a card there; pick the card in the wallet app." The
button opens Google Pay (`com.google.android.apps.walletnfcrel`)
or Samsung Wallet (`com.samsung.android.spay`): whichever is
installed; if both, the user picks once and the choice is
remembered in prefs (with a "Change pay app" affordance); if
neither, a dialog names both and offers the Play Store. No fake
NFC UI, no "tap here" affordance anywhere.

### Audit

Card added / edited / deleted / number shown / pay pressed are
written to OwnLog with origin UI through
`WalletCards.auditLine`, whose signature takes nickname + last4
only — a full number has no parameter to travel through, so it
cannot reach the log. (The pay line carries the opened app's name;
no card facts.)

### Verification

`./build-apk.sh` clean (R8 dexing); badging versionCode 8 / "1.7";
apksigner verifies with the v1 certificate; manifest xmltree
confirms WalletActivity (exported=false, adjustResize); dex
spot-checked for the four wallet classes; permission list vs v1.6
differs only by USE_BIOMETRIC. New `harness/WalletHarness.java`
**46/46**: Luhn (valid/invalid/edge), network detection, masking/
grouping, expiry rules, audit-line exactness + PAN-freedom, and
the GCM round-trip / random-IV / tamper-rejection / wrong-key /
short-blob legs. Regression, freshly compiled on the new tree:
**TerminalHarness 29/29**, **AdbConnectHarness 15/15**,
**RelayHarness 28/28** against the live relay. **No on-device
test exists in this VM — Adam's phone is the proof.** What he
should check:

1. Hold anywhere → circle menu shows 9 icons incl. Wallet; Wallet
   opens the wallet page.
2. Add a card: a mistyped number is refused; a good one stores and
   shows as `•••• 1234` with network + expiry.
3. Tap Show: the credential prompt appears (fingerprint / PIN);
   the full number shows grouped; it re-masks within ~30 seconds
   and on leaving the screen.
4. With no screen lock set, Show refuses honestly (set a lock to
   test the pass path, then re-check).
5. Pay opens Google Pay / Samsung Wallet (whichever is installed);
   with both installed, the first Pay asks once and remembers.
6. Edit nickname/expiry; delete with the confirm dialog; the
   Activity log shows the events with nickname + last 4 only —
   no full number anywhere.
7. Known limits, by design: no CVV storage, no tap-to-pay from
   Control itself, cards stay on this phone (no profile export),
   and the number can't be edited — delete + re-add.

The launcher site still serves v1.5 — it updates only at Adam's
direction, per the standing release step.

## v1.6 (2026-11-15) — built-in terminal + one-tap deep-view enable

Adam ran the launcher's own PC starter command from Termux (adb paired to
wireless debugging) and it enabled deep view — so he asked for a
Termux-like command line inside the launcher plus a function that runs
that command. APK: 2,966,447 B, sha256
`f7cb61767e1d7f4ab9933883cb802cab0e817010d4c5892a96308a285f3c3529`,
versionCode 7 / "1.6", same signing key (cert SHA-256 f646377d…a1c52)
— installs over v1.5. No new permissions (badging permission list is
byte-identical to v1.5). No pairing, relay, chat, walkie, or browser
changes; the starter command itself is untouched.

### The terminal (TerminalActivity)

Opened from a "Shell terminal" row in Deep view (the 8-icon circle menu
is unchanged). Termux-like in the space theme: dark SURFACE transcript,
monospace text, a `$` prompt beside the input row, a Run button, and a
scrolling transcript that auto-follows. History two ways: up/down keys
on a hardware keyboard step through past commands, and a Recents
button lists the last 20 (persisted in SharedPreferences, most recent
first, deduped).

**Execution is the EXISTING ADB shell channel, not a local process.**
Each typed command runs as its own shell-service invocation over a
fresh ADB connection through the BC TLS stack (`AdbStarter
.runShellCommand`, new in this version — fresh discovery per call plus
the restart flow's one re-discovery + retry), so commands run as
Android's shell user, exactly like from a PC. The header copy says so,
and says the rest honestly too: no persistent session (nothing carries
over between commands), no exit status (the ADB `shell:` service has
no exit-status channel — output just ends with CLSE, so none is ever
claimed), wireless debugging must be on, Control's key must be paired.
Unhappy states report in their own words: no service → the
wireless-debugging guidance; a rejected key → AdbClient's pair-again
wording verbatim; a "Pairing help" button names the three pairing
steps and offers to open Deep view, where the pair flow lives.

### "Enable deep view" preset

A button in the terminal that runs exactly
`ShellBridgeClient.shellStarterCommand` — no `adb shell` wrapper,
because the terminal IS the shell channel — then polls for the bridge
answer. It does this by calling `AdbStarter.restartBridge` and
streaming its stage lines into the transcript: the preset adds no
starter of its own and never alters the starter command. The
transcript echoes the starter line with the token masked (below).
The existing "Restart deep view" and Diagnose flows are untouched.

### Token redaction (mandatory, tested)

The starter command carries the private bridge token. The token value
NEVER appears in the transcript or the audit log: every string bound
for either surface passes through `AdbShell.redact` (pure Java, in
`AdbShell.java`), which replaces the token with `<bridge token>` and
leaves everything else byte-identical — the preset echo, typed
commands, command output (a command could echo the token back), and
audit entries alike. Commands containing the token are never recorded
in history (`AdbShell.shouldRecord`), so history can neither leak it
nor offer a broken re-run of a masked starter line. The wire is a
different matter, on purpose: the ADB channel carries the TRUE
starter bytes, token included — only the display redacts. (Deep
view's own starter-command block still shows the full line including
the token, as it has since v1.1; that pre-existing display was left
alone.)

### Audit

Every run is written to the shared audit log with origin UI: typed
commands under `TERMINAL (ADB)` with the redacted command as the
parameter (by `runShellCommand`); the preset under the restart flow's
existing `BRIDGE START (ADB)` lines (no double logging).

### Pre-existing breakage found and repaired

The tree as found did not compile: `BridgeControl.java` (edited
2026-11-12, after the v1.5 build) had gained a `setAppDisable`
wrapper calling a `ShellBridgeClient.setAppDisable` that does not
exist, for a `SET_APP_DISABLE` op the bridge does not implement, with
no callers anywhere. The dead wrapper was removed — no behavior
change; the governed enable/disable path (`setAppEnabled` /
`SET_APP_ENABLED`) is untouched.

### Verification

`./build-apk.sh` clean (R8 dexing); aapt2 badging versionCode 7 /
"1.6"; apksigner verifies with the v1 certificate; manifest xmltree
confirms TerminalActivity (exported=false, adjustResize); dex
spot-checked for TerminalActivity/AdbShell/runShellCommand. New
`harness/TerminalHarness.java` — mock adbd on IPv6 loopback —
**29/29**: 14 redaction/history unit checks on the shipping
`AdbShell`; a quoted multi-line typed command delivered byte-exact
with multi-chunk output verbatim; the starter-shaped command
reaching the mock byte-exact INCLUDING the real token while its
display form is token-free; refused-port and certificate-rejection
legs carrying the exact wordings the UI relies on. Regression:
**AdbConnectHarness 15/15** and **RelayHarness 28/28** against the
live relay, freshly compiled on the new tree. **No on-device test
exists in this VM — Adam's phone is the proof.** What he should
check:

1. Deep view → Open terminal: the header explains the ADB shell
   channel honestly; the paired-state line matches reality.
2. With wireless debugging on, type `id` and Run: output shows the
   shell UID, verbatim, with the `$ id` echo above it.
3. Tap Enable deep view: the transcript shows the starter line with
   `<bridge token>` in place of the token, the stage lines, then
   the bridge answering (or the honest failure, naming the stage).
4. With wireless debugging OFF, run any command: the guidance names
   turning it on — no fake success.
5. Recents offers past commands; a command containing the token is
   never offered there; the audit log (Activity) shows the runs
   with origin UI and no token value anywhere.
6. Known limits, by design: no persistent session (`cd` does not
   carry over), no exit codes, no interactive programs (each
   command runs to completion on its own connection).

The launcher site still serves v1.5 — it updates only at Adam's
direction, per the standing release step.

## v1.5 (2026-11-10) — Chat is a pager slide; "?" header popup; browser chrome + homepage fixes

Adam: "I want the chat to be a slide as well in the launcher. I want
the text below you are your profile key to be moved into a popup
that opens when a question mark button is pressed on the top right
of the chat." Folded in from his browser-page report the same
evening: the Browser toolbar buttons were mashed together, and the
homepage should be launcher.eyeknow.app, not DuckDuckGo. APK:
2,958,255 B, sha256
`df61b7bf1f09000e06d84233bd2ec6ca8a48ba850445df9fd5f80025c0324733`,
versionCode 6 / "1.5", same signing key (cert SHA-256 f646377d…a1c52)
— installs over v1.4.

### Chat as the fourth pager page

- The strip is now **[Browser, Home, Walkie, Chat]** (`PAGE_CHAT=3`
  in MainActivity), still starting on Home. The tiny page-icon row
  gained the speech-bubble glyph in position (`PageIconsView` GLYPHS
  = BROWSER, HOME, WALKIE, CHAT, APPS; `SLOT_APPS` moved 3 → 4). The
  circle menu's Chat entry slides the pager to the Chat page
  (`pager.goToPage(PAGE_CHAT, true)`). Apps sheet, circle menu, and
  every other navigation path are unchanged. PagerLayout itself is
  generic over child count — doc comment only.
- **One chat surface.** The new `ChatPage` (a `FrameLayout`
  implementing `PagerPage`) hosts BOTH halves of the old standalone
  chat, built once in its constructor and kept for the life of
  MainActivity:
  - the conversation list — a verbatim port of the old
    `ChatActivity` (DMs from contact cards, profile group chats,
    server merge with locked rows, new-chat dialog, scan handling;
    the `chatKeyPayload` / `parseChatKey` / `shortId` statics moved
    here with it), and
  - the thread — `ChatThreadView`, a verbatim port of the old
    `RoomActivity` (same pull/ingest/unwrap/send logic, same
    sealed-blob wire behavior, same thread note under the title,
    word for word). Opening a conversation swaps the list for the
    thread inside the page; the thread adds one piece of page chrome
    the activity didn't need: a "‹ Chats" button at its top left.
  Row taps, the new-chat flow, and intent routing all call
  `ChatPage.openConversation(...)`, which resets the thread state
  exactly like a fresh RoomActivity did.
- `ChatActivity` and `RoomActivity` are now **thin forwarders** in
  the WalkieActivity/BrowserActivity pattern (manifest entries
  kept). ChatActivity lands MainActivity on the Chat page;
  RoomActivity also translates its four extras ("channelId",
  "channelKey", "name", "peerPub") into MainActivity's
  `EXTRA_CHAT_*` extras, which `handleIntent` routes into the Chat
  page's open thread — so a contact's DM button
  (ContactEditActivity, untouched) still opens that DM, now in-page.
  Settings' chat row still points at ChatActivity and lands on the
  page through the forwarder. ImportActivity now calls
  `ChatPage.parseChatKey`.
- **Lifecycle / polling.** Nothing polls the relay for chat while
  another page is shown. The list poll (5s, as before) runs only
  while the Chat page is shown AND no thread is open; an open
  thread polls at ~3s only while the page is shown (`setPageActive`
  gates the thread's existing poll; activation pulls immediately).
  `onPageHidden` stops both. Because the page and thread are built
  once and only shown/hidden, unread badges and an open thread's
  messages/cursor survive page switches — no recreation per swipe.
- **Back.** MainActivity asks the Chat page first: inside a thread,
  Back (or "‹ Chats") returns to the conversation list; from the
  list, Back goes Home like the other pages. Sheet/menu Back order
  unchanged.
- **Gestures.** ChatPage follows the pager's existing arbitration:
  with the list shown, the conversation ListView owns horizontal
  gestures (it scrolls vertically); with a thread open, its message
  list and composer own theirs (WalkiePage-style `inside()` checks,
  so the composer yields the pager while typing). Headers, buttons
  and background page normally; the 28dp edge strips still always
  paginate.

### The "?" popup

The Chat header keeps the screen title, the display-name line, and
ONLY the identity line — exact wording/format unchanged:
`"You are your profile key · " + Profile.relayShortFingerprint(...)`,
13sp, accent, monospace. A "?" small-button sits at the header's
**top right**. The paragraph that sat below the identity line moved
into an AlertDialog behind it, **verbatim** (same string
concatenation as the old ChatActivity body text):

> "No account, no sign-in, no password — this key is the identity.
> Messages are sealed with each conversation's channel key; the
> relay stores ciphertext only and sees who talks and when, never
> what's said."

The dialog dismisses on its Close button, Back, or an outside tap
(default AlertDialog behavior). (The separate honesty note inside
the thread view stayed in the thread, also verbatim.)

### Browser chrome spacing (Adam: buttons mashed together)

Root cause: the chrome used stock `android.widget.Button`s, whose
own minimum width/height and padding set the box — the buttons
rendered at the framework's default bulk, hard against each other,
and squeezed the URL bar. Fix (spacing/sizing only, same Ui tokens,
same pill URL bar): every chrome button is now an exact box with
the stock minimums and padding zeroed — **40dp × 40dp** glyph
buttons with an **even 6dp gap** between every chrome element. The
bar now carries **back ‹ / forward › / reload ↻ / home ⌂**, then the
URL bar (weight 1, takes all remaining width — ~104dp at 360dp
wide), the reader button (52dp, only when a page is readerable),
and the ⋮ menu. Reload and Home were previously menu-only / absent;
the menu keeps its own Reload entry as before. No change to the
WebView arbitration: the page surface still owns horizontal
gestures, chrome still paginates.

### Homepage = launcher.eyeknow.app

- `Profile` gained a `browser.homepage` value (new profiles are
  seeded with it) and `Profile.browserHomepage(Context)`:
  no stored value → `https://launcher.eyeknow.app`; a stored value
  equal to the old default `https://duckduckgo.com` is **migrated**
  to the new default on load and written back; any other stored
  value (a homepage explicitly set to something else, e.g. via an
  imported profile) is returned untouched.
- BrowserPage's start page (the old hardcoded `HOME_PAGE`
  constant, removed) and the new Home button both load
  `Profile.browserHomepage(...)`. Search from the URL bar still
  goes to DuckDuckGo — only the homepage/start page changed, as
  asked.

### Verification

`./build-apk.sh` clean (R8 dexing); aapt2 badging versionCode 6 /
"1.5"; apksigner signer cert SHA-256
f646377df639f79d7bcf930b2f2af4fa5f968a3ae07c828ee896cc5f372a1c52
(unchanged since v1). Dex spot-checked: ChatPage/ChatThreadView
present, `https://launcher.eyeknow.app` and the identity line
present. Freshly compiled **RelayHarness 28/28** against the live
relay and **AdbConnectHarness 15/15** — neither the relay protocol
nor any bridge/ADB code changed in this version. **No on-device
test exists in this VM — Adam's phone is the proof.** What he
should check:

1. Swipe right from Walkie (or tap the speech-bubble tiny icon, or
   pick Chat in the circle menu): the Chat page slides in with the
   conversation list; the header shows ONLY "You are your profile
   key · ⟨fingerprint⟩" under the name — no paragraph under it.
2. Tap **?** at the chat header's top right: the popup shows the
   "No account, no sign-in…" text; Back / outside tap / Close
   dismisses it.
3. Open a conversation: the thread opens in the same page; Back
   returns to the list; Back again goes Home. Swipe away mid-thread
   and back: the thread and its messages are still there.
4. From a contact, tap the DM button: it lands on the Chat page
   with that DM open (the old separate thread screen is gone).
5. Browser page: back / forward / reload / home are separate,
   evenly spaced buttons that are easy to tap; the URL bar isn't
   squeezed at his screen width; tapping ⌂ (and opening the Browser
   page fresh) loads **launcher.eyeknow.app** instead of
   DuckDuckGo.
6. Regression watch (unchanged code paths, but in the same build):
   the walkie tuner/PTT gesture arbitration, reader mode, and —
   still the open v1.4 question — Deep view → Restart deep view
   reaching stage 3.

The launcher site still serves v1.3 deliberately — it updates only
after Adam's on-device verdict, per the standing release step.

## Verification performed for v1

- `./build-apk.sh` completes: aapt2, javac, d8, zipalign, sign, verify.
- `apksigner verify --verbose`: **Verifies** (v2 + v3 schemes), 1 signer,
  cert SHA-256 `f646377df639f79d7bcf930b2f2af4fa5f968a3ae07c828ee896cc5f372a1c52`.
- `aapt2 dump badging`: package `app.control.launcher`, label **Control**,
  sdkVersion 26, targetSdkVersion 34, launchable `MainActivity`.
- `aapt2 dump xmltree` on the packaged manifest confirms: `MainActivity` with
  MAIN + HOME/DEFAULT/LAUNCHER, `BrowserActivity` with VIEW http/https
  BROWSABLE, `DnsVpnService` (`android.net.VpnService`, SUPPORTS_ALWAYS_ON),
  `NotificationHoldService` (NotificationListenerService), `SmsReceiver`
  (SMS_RECEIVED, BROADCAST_SMS permission), and all 23 activities.
- APK: **738,153 bytes** at first verified build (rebuilt since with the
  governed bridge; see the file itself for the current size), one `classes.dex`,
  assets = blocklist + the two Readability scripts.
- **Not yet done: on-device testing.** There is no phone/emulator in this VM.
  Everything below is code-complete and build-verified; Adam's Galaxy A16 is
  the first real runtime test, as with the Palm app.

## Blocklist

- Source: **StevenBlack unified hosts** (`hosts` file, dated 2026-11-08),
  processed to unique registrable domains with subdomains collapsed to their
  parent when the parent is also listed.
- Result: **42,042 domains**, 774,278 bytes, `assets/blocklist.txt` (sorted,
  lowercase, one per line). `Blocklist.java` loads it once and matches by
  walking parent suffixes, so `ads.example.com` is caught by `example.com`.
- The same list drives the VPN DNS filter (NXDOMAIN) and the browser's
  `shouldInterceptRequest`.

## Module notes

### 1. Launcher
`MainActivity` (HOME), clock/date (profile toggle), favorites grid from the
profile, filter + held-notification status lines, dock (Apps, Browser, Chat,
Walkie, Activity, Settings). `DrawerActivity` = searchable all-apps grid.
No feeds, ads, widgets, or recommendations anywhere.

### 2. Notification control
`NotificationHoldService` (NotificationListenerService). Per-app mutes and an
SMS-driven QUIET-all flag live in the profile/prefs; held notifications are
recorded in `HeldStore` (cap 100) and cancelled from the shade. Nothing is
deleted — the held list is the record.

### 3. Ad filter
- **VPN DNS filter** (`DnsVpnService`): TUN routes only the virtual DNS
  server 10.0.0.1/32 into the tunnel, so the service sees DNS and nothing
  else. Blocklisted qname → locally built NXDOMAIN + counter; anything else
  is forwarded verbatim via a `protect()`ed UDP socket to the profile's
  upstream resolver (default 1.1.1.1, 4 s timeout) and the real answer is
  written back. Per-app bypass via `addDisallowedApplication` (own package
  always bypassed). Foreground notification, `VpnService.prepare` consent in
  Settings. Limits (stated in-app): apps doing their own encrypted DNS
  (DoH/DoT) bypass any DNS filter.
- **Private DNS flow**: Settings explains it, recommends
  `dns.adguard-dns.com`, copies the hostname, deep-links to wireless settings,
  and reads back the current mode/specifier. When the shell bridge is up, the
  same section can write Private DNS directly (governed, origin FILTER — see
  Module 9 bridge); the settings deep-link remains the fallback.

### 4. Browser + Reader mode
`BrowserActivity`: WebView with address bar (bare text → DuckDuckGo, dotted
text → https), back/forward/reload, progress, share / open-external,
bookmarks (`BookmarksActivity`, stored in the profile). Blocklist enforced in
`shouldInterceptRequest`; third-party cookies off, first-party on; JavaScript
toggle lives in the profile.
**Reader mode**: on page finish, the bundled readerable check
(`assets/Readability-readerable.js`, `isProbablyReaderable(document,
{minContentLength: 300})`) decides whether the **Read** button appears.
Activation clones the DOM and runs bundled **Mozilla Readability 0.5.0**
(`assets/Readability.js`), sanitizes the result (scripts/iframes/forms/`on*`
attributes/`javascript:` URLs stripped), and renders locally built HTML with a
fully inlined stylesheet in Control's dark theme (18px/1.65, 680px column) via
`loadDataWithBaseURL(currentUrl)` — **choice noted**: reader images resolve
against the page URL and therefore still pass through the blocklist check in
`shouldInterceptRequest`. Extracted text under 400 chars → one-line toast,
stay on the full page.

### 5. Matrix chat — and the honest E2EE state
`MatrixClient`: Client-Server API over HTTPS with `HttpURLConnection`, no SDK
(login `m.login.password`, `/sync` long-poll, send, create DM/named rooms,
join, profile, media upload/download, `m.audio` voice messages carrying a
custom `app.control.samples` count). `ChatActivity` = login + room list;
`RoomActivity` = per-room thread. **E2EE is NOT implemented in v1** — it is
deliberately staged: encrypted rooms are listed with "this version can't open
it yet", `m.room.encrypted` events render as an honest placeholder, and the
login screen states v1 handles unencrypted rooms. Unencrypted chat is never
presented as encrypted anywhere.

### 6. Portable profile (identity layer)
Canonical document: `files/profile.json`, `"version": 1`. All modules
read/write it through `Profile.java`; runtime-only state (filter on/off,
blocked count, Matrix token/device/since, bridge token, lock/quiet flags)
stays in SharedPreferences and is never exported.

**Token decision**: the Matrix **access token is EXCLUDED from the profile**
(device credential; a new phone re-signs-in with the homeserver + user ID the
profile carries). The Matrix **password is never stored anywhere**.

Annotated schema (v1):

```jsonc
{
  "version": 1,                          // mandatory; newer refused on import
  "launcher":    { "favorites": ["com.example.app"], "showClock": true },
  "notifications": { "mutedApps": ["com.example.noisy"] },
  "filter":      { "upstreamDns": "1.1.1.1", "bypassApps": [] },
  "browser":     { "jsEnabled": true,
                   "bookmarks": [{ "title": "Example", "url": "https://…" }] },
  "matrix":      { "homeserver": "https://matrix.org",
                   "userId": "@me:matrix.org", "displayName": "Me" },
                                         // password: never; token: excluded
  "mesh":        { "displayName": "Me", "key": "<base64 32-byte mesh key>" },
  "contacts":    [{ "name": "Sam", "matrixId": "@sam:matrix.org",
                    "phone": "+1…", "email": "", "meshKey": "",
                    "notes": "", "controller": false }],   // controller = SMS
  "channels":    [ /* configured radio slots only — see Module 7 */ ],
  "smsControl":  { "enabled": false, "secretHash": "<sha-256 hex>" }
}
```

Unknown fields are ignored/preserved on import; missing sections are filled
by `normalize()`; version > 1 is refused with a clear message. Saves are
atomic (tmp + rename).

**Encrypted export only** (`ProfileCrypto`): plaintext is gzip-compressed,
then AES-256-GCM. Two envelope modes — `"passphrase"` (PBKDF2-HMAC-SHA256,
200,000 iterations, random 16-byte salt; passphrase prompt on import) and
`"key"` (random 32-byte key). There is no plaintext export path.
**QR import**: the QR carries `{"app":"control","k":<key>,"h":<sha-256 of
ciphertext>}`; small profiles also fit whole-in-QR
(`{"f":"control-qr","k","iv","ct"}`, ≤ 2,200 chars). The encrypted file
travels by any channel; import verifies the SHA-256 fingerprint **before**
decrypting. **NFC**: export can *write* the key payload to an NDEF tag
(foreground dispatch); import *reads* key/hash from a tag (reader mode) or a
scanned QR or a paste. Full phone-to-phone NFC transfer (Beam/HCE) is the
staged piece — Beam is deprecated by Android and no HCE service is shipped.
First run offers **New profile** / **Import** before anything else.

### 7. Walkie-talkie (transport-agnostic, burst model everywhere)
One PTT engine; press-talk-release; the spurt is delivered over whatever road
reaches the channel. Audio codec: **IMA-ADPCM, 16 kHz mono** (4:1, 64 kbps;
`Adpcm.java`) — Opus was not reachable in the manual pipeline. Live roads
stream frames; slow roads deliver the burst a moment later.

| Road | State | How |
|---|---|---|
| Internet (Wi-Fi / LTE **data**) | Working (code) | Bursts upload as `m.audio` to the slot's Matrix room; texts are room messages. Inherits the room's encryption state — v1 rooms unencrypted, labeled so in-app. |
| Nearby mesh | Working (code) | **Native Wi-Fi Direct** (`MeshNet`): group owner runs the relay socket on port 8989 and arbitrates the floor (first talker holds it; off-floor voice dropped at the relay). Nearby Connections was **not** used: it drags a Play Services AAR closure through the manual pipeline and depends on Play Services at runtime. |
| LAN Wi-Fi | Working (code) | Same `MeshNet` socket engine on the current Wi-Fi; UDP broadcast discovery (`LanDiscovery`, port 8990, magic `CONTROL-WALKIE/1`). Came nearly free once the mesh engine existed. |
| Bluetooth | Working (code), bursts only | Bluetooth Classic RFCOMM (`BtWalkie`, service UUID `3f6a1c2e-7b4d-4e8f-9a1c-5d2e8b4f6a70`), one paired peer, ADPCM frames. BLE not implemented; UI labels Bluetooth as bursty, not fluid. |

Relay: the group owner relaying members **is** one hop of forwarding.
**True multi-hop relay beyond the owner is the named stretch goal — not
built.** Range claims: local-area only, stated in-app.
Security: sessions are keyed by the slot passphrase (SHA-256 → AES-256-GCM
voice on the mesh/LAN path, keyCheck refused at the door). Open slots use the
public per-number passphrase — anyone on the number can listen, and the UI
says exactly that; private slots are encrypted with their own key.

**Channels — a bank of 50 numbered slots** (like FRS/GMRS). Unconfigured
numbers are open channels with zero setup. Configured slot (in the profile's
`channels` array, keyed by `number`):

```jsonc
{ "number": 3, "label": "Worksite", "mode": "open" | "private",
  "key": "<base64, private slots>", "roomId": "<Matrix room or ''>",
  "members": ["@sam:matrix.org"] }
```

Slot identity (all transports): `open:<n>`, or `priv:<n>:<first 8 hex of
SHA-256(key)>` — the same identity on both sides of the online/offline line.
Channel invites are QRs: `{"app":"control-channel","number","label","mode","key"}`.
**Text** rides every road with voice; one interleaved thread per slot
(`WalkieStore`, device-local, cap 200/slot — history is deliberately *not* in
the portable profile). Offline texts queue in an outbox and flush when any
path opens, marked queued → sent in plain words; queued voice goes via Matrix
upload only.
**Tuner**: `TunerView` — a draggable band, one detent per channel (26dp
spacing), haptic tick (12 ms vibration) as each detent passes, 140 ms glide
settle; tap the readout for direct tune; **Scan** sweeps every ~1.1 s and
pauses 5 s on slots with thread activity in the last 5 minutes. The grid
(`ChannelsActivity`) is the secondary view. **Frequency readout** = real FRS
mapping for 1–22 (Ch 1–7: 462.5625 + (n−1)·0.025 MHz; Ch 8–14: 467.5625 +
(n−8)·0.025; Ch 15–22: 462.5500 + (n−15)·0.025), extended for 23–50 in the
same 25 kHz pattern starting 462.7500 — the extension is an in-app
convention, not real FRS. In-app text states the frequency is the channel's
radio identity while audio travels over the data roads.

### 8. SMS remote control
`SmsReceiver` (SMS_RECEIVED, guarded by BROADCAST_SMS) → `SmsControl`.
A text is a command only if **all** hold: feature enabled (default **off**),
sender matches a profile contact flagged `controller` (last-10-digit phone
match), and the first token's SHA-256 equals the stored secret hash (the
secret itself is stored **only as a hash**, in the profile). Format:
`<secret> <COMMAND>`. Commands: `STATUS`, `QUIET`, `LOUD`, `FILTER ON|OFF`,
`TUNE <1–50>`, `LOCATE` (last-known location + maps link; permission-gated,
says so when off), `LOCK` (app UI locks behind the same secret,
`LockActivity`), `WIPE PROFILE` → arms 10 minutes → `<secret> CONFIRM WIPE`
from the same number executes a full wipe (profile, prefs, held/feed,
walkie store, own log, audit). Unknown/malformed → nothing, no reply (no
oracle). Replies go only to the commanding number. Every executed command,
rejected controller attempt, and ignored unknown is in the shared audit log
(cap 120). **Honesty, also in-app: SMS is authenticated, not encrypted —
carriers can read texts; keep commands to controls, not secrets.**
Setup: Settings → Text control (enable, set/rotate secret, controller
checkboxes, command list, log). Permissions RECEIVE_SMS/SEND_SMS are
Play-restricted; fine for sideload. Optional tie-in (SMS as a walkie text
transport) was **not** built — flagged here as optional future work.
One pre-declared bridge mapping exists: `FILTER ON` also sets Private DNS
through the bridge **if it is already running** (origin SMS, audited).

### 9. Activity / Control Room — base tier (normal Android APIs only)
`ControlRoomActivity`, reachable from the home dock and Settings. All data
sits behind the `ActivityDataSource` interface; the v1 implementation is
`AndroidActivityDataSource` (a future privileged source can implement the
same interface without changing the screen).

- **Notifications**: the listener now records **every** notification it sees
  (`NotificationFeedStore`, on-device only — deliberately *not* in the
  portable profile — rolling cap 500, updates de-duped by notification key)
  with app, time, title/text as exposed in the extras, and **SHOWN vs HELD**.
- **Apps**: with usage access (`PACKAGE_USAGE_STATS`, guided grant button),
  `UsageStatsManager` foreground time + last-used today, and up to 120
  foreground/background transitions from usage events.
- **Network**: `NetworkStatsManager.querySummary` for Wi-Fi + mobile,
  aggregated per UID → per app, today or last 7 days. Volume only — Android
  does not tell normal apps which servers another app contacted, and the
  screen says so.
- **This app**: filter VPN state + blocked count, listener state, quiet mode,
  Matrix sign-in, walkie channel + last reported session state, SMS control
  state, lock state, profile presence, bridge state; Control's **own event
  log** (`OwnLog`, ring buffer cap 240, on-device file — events generated
  inside Control, explicitly *not* the system log); and the shared
  command/bridge audit log.
- **Honest limits, written on the screen**: other apps' internal services
  and the system-wide log are walled off from normal apps (per-app process
  visibility since Android 7; READ_LOGS is privileged). No workarounds.

### 9b. Deep tier — Control's own shell bridge (no Shizuku dependency)
`ShellBridgeMain` is a plain `main()` class inside the APK. Started through
adb, it runs via `app_process` as the **shell user (UID 2000)** and serves a
narrow IPC surface. It is our own implementation of the Shizuku-style
process-identity bridge; the separate Shizuku app is not used or required.

**Transports & caller verification (security boundary)**
- Abstract-namespace local socket `app.control.launcher.bridge.v1`:
  the bridge reads the caller's credentials (`LocalSocket.getPeerCredentials`)
  and refuses any UID other than Control's own app UID.
- Loopback TCP 127.0.0.1:17391 as a fallback transport.
- **Both** transports require the per-install bridge token (random 48-hex,
  stored only in Control's private prefs; shown only inside the starter
  command the owner copies). Requests are single-line JSON
  `{"op": …, "token": …, …}`; responses are single-line JSON, output capped
  at 350,000 chars, fixed commands time out at 12 s.
- The bridge executes **fixed command arrays via ProcessBuilder** — there is
  no shell, no executable/argument supplied by the caller, ever.

**Starter flow**
- Working path (v1): from a computer connected by adb, run the one line shown
  in Deep view (Deep view → Copy starter command). It resolves the APK path
  (`pm path`) and app UID (`cmd package list packages -U`), then launches
  `ShellBridgeMain <uid> <token>` under `setsid app_process` with output to
  `/data/local/tmp/control-bridge.log`. Root is never required and would only
  be another starter path.
- **Partial piece — in-app wireless pairing**: Android 11+ wireless-debugging
  pairing (Developer options → Wireless debugging → Pair with code) is
  *guided* in Deep view, but completing the ADB pairing protocol inside the
  app is **not built**: Android exposes no public wireless-adb pairing API to
  normal apps, and implementing the full ADB/TLS pairing client was judged
  too fragile for this manual build. Once any adb session exists (USB or
  wireless), the starter command above is the working path.
- **Lifecycle**: a shell process **does not survive reboot** (nor being
  killed). Deep view detects a dead bridge and shows, in plain words:
  "Deep view is off — tap to restart (needed after each reboot)". Settings →
  **Turn off deep control** sends the bridge the `STOP_BRIDGE` operation,
  which ends the bridge process; base Activity view keeps working.

**Final allowlist (v2)**
- Reads: `PING`, `PROCESSES` (`ps -A`), `PROCESSES_DETAIL`
  (`dumpsys activity processes`), `SERVICES` (`dumpsys activity services`),
  `SERVICE_LIST` (`service list`), `LOGS` (fixed `logcat -d -v threadtime
  -t 2000`; the caller's text is only an in-memory substring filter),
  `APPOPS` (`cmd appops get <pkg>`).
- Governed writes: `GRANT_PERMISSION` / `REVOKE_PERMISSION` (permission must
  be in the fixed 20-permission set), `SET_APP_OP` (op in a fixed 18-op set;
  mode in allow/ignore/deny/default/foreground), `FORCE_STOP`,
  `SET_APP_ENABLED`, `CLEAR_APP_DATA`, `SET_BACKGROUND_DATA` (netpolicy
  restrict-background blacklist add/remove by resolved UID),
  `SET_DEVICE_IDLE_WHITELIST` (`cmd deviceidle whitelist ±pkg`),
  `SET_PRIVATE_DNS` (mode in off/opportunistic/hostname; hostname validated),
  `SET_LAUNCHER_SETTING` (**only** `screen_off_timeout` 15 s–30 min and
  `font_scale` 0.85–1.30 — there is no general settings endpoint),
  `STOP_BRIDGE`.
- Every package target is regex-validated **and** checked installed
  (`pm path`) inside the bridge before any write.

**Refuse-list (bridge-side, authoritative; mirrored app-side)**
- No writes at all: `app.control.launcher` (itself), `android`,
  `com.android.systemui`, `com.android.settings`, `com.android.shell`,
  `com.android.phone`, `com.android.server.telecom`, both package
  installers, both permission controllers.
- Additionally protected from force-stop / disable / clear: Google Play
  services + GSF, Play Store, Settings/Telephony/Media/Downloads providers,
  Bluetooth, NFC, network stack + tethering, Wi-Fi resources, ONS, STK,
  eUICC, blocked-number and user-dictionary providers. (Full lists:
  `REFUSE_ALL` / `REFUSE_DESTRUCTIVE` in `ShellBridgeMain.java`.)

**Governance (app side, `BridgeControl` + `DeepControlActivity`)**
- **Confirmation tiers**: reversible writes = one explicit confirmation
  dialog naming action and target ("Revoke Location from <app>?").
  Destructive writes = press-and-hold 3 s confirmation: **clear data
  (always)**, and **disabling a system app**. Refused targets never reach
  a confirmation.
- **Audit**: every attempt that reaches `BridgeControl` — success, bridge
  refusal, app-side refusal, bridge down — is written to the **same audit
  log as the SMS commands** (`SmsControl` store, cap 120, on-device).
  Schema per entry: `time`, `origin` (`UI` | `SMS` | `FILTER`), `sender`
  (SMS number; empty for bridge), `command` (operation), `target`
  (package / "Private DNS" / setting), `parameters` (e.g.
  `permission=…`, `op=…, mode=…`), `result` (bridge output or refusal).
  Viewable in Activity → This app and Settings → command & control log.
  The audit log is never writable through the bridge (no bridge operation
  touches it; it lives in the app's own storage).
- **SMS origin rule**: the only SMS → bridge mapping is `FILTER ON` →
  Private DNS write, and only when the bridge is already up (origin `SMS`).
  No other bridge power is reachable by text.
- **Filter module rule**: Settings' Private DNS section uses the bridge
  write when Deep view is on (origin `FILTER`, confirmed) and falls back to
  the settings deep-link when it is down.

**Known unproven points (no device in this VM)**
- The bridge has never run on hardware yet: `app_process` startup, abstract
  socket SELinux behaviour between an app and a shell process, and each
  shell command's exact output on Android 16 are build-verified only.
  If the abstract socket is refused on the target, the loopback-TCP +
  token path is the built-in fallback.
- Individual control operations can legitimately fail per Android version /
  target app (e.g. `pm grant` on a permission the target doesn't declare or
  that isn't grantable, AppOp name differences, netpolicy on some OEM
  builds). Failures surface verbatim in Deep controls and in the audit log;
  no operation is reported as working until it has run on Adam's phone.
