# Control — build notes

Working name **Control**, applicationId `app.control.launcher` (the label can
change later; the applicationId should not). **Version 1.44 (versionCode 49)** —
see the version sections below; min SDK 26, target SDK 34. Pure Java; the
external dependencies are ZXing core 3.5.3 (`libs/zxing-core.jar`, QR —
profile transfer since v1.8, hotspot sharing since v1.25), Bouncy Castle 1.84 (`libs/bc*.jar`,
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

## v1.44 (2026-11-17) — the hold-anywhere diagnostic line, made visible

Adam, on 1.43, 2026-11-17 13:06–13:10: the ring STILL shows
only inside the launcher, never over other apps. Bridge is
v9 (current), the Settings hold-anywhere row says On — so
switch, permission, bridge version, and service liveness all
pass, yet no ring. Asked for the v1.43 second diagnostic
line, Adam answered: "It isnt showing any text." PROTOCOL v10
— BRIDGE RESTART REQUIRED once after installing (the
watchdog restarts a stale bridge by itself within minutes,
or Deep view → Restart; the Settings row names the stale
bridge until then).

DEFECT 1 — CONFIRMED: THE DIAGNOSTIC LINE WAS
UNDISPLAYABLE. OverlayRingService.statusLine2 returned
"watch … · hold … · window …" and SettingsActivity set the
overlay summary to stateLine + "\n" + line2 — but the
overlay row is built by switchRow, whose summary TextView is
setSingleLine(true) + ellipsize END, so the second line
could never be seen. The v1.43 diagnosis channel was dead
on arrival, which is why the 1.43 failure stayed invisible.
FIX: the overlay row's summary — and only that row's — now
undoes the single-line setting and caps at 2 display lines
(setSingleLine(false) + setMaxLines(2), ellipsize END kept),
the v1.39 bridge-row treatment
(DeepControlActivity.hubStatusRow). Verified by inspection:
the row height is WRAP_CONTENT with a 56dp minimum, so two
lines grow the row; refreshSummaries only ever setText on
this view; no other code re-sets singleLine on it; Ui.text
sets no line constraint. The other rows keep their
single-line summaries.

DEFECT 2 — ROOT CAUSE STILL UNKNOWN: THE NEXT ATTEMPT MUST
NAME THE STAGE. A fresh full re-audit of the chain
(TouchDeviceProbe, HoldEventParser, ShellBridgeMain's
streamHoldWatch + evaluator thread, HoldWatchClient,
HoldCoordinateMapper, OverlayRingService reconcile/watch/
showOverlay, CircleMenuView's open-count) found NO concrete
defect — see the re-audit note below. The runtime facts
(picked device, frames arriving, holds firing, window
result) were unknown, so this version makes them visible
instead of guessing a fix. New bridge op HOLD_WATCH_STATUS
(protocol v10): the picked input device ("path (name)"),
the frames and holds the current/last watch saw, whether a
watch is streaming, and any probe failure. Counts and names
only — never coordinates, never touch trails. The parser
gained synchronized frame/hold counters (framesSeen counts
SYN_REPORT boundaries; holdsFired counts fires, frame-path
or timer-path); the status op reads a live watch's counters
straight from its parser via a volatile reference, and the
watch's finally block snapshots the same counters once at
watch end, so the last watch's data survives its death. The
Settings second line is now one compact display line —
"watch running · sec_touchscreen · 0 holds · 12 frames",
with a window failure appended when one is recorded — and
falls back to the v1.43 app-side wording when no
bridge-side data exists (old bridge, failed fetch). The
line composition moved to the pure-Java HoldWatchStatus
(parse + deviceShort + formatLine), harness-pinned; the
app fetches the status in reconcile(), only when the switch
is on and the bridge is CURRENT, before the ping-done
callback re-renders the row.

WHY A NEW OP (AND THE BUMP) WAS GENUINELY NEEDED: the only
existing carrier for the picked device was the bridge's
stderr log file (/data/local/tmp/control-bridge.log),
readable only over an ADB shell connection (Diagnose) —
and the ADB connection is itself broken on Adam's phone
(the terminal cannot connect; see the terminal/ADB thread).
The HOLD_WATCH stream carries hold lines only and cannot be
queried. No existing path could carry device/counts to the
Settings row, so HOLD_WATCH_STATUS + VERSION 10 +
HOLD_WATCH_VERSION 10, per the bump-when-an-op-is-added
rule. A v9 bridge refuses the op with the allowlist error;
the client never asks a non-CURRENT bridge, so no
ambiguity.

RE-AUDIT NOTE (v1.44, no defect found — the diagnostics are
the deliverable): the probe's getevent -p parsing handles
the real Samsung layout (axis entries sharing the EV_ABS
header line; BTN_TOUCH on the EV_KEY line); the parser's
MT/ST handling, slot tracking, ffffffff lift, anchor/cancel
/fire state machine, and CLOCK_MONOTONIC timer comparison
all read correctly; the evaluator thread and parser entry
points are synchronized; the client's ack handling,
out-of-range hold filtering, and rotation-fresh mapping
read correctly; the service lifecycle, reconcile triggers,
and window params read correctly; CircleMenuView's
open-count is balanced (showAt increments only on a
closed→open transition, close decrements only while open),
so a stuck suppression count has no code path. The live
hypotheses the diagnostics will now discriminate: (a) the
probe picked the wrong device (the name in the line says
which); (b) the stream is dead (0 frames); (c) the parser
never fires on this hardware's stream shape (frames > 0,
holds 0); (d) holds fire bridge-side but the client drops
them as out-of-range (holds > 0, "no hold seen yet"
app-side — possible if the -p axis ranges mismatch the
stream values); (e) the window is rejected (the window
part names it).

NO AccessibilityService — the hold detection stays bridge
getevent only, as always. NO new permission (still 28).
NO manifest change. The 'override' read is untouched
(still exactly one place in ShellBridgeMain).
WalletCrypto/WalletStore untouched.

Harness: HoldWatchHarness gains leg 7 (parser counters) and
leg 8 (status parse/format, 56 new checks); BridgeGate and
DeepControl legs updated to v10. All 29 suites green — see
the harness report in the delivery.

On-device checklist:
- After installing, restart the bridge once (or wait for the
  watchdog — the Settings row says when the bridge is stale).
- Open Settings: the hold-anywhere row now shows TWO lines.
  The second line names the device and the counts — read it
  and report it exactly.
- Hold a finger still for a second over another app, then
  open Settings again and read the second line: frames 0
  means the stream is dead or the device is wrong; frames
  > 0 with 0 holds means the hold never fires; holds > 0
  with no ring means the hold arrived but the ring was
  dropped or the window failed (the line names a window
  failure when there is one).
- The ring still opens inside the launcher (chrome long-press).
- Report the second line's exact text after a hold attempt —
  that line is the diagnosis.

## v1.43 (2026-11-17) — the circle menu opens over other apps

Adam, 2026-11-17 12:39: "The circle menu doesnt open over other
apps, only in the launcher." PROTOCOL v9 — BRIDGE RESTART
REQUIRED once after installing (the watchdog restarts a stale
bridge by itself within minutes, or Deep view → Restart; the
Settings hold-anywhere row names the stale bridge until then).

THE SPLIT, confirmed from code: the in-launcher menu is a
separate path — the pager's chrome long-press calls
CircleMenuView.showAt directly (MainActivity.java), needing no
service, no bridge, and no overlay permission. Over other apps
the ring comes only from OverlayRingService (foreground
service) + the bridge's streaming HOLD_WATCH op + a
TYPE_APPLICATION_OVERLAY window. So every defect below is in
the service/watch/window path, and the in-launcher menu was
never evidence the overlay worked.

DEFECT 1 — THE HOLD COULD NEVER FIRE (the main defect).
HoldEventParser judged the hold threshold ONLY at SYN_REPORT
frame boundaries (feedEvent → evaluateFrame). Hardware that
reports only on change sends no frames for a perfectly still
finger: the anchor was set at the down frame, no later frame
ever arrived, and the lift cleared the state — so the hold
never fired, no hold line ever crossed the socket, and the
ring never opened over other apps, while every gate (switch,
permission, bridge CURRENT) passed and the Settings row said
On. FIX: HoldEventParser.evaluateAt(timeMs) judges the same
threshold at an explicit time, and the bridge runs a 100ms
timer thread calling it with SystemClock.uptimeMillis() —
CLOCK_MONOTONIC, the same domain getevent -t timestamps live
in, so the comparison is unchanged. On streaming hardware the
frame path fires first and the fired flag blocks a second
fire; on change-only hardware the timer is the only
evaluation that can fire. Parser entry points are synchronized
(the event thread feeds frames while the timer thread
evaluates). The wire shape is unchanged (same hold lines), but
the fix lives in the bridge process, so the protocol bumped
to v9 and the version gate honestly reports the old running
bridge as STALE until it restarts. The bridge log gains the
picked device line ("Hold watch device: /dev/input/eventN
(name)") so a wrong pick is visible in Diagnose.

DEFECT 2 — THE SERVICE DIED AND NEVER CAME BACK.
OverlayRingService.onEnded stops the service whenever its
watch ends (bridge death, reboot, getevent end), and
reconcile() was called ONLY from Settings and Deep view — not
from boot, not from the watchdog after a bridge restart, not
from the launcher. After any reboot the watchdog restarted
the bridge but the ring service stayed dead until Adam opened
Settings or Deep view. FIX: reconcile() is now also called
from MainActivity.onResume (every foregrounding; one
local-socket ping, and starting an already-running service is
a no-op), from BridgeWatchdogService after a successful bridge
restart, and from BootReceiver.

DEFECT 3 — THE WINDOW FAILURE WAS SILENT. showOverlay caught
every exception from the overlay-window add and dropped the
ring with no log and no state, so a rejected window was
indistinguishable from a dead watch. FIX: the failure is
logged to the app log and recorded; a successful add clears
it. The Settings hold-anywhere summary gains a second line
(OverlayRingService.statusLine2): watch state + last end
reason, whether a hold ever arrived, and the last window
failure — no coordinates, ever. The bridge log's "Hold watch
starting / device / ended" lines remain the bridge-side
diagnosis.

HARDENING — A STALE IN-LAUNCHER MENU SUPPRESSED THE RING.
The overlay refuses to open while CircleMenuView reports any
menu open, and MainActivity never closed its menu on pause —
a menu left open across a backgrounding kept the shared count
raised and suppressed every later hold over other apps. FIX:
MainActivity.onPause now closes an open menu (and clears the
pager's menu-open state). No behavior change while the
launcher is in the foreground.

NO AccessibilityService — the hold detection stays bridge
getevent only, as always. NO new permission (still 28;
SYSTEM_ALERT_WINDOW was already declared). The 'override' read
is untouched (still exactly one place in ShellBridgeMain).
WalletCrypto/WalletStore untouched.

Harness: HoldWatchHarness gains leg 6 (timer evaluation: fires
with no frames, never early, never after cancel/lift, never
twice, second touch fires, single-touch fires); BridgeGate and
DeepControl legs updated to v9. All 29 suites green — see the
harness report in the delivery.

On-device checklist:
- After installing, restart the bridge once (or wait for the
  watchdog — the Settings row says when the bridge is stale).
- Hold a finger still for a second over another app: the ring
  opens, centered on the hold.
- The ring still opens inside the launcher (chrome long-press).
- Background the launcher and return: the ring still opens over
  other apps (no Settings visit needed).
- The Settings hold-anywhere row's second line matches reality
  (watch running, last hold time, window ok).

## v1.42 (2026-11-17) — all text removed from the wallet screen

Adam, 2026-11-17 12:34: "Remove all the text from the launcher
wallet."

The rule applied: if text explains, describes, reassures, or
instructs beyond the control itself, it goes — ENTIRELY, not
moved behind "?" modals (unlike the v1.37/v1.38 Deep-view text
rule, which only relocated explanations). What stays, kept
minimal: the controls' own labels, card identifiers, and text
that reports a result or a failure. Every function is
unchanged; WalletCrypto/WalletStore logic is untouched — this
is a UI-text removal only, in WalletActivity.java alone. NO
protocol change (still v8 — no bridge restart needed), NO new
permission.

REMOVED, exactly:
- The intro paragraph under the "Wallet" title ("Your cards
  live only on this phone, sealed with a key Android's
  keystore holds. Control never stores the security code, and
  cards never leave in a profile export — this wallet is
  device-local. Showing a full card number asks for your
  device credential first.").
- The Pay paragraph ("Pay opens your wallet app — Google Pay
  or Samsung Wallet — where the tap happens. Control can't
  choose a card there; pick the card in the wallet app.").
- The card-count line ("1 card stored on this phone." / "N
  cards stored on this phone.") — the list speaks for itself,
  and "stored on this phone" was reassurance.
- The empty-state line ("No cards yet. Add one to keep it on
  this phone."). An empty wallet now shows the Add control and
  nothing else; the status line is left blank except for the
  corrupt-vault failure (kept, below).
- The add/edit form note, both variants ("The number is
  encrypted on this phone before it is stored. Control never
  asks for or stores the security code." / "The card number
  can't be changed — delete this card and add it again to
  change it."). In edit mode the number field is simply
  absent.
- The "No wallet app installed" dialog message ("Pay needs
  Google Pay or Samsung Wallet — the tap happens there, not in
  Control. Install one of them to pay."). The title plus the
  Get-Google-Pay / Get-Samsung-Wallet buttons carry it.
- The policy clause from the no-screen-lock toast ("Control
  won't show a card number without your device credential") —
  trimmed to "Set a screen lock first."
- The instructional clause from the unchosen pay-app line
  ("you'll pick on first Pay") — trimmed to "Pay app: not
  chosen."

KEPT, and why:
- Control labels: "Wallet" title, "Pay" / "Cards" one-word
  section headers, Pay, Change pay app, Add a card, Show /
  Hide, Edit, Delete, Add / Save / Cancel, Close, Create new
  key, Pay with, Get Google Pay / Get Samsung Wallet, and the
  field hints (Nickname, Name on card, Card number, Expiry
  month/year). The nickname hint keeps its "(e.g. Everyday)"
  example — it is part of the label, distinguishing the
  nickname from the name on the card.
- Card identifiers: nickname, network, masked number, name +
  expiry on each row.
- Everything reporting a result or a failure, verbatim: the
  corrupt-vault line ("Stored cards couldn't be read. Nothing
  was changed.") and its "Start fresh (deletes stored cards)"
  button; the credential-gate prompt ("Show card number" +
  nickname/last4) and its outcomes; the reveal/unlock failures;
  all five form-validation toasts; the store-error toasts;
  the pay-app state lines ("Pay app: …", "No wallet app found
  on this phone."); the "couldn't be opened" toasts.
- The full save-failure dialog (v1.39/v1.40) is untouched —
  title, complete per-attempt text, and the "First attempt:" /
  "After creating a new key:" section labels. It is the
  diagnosis channel.
- The key-regeneration confirm (v1.39) is untouched — its
  wording reports a consequence, not a description.
- The delete confirm is untouched — "will be removed from
  this phone. This can't be undone." names the consequence of
  the action being confirmed.
- The class-level honesty-rules comment in WalletActivity.java
  was reworded: the rules are enforced in code and no longer
  "stated on the screen itself". The rules themselves are
  unchanged and still documented there.

Judgment calls: (1) the count line and the empty-state line
were removed rather than shortened to bare counts/words — the
order was "remove all the text", and the list and the Add
button already convey both states. (2) The "Pay app: …" line
was kept: without it there is no visible sign of which app
Pay will open, and it is a state report, not an explanation.
(3) No Wallet-harness leg asserted on any removed copy — the
legs pin WalletCrypto/WalletCards/WalletKeyRecovery logic and
the kept confirm copy, so no harness changes were needed.

On-device checklist: the wallet opens with controls only and
no paragraphs; add a card works end to end; reveal works
(credential gate, 30-second re-mask); a save failure still
shows the full error dialog; an empty wallet shows only the
add control; the Pay section shows Pay + the pay-app line and
nothing else.

## v1.41 (2026-11-17) — uninstall from the app drawer by long-pressing

Adam, 2026-11-17 12:18: "I want to be able to uninstall an app
from the app drawer by long pressing" + "Add uninstall to that
menu."

What the code already had: the long-press DID open a menu — a
`PopupMenu` (v1.22, the Connections pattern) with the favorite
toggle and "View activities". Uninstall joins that menu as the
last item. The existing items and their behavior are untouched;
the menu-building code was restructured only so the item list
comes from one place (below).

Mechanism — the standard system flow, exactly what stock
launchers do: tapping Uninstall fires an `ACTION_DELETE` intent
with the `package:` URI for the app. Android itself shows the
confirm dialog and performs the removal. NO bridge involvement,
NO new permission, NO protocol change (still v8 — no bridge
restart needed). Its honest limits are Android's own: for a
system app the uninstaller offers only what it offers — an
updates removal, or a refusal — and says so itself; the app
never claims otherwise. The one defensive branch: if no
activity could handle the intent (essentially impossible — the
package installer is a system component), a toast says Android
could not open the uninstaller instead of crashing.

Exclusion: Uninstall is NEVER offered for Control itself
(`app.control.launcher`). The decision reuses
`ProtectedOverride.isOwnPackage` — the same single source of
truth as the bridge lockout: the launcher must not offer to
remove itself from its own drawer. A null or empty package also
gets no item (fail closed; unreachable in production).

The menu-item decision lives in the new pure-Java `DrawerMenu`
(items in display order, exact labels, action keys, the
exclusion), pinned by the new `DrawerMenuHarness` (26/26; added
to `run-launcher-harnesses.sh`, now 29 suites). The intent
launch itself is Android-only and proven on-device.

Refresh after uninstall: the sheet deliberately stays OPEN
while the system flow runs. The removal reaches it through the
existing path — `PackageChangeReceiver` (manifest-registered
for `ACTION_PACKAGE_REMOVED`) → `AppListStore.notePackageChanged`
→ background `refreshAsync` → the sheet's store listener, which
rebuilds the grid from the fresh cache — so the app is visibly
gone when the user returns. A cancelled uninstall broadcasts
nothing and changes nothing. No resume-refresh was added: the
receiver + listener path already covers it, and `show()` also
reloads from the cache on every open.

No audit-log entry: this is a user-confirmed system flow, not a
bridge write — Android's own dialog is the confirmation and
the record. Unchanged pre-existing behavior, noted only so it
is not mistaken for a regression: uninstalling a favorited app
leaves a stale entry in the favorites set, exactly as
uninstalling from Settings does today; the set is only ever
pruned by the favorite toggle itself.

Files changed: new `DrawerMenu.java` + `harness/DrawerMenuHarness.java`;
`AppsSheetView.java` (menu built from `DrawerMenu`, Uninstall
action); `build-apk.sh` (version only); `BUILD_NOTES.md`;
`run-launcher-harnesses.sh` (DrawerMenu added). Untouched, as
required: `ShellBridgeMain`, `BridgeOps`, `BridgeGate`,
`WalletCrypto`, the `'override'` read.

**On-device checklist**: long-press a user-installed app → the
menu shows Uninstall last → tap it → Android's own dialog →
confirm → the app is gone and the drawer list refreshes without
reopening the sheet; cancel → nothing happens, the app stays;
long-press Control → no Uninstall item (favorites + activities
only); long-press a system app → Android's own behavior
(updates removal or refusal), honestly reported by the system
dialog; favorites toggle and View activities behave exactly as
before.

## v1.40 (2026-11-17) — wallet root cause fixed: Keystore-generated IV on encrypt

Adam, 2026-11-17 12:11, after v1.39 created a fresh key and saving
still failed — the full-error dialog v1.39 added delivered the
decisive evidence verbatim:

> AllFailedException: No cipher worked:
> default=InvalidAlgorithmParameterException: Caller-provided IV not
> permitted; androidopenssl=InvalidKeyException: key.getEncoded()
> == null; keystore=NoSuchAlgorithmException: Provider
> AndroidKeyStore does not provide AES/GCM/NoPadding

ROOT CAUSE (proven, not a theory): every version since v1.7
initialized encryption with a caller-provided IV — a fresh random
IV wrapped in `GCMParameterSpec`. A Keystore key generated with
`setRandomizedEncryptionRequired(true)` (see `WalletStore`) REFUSES
caller-provided IVs on encrypt: the Keystore must generate the IV
itself. That is why saving a card NEVER worked on Adam's phone, on
any version, with any key. The v1.39 bad-key theory is DISPROVEN:
the fresh key failed identically, with the same caller-IV refusal.
The other two suppliers are expected dead ends for a Keystore key
(AndroidOpenSSL needs key bytes Keystore keys never expose; the
AndroidKeyStore provider does not expose this Cipher lookup on his
device) — they stay as fallbacks; the default supplier is the only
viable path, and it is the one the caller-IV init broke.

THE FIX — `WalletCrypto.java` only: `encryptOnce` now initializes
with NO parameters (`cipher.init(Cipher.ENCRYPT_MODE, key)`) and
reads the IV back via `cipher.getIV()`. If the cipher yields no
IV or one that is not exactly 12 bytes, the attempt fails
honestly and the next supplier is tried — the wire form assumes a
12-byte IV. The wire form IV‖ciphertext+tag is UNCHANGED, so
anything sealed stays readable. Decrypt is UNCHANGED: it MUST
supply the stored IV via `GCMParameterSpec`, which the Keystore
permits — the no-caller-IV rule applies to encryption with
randomized-encryption-required keys only.

KEPT FROM v1.39, untouched: the three suppliers in order, the
`AllFailedException` type + full-detail failure dialog, and the
regenerateKey-on-save-failure logic. Regeneration is no longer the
fix for this failure — it is harmless, and still covers a
genuinely-bad-key case, so it stays; the recovery decision rule
and its harness legs are unchanged.

NO PROTOCOL CHANGE: still v8. NO bridge restart needed after
installing. ShellBridgeMain, BridgeOps, BridgeGate, and the
`'override'` read are untouched.

Harness: WalletHarness 77/77 (was 71; +6 new legs in leg 9 pinning
the no-IV-init round-trip, provider IVs differing between
encryptions, the 12-byte IV prefix, a tampered IV prefix being
rejected, an old-way blob — caller-provided IV — still decrypting
through the unchanged decrypt path, and the all-failed message
still naming all three attempts). Full sweep green, all 28 suites:
AdbConnect 15, AppActivities 42, AppListCodec 22, AudioModem 71,
BridgeGate 57, BridgeOps 440, Bridging 157, Camera 461,
ChangeJournal 148, DeepControl 604, History 78, HoldWatch 64,
LowData 129, Nat 190, NfcArm 40, Relay 28, RingMath 101, Setup 85,
Share 145, Subscription 75, Terminal 29, TerminalScroll 32,
Updater 143, WalkieLayout 30, WalkieMode 70, Wallet 77, Watchdog
58, WordDict 497. (One sweep run showed Relay rc=1 because the
[::1]:8898 proxy relay was not running — environmental; restarted
and Relay passed 28/28. The harness proves the no-IV-init path
with a JVM software key; that a real Keystore key accepts
no-parameter init is proven on-device only.)

On-device checklist: add a card → saves; reveal shows the number;
restart the app → the card still opens; add a second card →
saves; if it still fails → the dialog's full text is the report.

## v1.39 (2026-11-16) — Deep view hub in the Settings style + meters header; wallet key-regeneration fix

Adam, two orders folded into one version: (1) 22:07 — "The deep view
window has way too much text. I want it to look like the main settings
window. A button on each row with an icon, and status information for
memory, battery, storage, data, and processing meters on the top of
the page." (2) 22:10 — "The wallet wont let me add a card still."
Disjoint files; the Deep view redesign was not disturbed by the
wallet fix.

NO PROTOCOL CHANGE: still v8. NO bridge restart needed after
installing. ShellBridgeMain, BridgeOps, BridgeGate versions, and the
`'override'` read are untouched.

### Deep view hub — Settings-style rows + meters

DESIGN SOURCE: the main Settings window (`SettingsActivity`).
The hub now mirrors its row pattern exactly: a rounded-stroke card
(SURFACE fill, 16dp radius, LINE border) holding 56dp rows — a 24dp
glyph bitmap drawn from `PageGlyphs` with DIM paint, a title, and a
chevron "›", with dividers between rows at 52dp left margin. The
`glyphIcon()` helper is duplicated in `DeepControlActivity`
(Settings' is private; no `Ui` refactor). Icons reuse existing
`PageGlyphs` constants only — no new assets, no icon font — the same
reuse Settings itself does (BELL/SHIELD/ACTIVITY/CHAT/SETTINGS/INFO
repeat there too).

Hub order, top to bottom: the unchanged title row ("Deep controls" +
"?" About modal) → the meters card ("Status" header + Refresh button)
→ the status card (Bridge row, Protected packages switch row, stale-
restart row) → the groups card (18 rows, same order as before).
Group rows are TITLE-ONLY (no summary line): the titles are the
controls' names, and live state belongs on the status card — a
judgment call, documented here. Tapping a row opens that group's
page; back returns to the hub. Group pages keep their v1.38 controls,
confirmations, refuse-lists, audit, journaling, no-long-text rule,
and "?" modals EXACTLY as they are. The text rule stands: no
paragraphs on the hub or group pages; explanations stay behind "?"
modals.

The Bridge row's summary wraps to 2 lines (not single-line) so the
shell UID in the ping text is never cut off — the parent thread
needs Adam to report the UID. The ping text itself is untouched.
The Protected switch moved onto the hub as a settings-style switch
row, keeping its exact v1.36 behavior: default off, strong confirm
to enable, locked unless the bridge is CURRENT, audit-logged, the
`syncingProtectedSwitch` guard, and `refreshProtectedSwitch()`
gating. The Protected packages group page keeps its header state,
status line, and "?" info, plus one short pointer line: "The switch
lives on the Deep controls hub." The stale-restart row is a divider
+ clickable row inside the status card (a `staleRow` column
container, GONE by default); `ping()` and `restartStaleBridge()`
visibility code is untouched.

METERS — all reads app-side via platform APIs, NO bridge calls, so
the meters work even when the bridge is off. Refresh on resume and
via the Refresh button; reads run on a background thread (the CPU
read sleeps 300ms between samples). Each meter shows a bar + short
numbers (e.g. "6.1 / 8.0 GB"):
- Memory: `ActivityManager.MemoryInfo` (avail/total), used/total.
- Battery: `BatteryManager` capacity + charging state ("82%",
  " · Charging", " · Full" at 100% charging); warns (SIGNAL tint)
  below 20% while not charging.
- Storage: `StatFs` on the data partition, used/total.
- Data: `TrafficStats` device totals, "RX … · TX … · since boot";
  the bar is the mobile share of the total when mobile counters
  are readable, and the numbers say " · mobile share unknown"
  when they are not.
- Processing: overall CPU load from two `/proc/stat` samples
  (aggregate "cpu" line only, idle = idle + iowait), labeled
  "63% · approx" — approximate, said so on the label.

HONEST FALLBACKS: any meter that cannot be read shows
"Unavailable" — never a fake number, never a zero presented as
real; an unsupported bar fraction is NaN (empty track, the numbers
say why). The shaping/formatting is pure Java in `DeviceMeters`
(formatBytes, formatUsedTotal, usedFraction, CPU parse + usage,
battery/traffic labels) and harness-pinned in DeepControlHarness
leg 18; the Android reads live in `DeviceMeterReads` and are
proven by construction + the on-device checklist below. The hub
row structure is pure Java in `DeepHub` (18 groups, glyph table,
INFO fallback for out-of-range indices) and harness-pinned in
DeepControlHarness leg 19.

ON-DEVICE CHECKLIST (hub): every row has an icon and opens the
right page; back returns to the hub; meters show plausible numbers
matching Settings/About values; meters work with the bridge off;
the stale bridge still shows the restart row; Protected switch
behavior is unchanged (confirm, lock on stale bridge, audit);
no long text anywhere on the hub or group pages.

### Wallet — key regeneration, third supplier, full failure text

THE SAGA (see `WalletCrypto.java`'s header): saving a card fails
with "No cipher worked: default=…; keystore=…". The
`Cipher.getInstance("AES/GCM/NoPadding", "AndroidKeyStore")`
lookup is KNOWN to throw NoSuchAlgorithmException on Adam's
Samsung A16 (Android 16), and the default supplier fails too,
identically across versions. The per-attempt detail never reached
us because the UI truncated it to 160 chars.

THE CAUSE THEORY — STATED AS THEORY, UNCONFIRMED: the persisted
Keystore key entry itself is unusable. `WalletStore.key()` returns
the existing entry forever, so a bad entry fails every version
identically at cipher init under every provider — which matches
everything observed. Saving has NEVER worked on his phone, so the
vault is almost certainly empty — but the fix does not assume it.

THE FIX:
1. KEY REGENERATION, ONCE, ON THE SAVE PATH ONLY.
   `WalletStore.regenerateKey()` deletes the entry and generates a
   fresh key with the EXACT existing spec (same alias
   `control-wallet`, AES-256, GCM, no padding, randomized
   encryption required, not auth-bound — the spec is factored
   into one private `generateKey()` so creation and regeneration
   can never drift). When encrypt throws all-failed on the save
   path: if the vault holds NO cards, regenerate once and retry
   the save once, silently. If the vault holds ANY cards — or the
   card count itself cannot be read (fail closed) — show a
   confirm dialog naming the consequence plainly ("The encryption
   key can't be used. Creating a new key means previously saved
   cards will stop opening and will need to be re-added.") and
   regenerate + retry only on confirmation. Never regenerate on
   the decrypt/reveal path (`unseal` untouched). Never loop: one
   regeneration per save attempt, then the failure stands. The
   decision lives in pure-Java `WalletKeyRecovery` and is
   harness-pinned (WalletHarness leg 8); the Android Keystore
   calls are Android-only and proven on-device.
2. THIRD SUPPLIER: `WalletCrypto` now tries default, then an
   explicit "AndroidOpenSSL" supplier, then keystore. It costs
   nothing and distinguishes provider-resolution failures from
   key failures in the diagnostics. The supplier-seam structure
   and harness substitution pattern are intact; `AllFailedException`
   is now a named type (the save path keys regeneration on it).
   Per-attempt detail grows 28 → 120 chars and the safety cap
   160 → 2000 (a bound only — the dialog shows the full text).
3. FULL FAILURE VISIBILITY: when a save still fails, the COMPLETE
   error text appears in a scrollable, text-selectable dialog
   (the v1.38 refusal-dialog pattern) instead of the truncated
   toast — including BOTH attempts' details when a regeneration
   happened (first attempt vs post-regeneration attempt, labeled)
   plus the cause chain (cause class + full message). The wire
   form (IV‖ciphertext+tag) is unchanged, so anything ever sealed
   stays readable under a good key. The delete/edit paths keep
   the old truncated toast — they involve no encryption. The edit
   path (`updateMeta`) is unchanged: no encrypt, no regeneration.

HARNESS: WalletHarness leg 6 updated for three suppliers (order,
first-success-stops, all-failed message naming all three in full,
long detail preserved, tampered blob naming all three); leg 7 pins
that each of the three positions can win; leg 8 pins the recovery
decision truth table + confirm copy + the AllFailedException type.
Encrypt/decrypt round-trip unchanged.

ON-DEVICE CHECKLIST (wallet): add a card → saves; add a second
card → saves; reveal shows the number; restart the app → cards
still open; if it still fails → the dialog shows the full
per-attempt text to report.

## v1.38 (2026-11-16) — launch refusals readable, scrollable short confirms, Deep view pages, flags picker + toggles

Adam, three reports folded into one version: (1) "The bridge refuses to
launch the activities of an app" + "Android did not confirm... then text
gets cutoff"; (2) "When I try to enable system packages, the modal has too
much text in it and i cant scroll"; (3) "the one long page has way too much
scrolling" + "The bridge page also has way too much text. I dont want there
to be huge lengths of text. You can put a ? Button in each of the sections
that has long text and have the details popup in a modal." Report (3)
REVERSES the v1.37 single-view direction — the latest word wins, so the
single-scroll layout is gone. No new permissions. **Bridge protocol v8:
Adam must restart the bridge once after installing** (the verifier fix runs
bridge-side; a v7 bridge classifies STALE with the restart row, same
fail-closed pattern as v1.33/v1.35/v1.36). Verified: `./build-apk.sh`
completes; `apksigner verify` passes (v2 + v3) with the same signing
certificate (`f646377d…f372a1c52`); badging shows versionCode **43** /
versionName **1.38**; 28 permissions (unchanged); zero
AccessibilityService strings in the dex. APK: 20,878,718 bytes, SHA-256
`c0584ad367f785501db16e98d2588c23ab6128f864ebd6c14beca7688e8afaa6`.

### Root cause: the launch refusal

`BridgeOps.verifyAmStart` failed whenever `am start` output lacked
"Starting: Intent", producing "Android did not confirm the launch: \<up to
300 chars of am output\>", and `AppActivitiesActivity` showed it as a
Toast — truncated on screen and gone in seconds, so the reason was
unreadable. Two of the failures were false: `am start` prints "Warning:
Activity not started, intent has been delivered to currently running
top-most instance." when the activity is already on top — the activity
came forward, and the verifier called it a refusal.

### The two launch changes

1. FULL RESULT DIALOG — `AppActivitiesActivity.launchViaBridge` replaces
   the result Toast with a scrollable `AlertDialog` showing the COMPLETE
   result text, selectable, naming the component; the failure title states
   the bridge refused. The body shaping is pure Java
   (`ActivityList.refusalDialogBody`: component + blank line + full error;
   null/blank error reads "No reason was given."). Success keeps the short
   toast ("Launched \<label\>."). The pre-launch confirm and the audit
   logging are unchanged. Both themes.
2. WARNING-AS-SUCCESS — `verifyAmStart` treats output containing
   "delivered to currently running top-most instance" (case-insensitive)
   as SUCCESS, with a message saying the activity was already running and
   was brought forward. All other verifier rules are unchanged (exit 0 +
   "Starting: Intent" + no error/exception markers); validation,
   package/class checks, and the fixed argv are untouched.

### The modal fix + shorter copy

The bug was `strongConfirm` in `DeepControlActivity`: it built a custom
`LinearLayout` box set via `.setView(box)`, which `AlertDialog` does NOT
scroll — while the plain `confirm()` path uses `.setMessage()`, which
scrolls natively. The fix wraps the strong-confirm content in a
`ScrollView` (the hold button scrolls with the text; a stationary press
still holds, because the ScrollView only steals the touch once the finger
moves). Every dialog on the confirm/strongConfirm path is covered by
construction (`confirmRestore` delegates to `confirmAction`; the switch
confirm shares `strongConfirm`). Input-form `setView` dialogs (flag edit,
wm dialogs) are not on that path and were not redesigned. The protected
enable/disable confirm copy is trimmed to the essentials — exact package,
specific consequence, recovery command (`recoveryText` = "Recovery —
re-enable here, or: adb shell pm enable \<pkg\>"); the confirm still
requires the same strong hold. The switch-enable flow is untouched beyond
the shared scrollability; `switchConfirmBody` was not trimmed.

### Pages, not one long scroll (the reversal)

`DeepControlActivity` is a hub plus one page per group. The hub lists the
18 groups as short rows in the same order (Protected packages, Target app,
Running processes, Permissions, AppOps, App state, Background policy,
Private DNS, Launcher system setting, Apps, Display, Input, Capture, Power
& scheduling, System config, Inspect, Change history, Result); tapping a
row opens ONLY that group's page; back (button or system back) returns to
the hub. Group cards are built once by the unchanged v1.37 `addGroup` code
and staged; showing a page re-parents that group's card into the page
holder, so every control keeps its state. The Result card travels with the
page where a control ran (it is the last group built; the Result page
shows it once). Bridge status and the Protected packages ON/OFF line stay
visible on the hub. `scrollToResult` scrolls the current page only.
Navigation restructure only: controls, confirmations, refuse-lists,
audit, and journaling behave exactly as before. Both themes.

TEXT RULE, ABSOLUTE (Adam's clarification): no paragraphs of body text on
any page — one short header line per section plus the controls. Every
longer explanation lives ONLY behind that section's "?" modal
(`showInfo`, `.setMessage()`, which scrolls natively). Copy genuinely
needed inline to use a control (a field hint, a status line, a button
label) stays inline and short: the bridge-status lines, the protected
status lines, the stale hint, and the flag status line were all shortened
to one short line each; the flag tap/long-press/switch instructions moved
into the System config "?" text. Confirmations are unchanged by this rule
except for the shortening above.

### System flags: namespace picker + toggles

The show-everything flag list is replaced by a dropdown (Spinner)
listing the namespaces alphabetically, populated by one unscoped list
call (`ConfigFlags.sortedNamespaces` unions flag-line namespaces and
bare namespace names, sorted, de-duplicated). Selecting a namespace shows
ONLY that namespace's flags; the default is the first namespace, or the
last-picked one when it is still listed — the choice persists in Prefs
(`KEY_LAST_FLAG_NAMESPACE`, device-local, never in the profile). The
picker caps at 200 namespaces with an honest note; the flag list caps at
400 rows with an honest note. Each flag row gets a TOGGLE SWITCH only
when its current value is exactly "true"/"false"
(`ConfigFlags.isBooleanValue`); flipping it fresh-reads the flag and
goes through the existing strong confirm + journaling + re-read
verification (`setDeviceConfigWith`, same as Change value). A flag with
any other value shows its value with the existing long-press menu
(Change value / Reset to recorded default); the group "?" copy says
plainly that switches appear only on true/false flags. The switch reverts
visually on tap and commits only through the confirm; a flag that no
longer holds true/false on the fresh read reloads the list instead of
guessing. "Reset all flags to defaults" stays on the flags page. The
manual Read/Set form keeps its own namespace input, synced from the
picker and from tapping a flag row.

### On-device checklist

- Launch a non-exported activity that is NOT running → it launches.
- Launch one that IS already on top → success ("already running, brought
  forward"), not a refusal.
- Launch one that genuinely fails → the dialog shows the full `am`
  output, scrollable and selectable, naming the component.
- Stale bridge → restart row on the hub + honest "bridge is old" copy on
  the activities screen; restart once → v8.
- Enable a protected package → the modal fits, scrolls if long, text
  short (package + consequence + recovery command); disable path same;
  other long confirms scroll.
- Hub fits one screen of short rows; each group opens its own page; back
  returns to the hub; bridge status + Protected ON/OFF visible on the hub.
- No page carries a paragraph of inline text; every "?" opens a scrollable
  modal with the details.
- Flags picker shows one namespace; the last-picked namespace is restored
  on the next visit; boolean flags toggle with confirm + journal entry;
  non-boolean flags keep the long-press menu; the result is visible on
  the page where the control ran.
- No bridge restart is needed for the pages/flags/modal changes alone —
  but the v8 bump means one restart is required for this version anyway.

### Harness

`BridgeOpsHarness` +7 (warning → success with and without the Starting
line, case-insensitive; error/exception/nonzero-exit still fail);
`AppActivitiesHarness` +6 (refusal body shaping);
`BridgeGateHarness` incl. fixed isStaleRefusal legs (v7 stale-by-version,
v8 current-version refusals not stale); `DeepControlHarness` +6 copy-trim
legs plus `protocolBumped` replacing `protocolUnchanged`;
`ChangeJournalHarness` +19 (boolean/toggle rules, picker namespace union:
148 total).
Full sweep via `~/workspace/run-launcher-harnesses.sh` — all suites green
(see the build report for counts).

## v1.37 (2026-11-16) — change journal + restore to default, config flags by category, process menus, single-view Deep controls

Adam: "I need the launcher to keep track of any changes that are made so
things can be restored to default. I want the list of config flags to be
sorted by category and each item to have a menu when long pressed to
change the flag. I need a way to set all flags to defaults. I also need a
list of the processes with the long press menu to enable/disable them."
Plus, same version: "the bridge screen has way too much text and I want
it to be cleaner and more organized with all of the controls in a single
view" — the Deep view controls screen, redesigned (presentation only).
No new permissions. **Bridge protocol stays v7: no bridge restart is
needed** — every before-value read reuses an existing op (see below).
Verified: `./build-apk.sh` completes; `apksigner verify` passes (v2 +
v3) with the same signing certificate (`f646377d…f372a1c52`); badging
shows versionCode **42** / versionName **1.37**; 28 permissions
(unchanged); zero AccessibilityService strings in the dex. APK:
20,874,622 bytes, SHA-256
`baa5cbecc1745d0745d09bbf9086658983fcb5854cd8ad0881afa38181db4cf6`.

### The change journal (the feature)

`ChangeJournal.java` (pure Java, no Android imports) persists one
app-private file, `change-journal.json` under `getFilesDir()`. It
survives cold starts and is NEVER part of the portable profile export
(the export reads only Prefs keys; the journal file is referenced only
by `ChangeJournal` and `DeepControlActivity` — grep-proven). Each entry
records op, target, previous value (read first), new value, timestamp,
restore routing (package / namespace / key / detail), and its state.

- **Record before, finalize on success.** `runJournaled` persists the
  entry PENDING before the write runs; success finalizes it
  (`markApplied`), failure/refusal drops it entirely — nothing changed,
  and the audit log already holds the attempt. A pending entry left by
  a dead process is dropped on load: its outcome is unknowable.
- **Restores never append entries.** Restoring writes the previous
  value back through the same governed `BridgeControl` op (same
  confirmations, refuse-lists, override rules, verification, audit
  markers) and marks the entry restored, so restore-all cannot loop.
  Undo paths (battery reset, Doze unforce, display reset-to-originals,
  install-existing from the Removed list) are NOT journaled: they are
  the return to a previous state, not a new change.
- **Restore-all replays newest-first**, ending at the earliest recorded
  values, under ONE strong confirm naming the attempt count and the
  skipped (unrestorable) count. A refused entry does not stop the
  replay; every result is reported honestly with failures listed.
- **Cap 500 entries**: the oldest restored entries are dropped first,
  then the oldest entries overall.
- **"Defaults" are strictly the earliest recorded values.**
  `recordedDefault` returns the earliest UNRESTORED applied entry's
  previous value for a flag/setting — null when there is no unrestored
  entry, when the earliest such previous value is unknown, or when the
  earliest such entry is unrestorable. Skipping restored entries is
  what makes a Restore button hide once its flag is back at its
  default; a new change after a reset re-establishes the default from
  its own fresh before-value.

Coverage — every persistent Deep-view write is journaled:

| Write | Before-value read | Restore |
|---|---|---|
| SET_APP_ENABLED | PackageManager enabled state (sync) | Set back |
| SUSPEND / UNSUSPEND | None exists — unknown | Unrestorable; copy points at the manual inverse |
| UNINSTALL_USER | "installed" (it was, or the call refuses) | INSTALL_EXISTING |
| SETTING_PUT / SET_LAUNCHER_SETTING | SETTING_GET | Put the previous value back; "null" (unset) → unrestorable |
| DEVICE_CONFIG_PUT | DEVICE_CONFIG_GET | Put the previous value back; "null" (unset) → unrestorable |
| WM size / density / overscan | Fresh WM_GET (dialogs restructured to read first) | Set the previous value back; existing originals prefs + Reset untouched |
| NIGHT_SET | Fresh NIGHT_GET | Set the previous mode back ("unknown" → unrestorable) |
| BATTERY_SET_LEVEL / BATTERY_UNPLUG | Fresh BATTERY_GET display string | BATTERY_RESET unconditionally — restorable even with an unknown previous |
| STANDBY_SET | Fresh STANDBY_GET | Set the previous bucket back ("unknown" → unrestorable) |
| DOZE force-idle / force-inactive / step | Fresh DEVICEIDLE_STATE | Unforce unconditionally — restorable even with an unknown previous |
| GRANT / REVOKE permission | PackageManager.checkPermission (sync) | The inverse grant/revoke |
| SET_APP_OP | APPOPS + `BridgeOps.parseAppOpMode` | Set the previous mode back (unparseable → unrestorable) |
| SET_PRIVATE_DNS | Settings.Global mode + specifier (sync; null mode = "opportunistic", the platform default) | Set the previous mode/host back |
| SET_BACKGROUND_DATA / SET_DEVICE_IDLE_WHITELIST | No read op exists — unknown | Unrestorable; copy points at the opposite action |

Recorded as **cannot be undone** (never a faked restore): clear-data,
Wi-Fi forget, user removal, trim caches, user creation, APK install.
NOT journaled (transient / non-state): force-stop/Kill, input
injections, capture, inspect reads, run-job, activity launch,
screenrecord/bugreport, stop-bridge. Radio writes from the Connections
screen and SMS-originated writes are out of scope for the journal
(audit-log-only) — the journal covers the Deep view, where restore
actions live.

**No protocol bump.** Every before-value read reuses an existing op
(WM_GET, SETTING_GET, DEVICE_CONFIG_GET, NIGHT_GET, BATTERY_GET,
DEVICEIDLE_STATE, STANDBY_GET, APPOPS) or a client-side read
(PackageManager, Settings.Global). `ShellBridgeMain.VERSION` and
`BridgeGate.HOLD_WATCH_VERSION` stay 7; the harness pins this
(DeepControlHarness leg 17). No bridge file was touched: the bridge
reads the "override" field in exactly one place still (grep-proven).

### Config flags: sorted by category, long-press menu, reset all

`ConfigFlags.java` (pure Java) parses `device_config list` output in
both shapes — flag lines (`namespace/key=value`, or bare `key=value`
when the call was scoped to one namespace) and bare-namespace output
(the unscoped list on some builds returns namespace names only, in
which case each namespace is fetched separately, capped at 200).
Unparseable lines are skipped, never guessed; unparseable output is
shown raw. The list renders grouped: namespaces alphabetical (the
category headers), flags alphabetical within, each row showing the key
and current value (empty reads "(empty)").

- **Tap** a flag fills the manual form below and reads the value.
- **Long-press** opens a menu: "Change value…" (edit dialog prefilled
  from a FRESH get, validated non-empty single line ≤512 chars, then
  the existing strong confirm + journaling) and "Reset to recorded
  default" (only while `recordedDefault` holds one; strong confirm; on
  success every entry for that flag is marked restored).
- **"Reset all flags to defaults"** in System config replays the
  flag-restore plan (device_config + settings entries, newest first)
  under one strong confirm naming the count + skipped count, with
  per-flag results reported honestly. The copy states plainly:
  "defaults" = the values recorded before the app first changed each
  flag; flags the app never changed are untouched; flags changed
  outside the app can't be known.
- The Display-section settings editor (8 governed keys) is journaled
  too, and each key gets a **Restore** button visible only while a
  recorded default exists (normal confirm, like other setting
  changes; on success the flag's entries are marked restored).
- A flag that was UNSET before Control changed it (`device_config get`
  returns the literal `null`) is unrestorable: restore would need a
  delete, and `cmd device_config delete` is not reachable through any
  existing op — the empty-value put's "clears" behavior was NOT treated
  as a verified delete. The journal says so instead of pretending.

### Processes: long-press enable/disable on every mapped row

`ProcessRules.menuItems(row, enabledState)` (pure Java, single source
of truth for the menu) decides: Details always; no known package → ONE
disabled item with the exact wording "No package found for this
process — nothing to disable" (the v1.33 no-guessing rule stands);
Control's own row → the disabled explainer only; every other mapped row
→ Kill + Enable/Disable, reading FRESH enabled-state at menu-open time
(null when the package vanished → the label defaults to Disable and
the governed path refuses honestly). **Deliberate change from v1.36:**
protected rows now OFFER Enable/Disable even with the override off —
the governed confirm names the coming refusal and the bridge refuses,
the same pattern Kill already uses; the v1.36 menu-hiding behavior is
superseded. Enable/disable from the menu runs the exact shared
`enableDisableFlow` path (same confirms, refuse-lists,
ProtectedOverride rules, audit markers) plus journaling. Kill behavior
is unchanged.

### The single-view redesign (presentation only)

The Deep view was a disclosure-section maze (17 collapsible sections,
long explanatory paragraphs inline). It is now ONE scrolling view:
every group is always visible as a `Ui.card()` card with one short
header line; the longer explanation moved behind a small "?" button per
group that opens an info dialog. Browsing copy is terse; NOTHING
essential is hidden — every confirmation dialog still names the exact
action, target, consequence, and recovery exactly as before.

- **Group order kept as Adam knows it:** Protected packages, Target
  app, Running processes, Permissions, AppOps, App state, Background
  policy, Private DNS, Launcher system setting, Apps, Display, Input,
  Capture, Power & scheduling, System config, Inspect, Change history
  (new), Result. The About section compressed to a "?" button on the
  title row. No control requires a separate screen; nothing was
  dropped.
- **Result card stays last, always visible.** `runCall` scrolls to it
  (smooth scroll) for confirmed actions; input injections report
  without scrolling (rapid-fire presses must not yank the view).
  `confirm()` was factored into `confirmAction()` so journaled and
  plain paths share the exact dialogs; `runCall` delegates to
  `runCallWithResult` with a result callback the journal finalizes on.
- **All group data loads once on first resume** (processes, removed
  apps, display, power, system config) instead of on section-expand;
  Refresh buttons reload. The processes list refreshes after every
  control run (it is always visible now, and an enable/disable must
  show at once).
- **Caps that keep the view usable:** flags render capped at 400 rows
  with an honest note + narrow-by-namespace hint; history renders
  capped at 100 rows with a count note. Both themes use the same
  `Ui.card()`/`Ui` tokens, so no theme-specific layout exists.

### Deviations from the brief (all deliberate, with reasons)

- Suspend/unsuspend, background-data, and Doze-whitelist entries are
  unrestorable (no read op exists for the previous state) — the brief's
  "previous value unknown → restore refuses honestly" fallback, with
  copy pointing at the manual inverse action.
- Unset-before flags/settings are unrestorable (no verified delete) —
  the brief's explicit fallback.
- Battery/doze restores are unconditional (reset/unforce) and stay
  restorable even when the before-value read failed — the restore does
  not depend on the recorded value.
- The process menu's own-package row offers Details + explainer only
  (no Kill) — `menuItems` is the single decision point, and offering a
  command against Control itself serves nothing.
- WM dialogs now read fresh state before opening (one extra bridge
  round trip per dialog) — the journal needs a true before-value, and
  the prefilled values are fresher for it.
- `recordedDefault` skips restored entries (a fix to the journal core
  made during this build): otherwise a setting's Restore button would
  never hide after a successful restore.
- Restore-to-default actions mark entries restored ONLY on bridge
  success (via the result callback, not the `after` runnable, which
  runs on failure too).

### Harnesses

New `ChangeJournalHarness` (129 checks: pending lifecycle, codec round
trip incl. dead-process pending drop + corrupt/missing files, ordering,
recorded defaults incl. the restored-skip rule, flag plan, 500-entry
pruning, display words, both flag-parse shapes + grouping, AppOp mode
parsing) + 36 new legs in `DeepControlHarness` (process-menu rules +
protocol-unchanged pin), now 510 checks. Full sweep: all 28 suites
green — AdbConnect 15, AppActivities 36, AppListCodec 22, AudioModem
71, BridgeGate 55, BridgeOps 433, Bridging 157, Camera 461,
ChangeJournal 129, DeepControl 510, History 78, HoldWatch 64, LowData
129, Nat 190, NfcArm 40, Relay 28, RingMath 101, Setup 85, Share 145,
Subscription 75, Terminal 29, TerminalScroll 32, Updater 143,
WalkieLayout 30, WalkieMode 70, Wallet 56, Watchdog 58, WordDict 497.

### On-device checklist (v1.37)

- Deep view opens as ONE scroll: all groups visible, short headers,
  "?" dialogs hold the long text, both themes; no control needs a
  second screen; Result card last, scrolls into view on a confirmed
  run, stays put on rapid key presses.
- Change a setting (e.g. font scale): Change history gains an entry
  with the right was → now; Restore writes it back; the entry marks
  Restored; the setting's Restore button appears, then hides after
  the restore.
- Disable an app, then Restore from history: the app re-enables; a
  refused write (e.g. protected package, override off) leaves NO
  history entry.
- Flags: List flags groups by namespace, alphabetical; long-press →
  Change value + Reset to recorded default; Reset all flags to
  defaults restores every journaled flag with an honest per-flag
  report; an unset-before flag shows "cannot be undone" with the
  delete explanation.
- Processes: long-press every row type — unmapped shows the exact
  explainer; Control's row shows its explainer; a disabled app shows
  Enable; a protected row offers Disable and the confirm names the
  refusal when the override is off.
- Restore all changes: one strong confirm, newest-first replay,
  failures listed, unrestorable entries skipped and counted.
- Cold start: history survives; profile export/import carries no
  journal entries. No bridge restart needed (still v7) — the
  Protected switch stays unlocked after the update.

## v1.36 (2026-11-16) — protected-package override: disabling system-critical packages

Adam: "I want to be able to disable the system critical packages." The
refuse-lists are NOT removed: they stay the default, and the override is a
deliberate switch with guardrails. No new permissions. Bridge protocol v7:
a v6 bridge classifies STALE everywhere with the one-tap stale restart —
Adam must restart the bridge once after installing 1.36.

### The guardrails (the feature)

- **Switch, default OFF.** A "Protected packages" section in Deep controls
  (after About), with a switch persisted in `Prefs`
  (`KEY_PROTECTED_OVERRIDE`, device-local — never in the portable
  profile) and the section header naming its state (ON/OFF) so the state
  is plain even collapsed. The persisted value survives cold starts; it
  is never silently reset. Turning the switch ON needs the strong
  press-and-hold-3-seconds confirmation, in plain words: what can break
  (status bar, Settings, calls, Play services), the recovery path
  (re-enable from this screen, or `adb shell pm enable <pkg>`), and the
  Control exception. Enabling is audit-logged (`PROTECTED_OVERRIDE`,
  `override=on`); disabling needs no confirm (the safe direction) and is
  audited too.
- **Per-action strong confirmation.** With the switch on, disabling or
  enabling a refuse-listed package takes the strong confirmation naming
  the exact package, the specific consequence for that package (see
  `ProtectedOverride.consequenceFor` — every list member has one), and
  the recovery command. Each override action is audit-logged with the
  `override=protected-disable` marker, and the bridge's own result text
  carries "Protected-package override."
- **Scope is disable/enable ONLY.** Force-stop, clear-data, uninstall,
  suspend, and every other op keep the existing refusals unchanged,
  override or not. The bridge reads the "override" field in exactly one
  place (the SET_APP_ENABLED case) — grep-proven; no other op's
  validation code was touched.
- **One hard exception: Control itself.** `app.control.launcher` stays
  refused ALWAYS — the UI never offers it (the process menu shows a
  disabled item saying so; the target-app path routes straight to the
  audited refusal with no confirmation), BridgeControl refuses it
  app-side, and the bridge enforces it even if a client asks. Reason:
  disabling Control removes the only tool that can re-enable it.
- **Switch gating.** The switch unlocks only on a CURRENT bridge (the
  last ping's gate state). On a stale bridge it is disabled with a note
  naming the restart; on a missing bridge it says the bridge is not
  running. The switch must not promise what a stale bridge refuses.

### Protocol decision: bump to v7, fail closed

The override is a bridge-enforcement behavior change, so it takes the
standard version bump: `ShellBridgeMain.VERSION` 6 → 7 and
`BridgeGate.HOLD_WATCH_VERSION` 6 → 7, with the usual stale detection +
one-tap restart (DeepViewActivity and AdbStarter follow the gate
constant, so they need no changes). The client sends the switch state as
an ordinary boolean field, `override`, on SET_APP_ENABLED requests.

Fail-closed, two layers:

1. **An old bridge refuses.** A v6 bridge never reads the "override"
   field, so its SET_APP_ENABLED path computes exactly
   `decideEnable(pkg, override=false)` — and the harness pins that every
   refuse-listed package refuses under that input (leg 15). The flag
   cannot smuggle a protected disable past an old bridge, because the
   old bridge never looks at it.
2. **The gate forces the restart.** A v6 bridge classifies STALE, the
   Deep view shows the restart row, and the override switch stays locked
   until the ping reports v7.

The enforcement decision lives in pure-Java `ProtectedOverride`, shared
by the bridge (`checkedPackageForEnabled`) and the client
(`BridgeControl.setAppEnabled`, the Deep view menus and confirms), so
the list the UI warns about is the list the bridge enforces — the same
single-source-of-truth shape as `ProcessRules` (which still owns the
lists themselves). Refusal wordings for the non-override path are
byte-identical to the long-standing bridge strings.

### UI details

- The processes list + filter keep their classification and warnings
  unchanged: a protected row stays ⚠-marked and in the System group.
  The long-press menu offers Disable/Enable on a protected row ONLY
  while the override is on, with the label naming the risk ("Disable
  app — protected"); with the override off it shows a disabled item
  pointing at the Protected packages section. Kill is unchanged
  (offered, bridge refuses, confirm names the refusal).
- The strong-confirm dialog was factored into `strongConfirm(...)` so
  the switch can use the press-and-hold dialog without a bridge call;
  `confirm(...)` delegates to it for the strong tier — no behavior
  change to existing confirmations.
- `BridgeControl.setAppEnabled` sends `override=true` only when the
  switch is on AND the package is protected AND it is not Control
  itself (`ProtectedOverride.overrideApplies`); ordinary packages never
  carry the flag.

### Verification

- DeepControlHarness 474/474 (was 202; +272: decision matrix over both
  lists, consequence coverage, scope/audit/menu/switch-copy pins, and
  the old-bridge fail-closed leg including the gate↔bridge version
  agreement). BridgeGateHarness 55/55 (was 52; +3: v7 parse, future-v8,
  v6 stale-by-version). All other 25 suites unchanged and green.
- Grep-proven: zero `AccessibilityService` references; no bare `pm
  disable` element in any command construction (disable-user only, via
  `PmOutcome`); the "override" field is read in exactly one place
  (`ShellBridgeMain` SET_APP_ENABLED case).
- Unproven until Adam's phone: the switch in hand, a protected disable
  + re-enable round trip, the v6 → restart → v7 flow, and the audit
  entries on-device.

## v1.35 (2026-11-16) — bridge expansion: all remaining ADB capabilities

Adam: "Add all of those features to the bridge" — the full survey list
from the ADB-capabilities answer. Every remaining thing the shell user
can do over adb, as named bridge ops, in seven new Deep controls
sections. No new permissions. Bridge protocol v6: a v5 bridge
classifies STALE everywhere with the one-tap stale restart — Adam must
restart the bridge once after installing 1.35.

### Op inventory, by section

Apps: UNINSTALL_USER (`pm uninstall --user 0`, refuse-listed refused,
pm's Success required), INSTALL_EXISTING (`pm install-existing
--user 0`), LIST_REMOVED (the `-u` list minus the plain list, sorted),
INSTALL_APK (staged path only, see below), SUSPEND_APP / UNSUSPEND_APP
(`pm suspend` / `pm unsuspend` — a refusal passes through verbatim,
and an empty success says pm printed nothing), TRIM_CACHES (fixed
1 GiB target).

Display: WM_GET / WM_SET_SIZE / WM_SET_DENSITY / WM_SET_OVERSCAN
(`wm size/density/overscan`, verified by re-reading; the first
successful read records the effective values as the originals in
prefs, never overwritten; Reset restores exactly those, no confirm),
SETTING_GET / SETTING_PUT over the eight-key allowlist (system
font_scale, global window/transition/animator scales, system
screen_off_timeout, screen_brightness_mode, screen_brightness,
accelerometer_rotation — presets only, range-validated, verified by
re-read), NIGHT_GET / NIGHT_SET (`cmd uimode night yes/no/auto`).

Input: INPUT_KEY over the fixed 15-code menu, INPUT_TAP /
INPUT_SWIPE (coordinates validated against the live display bounds
app-side and a sanity bound bridge-side), INPUT_TEXT (pure-Java
escaping: `%` → `%%`, space → `%s`, controls dropped; the typed text
is never logged, only its length).

Capture: SCREENSHOT (`screencap -p`, PNG as base64 in the response
"data" field — see below), SCREENRECORD_START / STATUS / STOP
(10/30/60s, start-and-poll, file in Movies).

Power & scheduling: DOZE (`dumpsys deviceidle force-idle /
force-inactive / unforce / step`, state re-read), DEVICEIDLE_STATE,
BATTERY_GET / BATTERY_SET_LEVEL / BATTERY_UNPLUG / BATTERY_RESET
(level verified by re-read), STANDBY_GET / STANDBY_SET (`am
get/set-standby-bucket`, verified by re-read), RUN_JOB (`cmd
jobscheduler run -f`, job ids found via dumpsys jobscheduler in
Inspect).

System config: DEVICE_CONFIG_LIST / GET / PUT (put is strong
confirmed naming namespace+key+old→new, verified by re-read; an
empty value clears the flag and "null" is the confirmation),
WIFI_LIST / WIFI_FORGET (`cmd wifi list-networks / forget-network`;
the list parse is lenient and shows raw output when it cannot parse),
USERS_LIST / CURRENT_USER / USER_CREATE / USER_REMOVE (user 0 and
the current user are refused by the bridge with the reason),
LAUNCH_ACTIVITY (`am start -n`, wired into the v1.22 activities
screen: non-exported rows offer "Launch via bridge" when the bridge
is current).

Inspect: DUMPSYS over the fixed 11-service menu (activity, window,
battery, wifi, notification, power, package, meminfo, usagestats,
deviceidle, jobscheduler — 14s bridge timeout against the client's
15s), LOGCAT_TAIL (streaming, mirrors HOLD_WATCH: `logcat -T 20`,
bounded lines, optional substring filter, ends on disconnect/Stop/
cap; the Deep view Device-log surface gains Live log + Stop),
BUGREPORT_START / STATUS / STOP (start-and-poll, file in Download).

### Governance

Fixed command arrays only: every new command is built by the
pure-Java `BridgeOps` (argv construction, output verification,
allowlist enforcement, parsers) and the bridge runs it through
`runFixed`/`runFixedResult`/`runFixedBytes` — grep-proven, no
`Runtime.exec`, no shell strings, no new `pm disable` (the v1.33
disable-user fix stands). `runFixedResult` is the v6 addition: the
old `runFixed` discarded exit codes, and the v6 verifiers need both
the tool's words AND its exit status. Every write verifies from the
command's own output before success is reported (the v1.33 lesson),
refusals carry the tool's own words, and every write is audit-logged
through BridgeControl. Tiered confirmations throughout, with two
deliberate exceptions, both documented on the screens: input
injections need no per-press confirm (confirming every tap would make
them unusable, and a confirm dialog would show typed text on screen —
they are audit-logged instead), and resets (display originals,
battery reset, Doze unforce) need no confirm because they restore the
known-good state.

### Screenshot / screenrecord / bugreport retrieval

The bridge answers one JSON line per request, and the text field is
capped at 350,000 chars — a PNG does not fit. SCREENSHOT therefore
returns its base64 in a separate "data" field, outside the cap, and
the summary stays in "text" so no generic display ever dumps
megabytes. The bridge captures the PNG with stderr kept separate
(merging it would corrupt the image) and checks the PNG signature
before answering. The app saves the PNG to app-private storage (only
the latest is kept) and View/Share go through ImageShareProvider, the
same narrow one-file token pattern as ApkShareProvider. Recordings
and bugreports are start-and-poll (they outlive the 15s client
timeout): the bridge holds one slot each, STATUS reads liveness +
file size, STOP sends SIGTERM first (screenrecord finalizes its file
on a clean stop) and force-stops only if it does not exit. Files land
in shared storage — Movies for recordings (the gallery is the
retrieval; no media-read permission was added), Download for
bugreports — and every status names the exact path and size.

### APK install staging

The app-private-storage idea does not work: the shell user cannot
read the app's private files. So a picked APK is copied twice, both
by the app: once to its own cache, where PackageManager reads the
package it declares (a non-APK is refused here, before anything is
staged), and once to the app's own MediaStore Downloads entry with a
random token name — `/sdcard/Download/control-install-<token>.apk`,
the one path shape the bridge installs from, validated exactly. The
confirm names the file, package, and version; the staged and cache
copies are deleted after the attempt or on cancel. Never a URL, never
a download: the file picker is the only source.

### Deliberately left out

Add-network (joining a Wi-Fi network needs credential handling too
fiddly to do honestly in this pass — joining stays in Settings);
full `dumpsys` (far too large for the response channel and the
screen — the fixed menu is the scope); free-form settings keys (the
eight-key allowlist is the whole writable surface); a jobscheduler
list op (`cmd jobscheduler` has no per-package filter — run-by-id
plus the dumpsys menu is the honest scope). The `cmd wifi`
list-networks wording and the `cmd uimode night` read-back wording
are the least certain of the new parsers: both fail honestly (raw
output shown / "unknown") when Android 16 words them differently,
and the on-device pass decides.

### Verification

BridgeOpsHarness is new (433 checks: exact argv for every command,
output-verification shapes, allowlist enforcement, wm validation +
originals logic, input escaping, key menu, user-removal refusals,
suspend honesty, staged-path rule, device_config shapes, wifi/user
parses, launch + dumpsys menus, wm-state and long-task status
round-trips). BridgeGateHarness grows 49 → 52 (v6 parse, v5 STALE,
v5 refusal stale by version). All 27 suites green; every other count
unchanged. Zero AccessibilityService references. The v1.34 builder's
IPv6 relay was still running for the live Relay harness (28/28).

## v1.34 (2026-11-16) — sort the running processes: system vs the processes you can control

Adam: "On the running processes I only want to be able to sort the system
processes from the processes I can control." One control, nothing else: a
segmented All / Can control / System row at the top of the Running
processes section (small-button language, accent fill on the selected
segment, both themes). Default All — the v1.33 behavior on open. Tapping a
segment filters the displayed rows immediately from the fetched list; no
re-fetch, no persistence of the choice. Refresh re-fetches and re-applies
the selected filter.

The classification is `ProcessRules.groupFor` (pure Java, harness-tested),
and it uses ONLY the signals v1.33 already computed — no new heuristics,
no new bridge data. CONTROLLABLE means exactly the rows whose long-press
menu offers Kill and Disable/Enable: a known package mapping AND not
refuse-listed AND not system-marked. SYSTEM is everything else:
system-marked rows (even with a mapping, e.g. SystemUI), refuse-listed
rows (even with a mapping, e.g. Play services), and unmapped rows
(Details only). The ⚠ markers, the Details-dialog protection reasons, and
what each row can do are unchanged — the filter only changes which rows
are shown.

Counts and cap honesty: the status line always names the fetched total
and both group sizes ("7 processes — 3 can be controlled · 4 system"),
and under a filter it names the group, the other group, and the loaded
total. The 300-row cap bounds the rows rendered; under a filter the
status line says "showing the first N of this group", never implying the
filter searched beyond the fetched set. An empty group shows an honest
empty state ("No processes you can control in this load — Refresh to
load a fresh list."), never a stale list.

Verified: DeepControlHarness gains classification + filter legs (see the
harness header); all 26 suites green. No new permissions, no bridge
protocol change (still v5 — no bridge restart needed for this update),
zero AccessibilityService references.

## v1.33 (2026-11-16) — Deep controls redesign: compact sections, running-processes list, package-disable fix

Adam: "way too much text" in Deep view — make it more user friendly, with
running processes as a list and long-press commands on a process. And: "Why
cant I disable packages using the shell user?" Both are answered here.

### Deep controls is compact sections now

`DeepControlActivity` keeps every control, explanation, and audit surface
from v1.32 — nothing was removed — but each group sits behind a disclosure
header (▸/▾) that opens with one tap: About these controls, Target app
(expanded by default — it is the working context), Running processes,
Permissions, AppOps, App state, Background policy, Private DNS, Launcher
system setting, Result. The screen opens as a title, one bridge-status line,
and nine headers instead of a wall of text. All explanation copy is verbatim
from the old layout, inside its section. The Result section auto-expands when
a control runs. Both themes (all text/colors through `Ui`, no hardcoded
colors). The stale "governed allowlist v2" status copy (flagged since
v1.15.1) is fixed: the status line now names the version the bridge actually
reported, via `BridgeGate.versionName`.

### Running processes: a list, with long-press commands

A new "Running processes" section lists live processes from the bridge's
existing PROCESSES op (fixed `ps -A` — no new bridge op for listing). Rows
are name + one status line (PID · state · memory · system/protected
markers); system or refuse-listed rows carry a ⚠. The list loads only when
the section expands or Refresh is tapped — never a background poll — and a
bridge failure shows the honest error, never an endless spinner. Display is
capped at 300 rows in ps order, with the status line saying so when capped.

Shaping is pure Java in `ProcessRules.java` (harness-exercised): ps lines
that do not parse are skipped, never misread; the package mapping is the
process name up to the first ':' and ONLY when it looks like a package name
AND is installed — a custom process name like
`com.google.android.gms.persistent` stays unmapped, never guessed. Tap a row
for a Details dialog (PID, parent PID, user, state word, memory, package or
an honest "no package mapping", protection reason). Long-press opens a
`PopupMenu` (the Connections-screen pattern): Details always; Kill and
Disable/Enable only when a mapping is known — without one, the menu shows a
disabled item saying why instead of offering commands that cannot run.

**Kill is the existing governed kill path.** There is no per-PID kill op in
the bridge, and none was added: the shell user cannot `kill()` another UID's
process (no CAP_KILL), so a KILL_PID op would fail for nearly every row.
Kill runs the existing `FORCE_STOP` path via `BridgeControl.forceStop`, and
the confirmation dialog says exactly that — it names the process and PID,
explains that every process of the app ends because Android gives the shell
user no per-process kill, and carries the warning for system or
refuse-listed targets. The brief's "Force-stop app" item IS that same path,
so the menu's third command is Disable/Enable through the fixed
SET_APP_ENABLED op below (shared `toggleEnabledFor`, same strong
confirmation for system/destructive targets). All three commands go through
the existing confirmations and the existing audit path; a refused target is
refused by the bridge with its reason, and the attempt is audited. After any
control runs, the list refreshes itself when the section is open.

**Refuse-lists, one source of truth.** `ProcessRules` now owns
`REFUSE_ALL` / `REFUSE_DESTRUCTIVE` (contents verbatim from the bridge);
`ShellBridgeMain` reads them from there, so the list the UI warns about is
exactly the list the bridge enforces. `BridgeControl`'s own minimal sets
are untouched (its client-side pre-check result was already ignored — the
bridge is the authoritative refuser).

### Package-disable fix: disable-user + verified pm output

Root cause, verified from code: `SET_APP_ENABLED` ran plain
`/system/bin/pm disable <pkg>` — the SYSTEM-level disable, reserved for
system/signature holders behind CHANGE_COMPONENT_ENABLED_STATE. The shell
user is denied, so every disable failed. Second bug: the op reported
success unconditionally without checking pm's output, so the refusal
looked like success (unlike CLEAR_APP_DATA, which checks for "Success").

The fix (`PmOutcome.java`, pure Java, harness-exercised):

- Disable runs `pm disable-user --user 0 <pkg>` — the per-user variant the
  shell user IS allowed. Same visible effect: the app cannot run and is
  hidden. Enable keeps plain `pm enable`, which clears the user-disabled
  state (its "new state: enabled" line confirms it).
- pm's output is verified before success is reported: success requires NO
  error marker (error/exception/unknown package/not installed) AND the
  expected "new state:" line ("disabled" covers both `disabled` and
  `disabled-user`). Empty, null, garbage, wrong-action, or error-bundled
  output is a failure carrying pm's own words. A refusal can never again
  read as success. Grep-verified: no bare `"disable"` element remains in
  any pm command construction (the remaining `"disable"` strings are the
  `svc` radio toggles and display text).

### Bridge protocol v5 + the restart requirement

The fix lives in `ShellBridgeMain`, which runs as a separate app_process
that SURVIVES APK upgrades — so after installing 1.33 the running bridge
is still v4 code until it is restarted (the v1.15.1 lesson). Handled the
established way: `ShellBridgeMain.VERSION` 4 → 5 and
`BridgeGate.HOLD_WATCH_VERSION` 4 → 5, so a v4 bridge classifies STALE
everywhere (overlay gate, watchdog, Settings, Deep view) and the Deep
controls status line names it ("running old code (v4)") with a one-tap
"Restart bridge" button calling the existing
`AdbStarter.restartStaleBridge` (stop-then-start; the plain restart would
no-op on a running bridge). **Adam must restart the bridge once after
installing 1.33 for disable to work** — the status line says so until he
does. No new permissions; no AccessibilityService; no device-owner code
(the launcher's device admin stays limited to force-lock + wipe-data).

### Verification

- New `DeepControlHarness`: **150/150** (ps parsing, package candidates,
  mapping, refuse matching, warning flags, status/detail lines, pm
  command construction, pm output verification incl. every failure
  shape, end-to-end fixture).
- `BridgeGateHarness`: **49/49** (was 44; +5 for v5: v4 now STALE, v5
  CURRENT, v4 refusal stale, v5 fragments).
- All 24 other suites green at their v1.32 counts: WordDict 497,
  LowData 129, Bridging 157, Camera 461, Updater 143, Share 145,
  AudioModem 71, NfcArm 40, WalkieMode 70, WalkieLayout 30, Relay 28
  live, AdbConnect 15, AppActivities 36, AppListCodec 22, History 78,
  HoldWatch 64, Nat 190, RingMath 101, Setup 85, Subscription 75,
  Terminal 29, TerminalScroll 32, Wallet 56, Watchdog 58.
- APK: 20,788,606 bytes, SHA-256
  `ffa6fd96e80434ce292c0e8adecc72eba041d21fd19c88c9b79f8156ea381c6c`,
  versionCode 38 / 1.33, same signing cert, 28 permissions unchanged,
  0 AccessibilityService references in the dex.
- On-device proof still needed: compact sections with everything
  reachable; processes list populates; long-press Details/Kill/
  Disable-Enable with confirmations + audit; system process refused
  with reason; disable works after the bridge restart (app hidden +
  can't run); re-enable works; a refusal reports honestly; stale
  detection fires on the old bridge and the one-tap restart fixes it.
  Also unproven: the toybox ps dialect and pm's exact wording on
  Android 16 — both fail honestly (skipped lines / unconfirmed
  outcome) if they differ.

## v1.32 (2026-11-16) — every message encoded, no marker, long-press encoded view

Adam's direction (2026-11-16, on 1.30): short messages looked
unencoded; he wants ALL messages encoded, and with no
per-message header saying so — "it just automatically knows
it has to decode it" — plus a long press on a message to view
the encoded form.

ENCODE ALL: the v1.28 savings gate is REMOVED.
WordDict.encode()/encodeScoped() always emit the coded wire
form, on every road (mesh/LAN, Bluetooth, relay walkie, chat
DMs). The "wire never grows" guarantee is GONE by Adam's
choice: a one-word message costs its code, and an
escape-heavy message may grow slightly. The v1.28
never-wrong-decode rule stands: a payload that does not parse
entirely as valid coded form renders raw with the honest
undecoded note, never a silent mis-decode.

NO MARKER — the wire is self-identifying by SHAPE, at zero
extra bytes:
- Mesh/Bluetooth: coded texts ride a NEW packet type 'D'
  (the type byte already existed). The coded payload sits
  on the "w" key inside the packet JSON; "wdt" rides as
  before. The host relays 'D' packets verbatim, like 'T'.
- Relay walkie + chat: the coded payload rides a new "w"
  key instead of "text" in the envelope
  (LowDataEnvelope / ChatEnvelope, the latter extracted
  from ChatThreadView into pure Java so the harness
  proves it). "ld" and "wdt" ride exactly as before.
Strict decoding (WordDict.decodeCoded) gates the new shape:
a coded payload must parse ENTIRELY as codes, escapes, and
literals — a stray letter, emoji, or control byte outside an
escape refuses with the corrupt note. The lenient decoder
stays for legacy "wd"-marked payloads, byte-for-byte the
v1.28 behavior. If encoding itself throws, the sender falls
back to WordDict.escapeOnly (the whole text as escape runs)
so the message still arrives readable in valid coded form
instead of vanishing.

LEGACY COMPAT (receiving — this build reads everything):
- "wd":1/"wd":2 messages decode forever: in-flight, stored,
  and from old-build senders (decodeMarked, unchanged).
- Legacy PLAIN messages (no marker, old "text" key / 'T'
  packets) display as plain text. The receiver
  distinguishes by shape/key, never by guessing content.
- Teach blocks ("wdt") unchanged in behavior: bounded,
  applied before decoding, riding the new coded shape.
  Learned-word submission (v1.30 DictSubmit) unchanged.
  Capitalization/escape rules unchanged. The audio modem
  is untouched. Bridging re-sends on the new shape and
  never bridges a message it cannot decode (existing rule).

BREAKING CHANGE (sending — both sides need 1.32+): an old
build receiving the new form cannot decode it. Exactly:
- Mesh/Bluetooth: an old build's packet switch has no 'D'
  case — the packet is DROPPED silently (default branch).
  The old build never sees the message at all.
- Relay walkie: an old build's envelope parse finds no
  "text" key and returns null, so the payload falls
  through to the burst path and lands as a VOICE entry
  whose blob is the envelope bytes (playing it is noise).
  The old build never sees the text.
- Chat: an old build's unwrap finds no "text" key and
  shows an EMPTY message (optString default "").
No copy promises old builds read new messages. This is the
same breaking-change shape Adam already accepted for the
v1.26 audio modem change.

WIRE-FORM PERSISTENCE + LONG-PRESS VIEW: the actual wire
form is recorded with each entry at send/ingest time, never
re-encoded (a re-encode could differ as the dictionary
changes):
- Walkie entries carry "wire" (the payload exactly as
  sent/received), "plainBytes" (the original text's UTF-8
  byte count, -1 when unknown because the payload did not
  decode), and "coded" (whether it arrived coded) —
  stamped by WalkieStore.stampWire from every road: the
  mesh/BT listeners (SentText carries the sent form),
  sendTextViaRelay (LowDataEnvelope.buildDetailed), the
  outbox flush (markQueuedSent), and RelayIngest.stampEntry
  for both relay receive paths.
- Chat messages carry the same three fields on Msg
  (ChatEnvelope.Built/Unwrapped).
- Pre-v1.32 entries lack the keys and read as unrecorded,
  never a crash. HistoryRule moves the whole entry, so a
  played message keeps its wire form in History. A
  bridged entry stores the form AS THIS PHONE RECEIVED IT,
  with its "relayed by <name>" provenance kept visible.
Long-press a message in the walkie thread (or History) or a
chat thread opens a menu with "View encoded message" (the
walkie menu also keeps "Details"). The dialog
(EncodedMessageDialog, shared) shows the DISPLAY form of
the wire text (symbol + real Unicode super/sub digits via
WordDict.wireDisplayForm — null for invalid input, in which
case the raw wire shows), "Sent as N bytes, was M bytes"
(the size line omitted when the original size is unknown),
"Sent plain" + the text for legacy plain messages, the
honest "The coded form was not recorded for this message."
for pre-v1.32 entries, and the wire as received + the note
for undecoded messages.

Harnesses: WordDict 497 (was 385 — legs 3/5/9/10/14
rewritten for encode-all and the new shape, leg 20 added),
LowData 129 (was 124 — envelope leg rewritten, old-client
leg now asserts the documented silent miss), Bridging 157
(was 156 — envelope leg decodes the "w" key; "relay me"
normalizes to "Relay me"), Subscription 75 (unchanged
count — two expectations updated for normalization). All
other suites unchanged and green; see the sweep in the
v1.32 report.

## v1.31 (2026-11-16) — camera monitoring page: network cameras, auto-detect, mesh proxy

Adam's direction (2026-11-16): a Cameras page for network
cameras, the easy version with auto-detect. A fifth pager
page (after Chat), a tenth circle-menu destination, and a
Cameras sub-ring listing the saved cameras. The page shows
a two-column grid of tiles — latest snapshot, name, status,
last-refresh time — tap for the full-screen view (large
snapshot, name, address, Refresh, Edit, Remove). Refresh:
per-camera button, refresh-all, and auto-refresh while the
page is visible at each camera's own interval (5s/10s/30s/
60s/off, persisted, default 10s). A camera that stops
answering keeps its last picture, grayed, with "Unreachable
since <time>" and a retry — never a spinner, never a blank
tile hiding the failure. A 401 says "Check the username and
password" and stops auto-refresh for that camera until the
user retries — never a retry loop hammering a wrong
password. Copy is honest throughout: snapshots, not video,
except where MJPEG is advertised.

AUTO-DETECT (three layers, ONLY while the user taps Scan —
never background scanning; a visible "Scanning…" state with
a Stop; results dedupe by IP, nothing added without the
user choosing it):
1. mDNS via NsdManager: _http._tcp, _rtsp._tcp, _onvif._tcp,
   _axis-video._tcp, _camera._tcp. Plain _http._tcp alone
   never lists a device — it must be a camera-marking
   service type or a camera-named instance.
2. ONVIF WS-Discovery: UDP multicast probe to
   239.255.255.250:3702, ProbeMatches parsed for XAddrs +
   scopes (name/model). Needs a MulticastLock, which is
   why this version adds CHANGE_WIFI_MULTICAST_STATE
   (normal-level, 28 permissions total).
3. Bounded subnet sweep fallback: the CURRENT Wi-Fi /24
   only, ports 80/8080/8000/554/443, short timeouts,
   Server-header + body fingerprinting, ~15s max. Only
   camera-like hosts are reported — generic Apache/nginx/
   lighttpd answers do not qualify, and GoAhead/Boa need
   a camera-ish body hint.

FEED RESOLUTION, in order: (a) ONVIF GetSnapshotUri via
SOAP when auth allows; (b) known-pattern probes AFTER
credentials are entered (generic + vendor patterns for
Hikvision, Dahua/Amcrest, Reolink, Axis, Foscam, UniFi —
first 200 with image/* wins, probing stops at the first
401); (c) a hand-typed URL. MJPEG where offered (ONVIF
stream URI or known MJPEG patterns): the tile streams
live (up to 4 concurrent grid streams) with a "Live"
badge, and the detail view streams too; otherwise
snapshots. The MJPEG parser is pure Java (multipart/
x-mixed-replace, boundary-split-safe at any feed point,
2MB frame cap with resync, SOI-only emission).

HTTP: the fetcher is a minimal HTTP/1.1 GET over a raw
socket (CamHttp) — deliberate, because the manifest sets
usesCleartextTraffic="false" and the platform stack
refuses LAN plain-HTTP. GET only, http:// only (https is
refused: self-signed certificates), no redirects, capped
bodies, one unauthenticated attempt + ONE auth retry.
Credentials NEVER travel in URLs (userinfo is rejected
everywhere, including in ONVIF URIs and stored paths).
Digest auth is implemented properly (CamDigest, RFC 2617
MD5/MD5-sess, qop auth > auth-int > none, per-camera nc
tracking); SHA-only challenges are refused honestly, and
Basic is the fallback when Digest is not offered.

CREDENTIALS: typed once per camera, sealed with the
wallet's Keystore AES-256-GCM seam (WalletCrypto) under a
separate alias, control-cameras — no second crypto
scheme. Stored in cameras.json as ciphertext only; never
in the profile export, never logged, never in the clear
in any file. No default passwords are ever tried. Last-good
images live in cam-last/<id>.jpg, device-local, deleted
with the camera.

HOTSPOT TOPOLOGY: cameras with no Wi-Fi join the phone's
REGULAR hotspot (fixed SSID/password, flipped by the user
in Settings — the app cannot turn the regular hotspot on
programmatically and never claims it can; the v1.25
local-only share hotspot is the wrong tool here: rotating
credentials, dies with the app). Cameras + phones on the
hotspot LAN need no extra code: the mesh's LAN road
connects phones, the page reaches cameras by IP. PROXY:
the hotspot phone app-level-proxies snapshots to mesh
peers NOT on the hotspot — a mesh/LAN request is fetched
by the proxy phone from its hotspot LAN and served back
over an HTTP server on port 8993 (the v1.25 pattern) with
UDP announcements on 8994 (ids + names only). Kernel IP
forwarding needs root and is NOT available — the proxy is
application-level, JPEG bytes only, credentials never
leave the proxy phone. The "Share cameras with the mesh"
switch (Prefs KEY_CAM_SHARE) governs it, DEFAULT OFF;
when off, nothing is served. Proxied cameras appear in
the grid marked "via <phone name>", session-only, expiring
after 15s without an announcement. MJPEG is direct-only;
the proxy serves snapshots.

HONEST LIMITS (in copy and here): current Wi-Fi/hotspot
LAN only; cloud-only cameras (Ring/Nest/Wyze cloud)
cannot be pulled directly — the empty state says so; no
video through the relay; NO RTSP live video (snapshots +
MJPEG only); no recording, no motion alerts, no PTZ.

FILES: CamDigest, CamHttp, MjpegParser, CamOnvif,
CamDiscovery, CamFeed, CamProxy, CamRecord, CamStore,
CamScanner, CamFetcher, CamProxyServer, CamProxyClient,
CameraPage (all new); MainActivity (PAGE_CAMERAS=4,
cameraPage, ring sub-ring, EXTRA_CAMERA_ID),
CircleMenuView (ITEM_CAMERAS=9), PageGlyphs (CAMERA=15),
RingItems (Action.OPEN_CAMERA), OverlayRingSource
(cameras sub-ring + routing), Prefs (KEY_CAM_SHARE),
AndroidManifest (CHANGE_WIFI_MULTICAST_STATE),
build-apk.sh (36/1.31), run-launcher-harnesses.sh
(Camera suite). CameraHarness: 461 checks, all 25 suites
green. No socket test in the harness: the sandbox garbles
JVM IPv4 loopback, so the HTTP/MJPEG/ONVIF paths are
proven over byte arrays and in-process handlers (the
Share harness rule); the socket byte flow is verified by
the on-device checklist below.

ON-DEVICE CHECKLIST: scan finds a real camera on the LAN;
add with credentials; the snapshot grid refreshes; the
MJPEG live badge appears where offered; an unreachable
camera shows its last image + honest status; manual add;
hotspot topology — cameras on the regular hotspot are
visible; proxy switch off = nothing served, on = a second
phone sees "via" cameras.

## v1.30 (2026-11-16) — dictionary write-back: learned words flow back to the GitHub dictionary

Adam's direction (2026-11-16): when the phone learns a new word
it should flow back into the shared dictionary, merged
automatically when it is clean. The phone STILL never writes to
the repo — there is no GitHub token in the app, ever. The
write-back is a three-hop path: the phone proposes learned words
to the Control Relay queue (the same signed-request auth as
chat/walkie), and an off-phone merge job validates, appends, and
pushes. Merges are batched hourly; no copy anywhere promises an
instant merge.

THE RELAY ENDPOINTS (deployed with this version; full contract
in the relay's PROTOCOL.md §7.5/§7.6):

- `POST /v1/dict/proposals` — the existing Ed25519 signed-request
  prelude (pubkey + timestamp + signature, 5-minute replay
  window, 600 req/min/key, registered devices only). Body
  `{"words":[...]}`: 1–100 words, deduped server-side. Each word
  must be 2–30 chars, lowercase a–z, with at most one apostrophe
  strictly inside — one bad word refuses the WHOLE batch (400
  `invalid_word`, naming the first offender). Rate limit: 100
  words per key per rolling 24 hours (429 `rate_limited`);
  refused batches consume nothing. Storage: `dict_proposals`
  (word PK, first_seen, seen_count, source_count, merged_at)
  plus `dict_proposal_sources` (word + SHA-256 of the pubkey) —
  the queue holds words + counts only, never a pubkey.
  Re-proposals bump seen_count, and source_count when a new key
  proposes the word; a merged word's `merged_at` is never
  resurrected.
- `GET /v1/dict/queue` — CRON_SECRET Bearer guard (the purge
  pattern). Returns unmerged words + counts, oldest first, cap
  10,000.
- `POST /v1/dict/queue/ack` — same guard. Body `{"words":[...]}`
  marks them `merged_at = NOW`, idempotently.

THE APP (this version): `DictSubmit.java` holds the pure submit
rules — the submittable shape (mirrors the server), batching in
first-learned order, the hourly throttle, the 100-word cap, and
the exact JSON body. `DictStore.maybeSubmitLearnedWords()` runs
the plan on the dictionary worker: at most one submit per hour,
only when unsubmitted words exist, only when the relay answers —
a `GET /v1/channels` reachability probe first, so an offline or
relay-less network costs nothing. The submit itself goes through
`RelayCore.proposeWords()` — the SAME signed-request path as
chat/walkie, no second auth system — on the worker thread, never
blocking a send or any UI. Failures are silent and retry at the
next window; a 429 marks the words submitted anyway (the server
already counted them toward the budget; re-sending would never
help). Submitted words persist in the private file
`dict-submitted.txt` (one word per line, capped at 50,000,
oldest dropped first; the count mirrors into Prefs as
`dict_submitted_count` for the row) and are never re-sent from
this phone. The trigger runs after every dictionary build
and every learn — including teach adoptions, so a word learned
from another phone is proposed too (that is what `source_count`
is for). The Dictionary row gained one line, "Shared N words",
shown only when N > 0. No new permissions.

THE MERGE SCRIPT (`~/workspace/dict-merge/merge-dict.py`, mode
700): GETs the queue with the CRON_SECRET Bearer, re-validates
every word, fetches the current `dictionary.txt` + `version.json`
from the raw GitHub URL, drops words already present
(case-insensitive), appends the new words in FIRST_SEEN order
(the file stays append-only — never sorted, never edited),
bumps `version.json` (version+1, new count, new sha256), commits
and pushes via the secure PAT route from the ControlDictionary
clone (`git fetch` + `reset --hard origin/main` first, trees
verified), then ACKs the merged words. `--dry-run` does
everything except the push + ack. Any failure exits non-zero
with one log line, no partial push, no ack without a verified
push, never force-push. The merge runs on a schedule the parent
agent owns — no cron is created by this build.

GRADUATION (re-verified, harness-simulated): a word that was in
the learned section and then arrives in a fresh `dictionary.txt`
is restored through the same `restoreLearned` alias path v1.29
built — sends prefer the base code, the old learned code still
decodes (the alias rule), unmerged learned words keep their
codes, and taught sets survive the table swap untouched. The
WordDictHarness MERGE leg runs the whole swap through real
`DictFile` bytes: build before → learn → encode with the learned
code → build after (word appended) → restore → encode with the
base code → decode the old wire.

Harness: WordDictHarness grew two legs — SUBMIT (42 checks) and
MERGE (27 checks) — 385 checks total; all 24 prior suites green.

Files changed in this version: new `DictSubmit.java`;
`DictStore.java` (submit hook + shared-count), `RelayCore.java`
(`proposeWords`), `Prefs.java` (submitted-words + last-submit
keys), `WordTable.java` (`learnedWordsByIndex`),
`SettingsActivity.java` (row line). Relay: `lib/db.ts`
(dict_proposals schema), `lib/ratelimit.ts` (word budget),
`lib/store.ts` (proposal store), `lib/route-helpers.ts`
(cron guard), three new route files, `PROTOCOL.md`,
`scripts/dict-verify.mjs` (48 checks, all passing locally +
live auth checks).

## v1.29 (2026-11-16) — the dictionary lives on GitHub: 370,164 words, learned words, in-band teaching

Adam's direction (2026-11-16): a complete dictionary with all
words, hosted on GitHub, fetched by the app in the background —
with the app adding words it sees to its own on-device
dictionary file. The phone NEVER writes to the repo (no token
in the app, ever — a baked-in write key could be extracted by
anyone holding the app); merges happen off-phone only. There is
NO upload path for learned words in this version, and no UI
promises one — the write-back path is a separate later build.

THE REPO: https://github.com/doeyeknow/ControlDictionary
(public, created 2026-11-16). Contents: `dictionary.txt` (one
word per line), `version.json`
(`{"version":1,"count":370164,"sha256":"5cdf9205…47dd895","format":1}`),
and a README stating the APPEND-ONLY rule (entries are never
reordered, removed, or edited — codes are positional, so any
reorder breaks every coded message), the format, and the
no-phone-write rule. Pushed commit b17048f8; the raw file was
fetched back and verified byte-identical (count + sha256).

THE WORD LIST: 370,164 words, no arbitrary truncation. The head
is the v1 1,200-word list FROZEN in its exact v1.28 order
(extracted programmatically from the shipped WordDict code, so
every v1 code is unchanged). The tail is 368,964 words from
`words_alpha.txt` in dwyl/english-words (MIT license), deduped
and stripped of any word already in the head (case-insensitive).
The file is lowercase except the frozen head's I-forms. The
harness fixture `harness/dictionary.txt` is a copy of the
shipped file, pinned by count + sha256.

CODE MATH (extends v1.28): indices below 1240 keep the exact
v1.28 two-byte codes (symbol + base-20 digit). At and above
1240, codes are FOUR bytes and self-delimiting: symbol +
lead + two base-62 digits, where the lead alphabet is 42 chars
(`klmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`) chosen DISJOINT
from the 20 digit chars, so the second byte decides the form
unambiguously. Span: 62 x 42 x 62 x 62 = 10,009,776 long codes;
MAX_INDEX = 10,011,015. Learned indices start at 5,000,000.
Every code byte is printable ASCII (the old-build degradation
rule is unchanged). The display form for long codes is the
wire form itself.

FILE FORMAT + SECTIONS: the fetched text builds
deterministically into a packed blob (`CDIC` magic, format 1,
count, (count+1) big-endian offsets, concatenated UTF-8
words) in app-private storage (`dict-base.blob`, atomic
temp+rename — a half-written file is never used). The base and
learned sections are INDEPENDENTLY versioned: learned words
live in `dict-learned.txt` and taught sets in `dict-taught.txt`,
so a list rebuild never loses them. Both stay on-device: never
uploaded, never in a profile export.

FETCH / VERIFY / REBUILD (DictStore, mirroring UpdateManager):
on launch, throttled to ~1/hour, failures silent: fetch
version.json over HTTPS only (manual redirects, each hop
re-checked, 64KB cap, cache-busted); when the version or sha
differs, download dictionary.txt (16MB cap) and verify its
SHA-256 against version.json BEFORE use — a mismatch discards
the download and keeps the old file. The blob is built,
count-checked against the manifest, written atomically, and
the active table swaps carrying the learned snapshot. While
fetching or building — or on a corrupt/missing file — the
codec falls back to the v1 1,200-word list that stays in code,
so sending always works immediately and is never wrong.
Corruption triggers a background rebuild. Settings shows a
Dictionary row: version + word count + last-check status
(building / ready / built-in list / check failed); tapping
checks now.

LEARNED WORDS: words seen in sent text and cleanly decoded
received text (never undecoded payloads, whose code fragments
would poison the table) that are not in the list are appended
with the next free codes, stable forever, surviving restarts
and rebuilds. Learnable shape: 2..64 chars, all-lowercase or
Capitalized; ALL-CAPS runs and single letters are skipped.
Cap: 50,000 entries, then learning stops (honest at-cap
behavior: new words simply travel raw). Learned codes go on
the wire ONLY after the peer/channel was taught them (see
below).

IN-BAND TEACHING: a learned code rides to a scope only after
that scope was taught it. The first message spells the word
out AND carries a teach block (`"wdt"`: `word:CODE` entries,
max 50 per message, 4KB cap) — the block rides even on plain
(unencoded) messages, so teaching completes on first use
instead of waiting for a message long enough to absorb it.
The receiver applies the block BEFORE decoding, so codes
taught by a message resolve in that same message. Taught sets
are per-peer (DMs) and per-channel (walkie/mesh), persisted,
capped at 500 scopes. CONFLICT RULE: the sender's code is
authoritative — the receiver ADOPTS the taught code for
future sends while still DECODING its old local code for
in-flight messages (kept as an alias; a displaced word loses
its code and re-learns later). GRADUATION RULE: when a word
exists in both the base and learned sections, the encoder
prefers the BASE code and the decoder accepts EITHER code
(the old learned code survives a merge as an alias). KNOWN
LIMIT: a late channel joiner sees the honest undecoded note
for learned words until re-taught; there is no re-teach
protocol in this version; base words always decode.

FRAMING: `"wd":2` for messages using the full/learned
dictionary; `"wd":1` still decodes, and v1-only messages stay
`"wd":1` when cheap (the version describes the wire, not the
teach block, so v1.28 builds still decode head-only wires —
they ignore the `"wdt"` key). Newer-version, corrupt, and
untaught payloads render undecoded with honest notes
(NOTE_NEWER / NOTE_CORRUPT / NOTE_UNTAUGHT), never spoken,
never bridged. Savings, capitalization, and escape rules are
unchanged. The audio modem is untouched.

FILES: new DictFile.java, WordTable.java, TeachBlock.java,
TaughtStore.java, DictStore.java; WordDict.java refactored
onto WordTable (WORDS array untouched byte-for-byte, VERSION
2); Prefs.java gains the dict keys; MeshNet/BtWalkie putText
gain scope overloads + noteReceived on receipt;
LowDataEnvelope gains a scope build + adopted on parse;
BridgeRule.buildRelayedText gains a scope overload;
RelayIngest.Decoded gains adopted; ChatThreadView, WalkiePage
(relay send/bridge/poll), and SubscriptionService (ingest +
onCreate init) wire the scopes; MainActivity calls
DictStore.onAppLaunch; SettingsActivity gains the Dictionary
group; build-apk.sh 34 / "1.29". No new permissions (27),
zero AccessibilityService references, RECEIVER_NOT_EXPORTED
kept.

VERIFICATION: WordDictHarness 316/316 (157 prior + 159 new:
long codes, DictFile determinism/corruption, learned +
graduation, two-table teach + conflict adoption, taught
store, full-list round trip over the actual dictionary.txt,
versions, scoped packet overloads). All 24 harnesses green (Updater 143, Share 145,
AudioModem 71, NfcArm 40, Bridging 156, WalkieMode 70,
WalkieLayout 30, Relay 28 live, AdbConnect 15,
AppActivities 36, AppListCodec 22, BridgeGate 44, History 78,
HoldWatch 64, LowData 124, Nat 190, RingMath 101, Setup 85,
Subscription 75, Terminal 29, TerminalScroll 32, Wallet 56,
Watchdog 58). APK 20,731,262 B, sha256 ed7bfb33…, versionCode
34, same cert. Savings preserved (100→78, 89→63, 143→90,
879→549) plus tail-word savings (118→70). **Still unproven
until Adam's phone**: the first-launch fetch + build, the
Settings row transitions, two-phone teaching, restart
persistence, and the late-joiner note.

## v1.28 (2026-11-16) — word-dictionary message compression

Adam's design (2026-11-16): a dictionary translating words into a
single character with a superscript or subscript digit; messages
encode on send and decode on receipt, so less data travels. It
applies to walkie TEXTS on all three roads (mesh/LAN, Bluetooth,
relay) and to chat (relay DMs). The audio modem is untouched — it
carries keys and cards, never chat text — so the size win lands
on mesh/Bluetooth/relay text payloads.

CODE SPACE: 62 base symbols (a-z, A-Z, 0-9) x 10 digits x 2
positions (superscript / subscript) = 1,240 codes. The
dictionary is 1,200 entries (40 codes spare), compiled from
general word-frequency knowledge — the most common English
words, contractions, and the app's own vocabulary (channel,
mesh, relay, walkie, bridge, gateway, quiet, filter, ...). The
ordered word list IS the table: a word's code index is its
position in `WordDict.WORDS`, and index arithmetic derives
(symbol, digit, position). Entries are never reordered or
removed within a version — only appended, with a version bump.

WIRE FORM: two bytes per code. Byte one is the base symbol;
byte two is a base-20 digit: '0'-'9' for superscript 0-9,
'a'-'j' for subscript 0-9 (the second byte is the raw
remainder, so e.g. index 1 is "a1" — digit 0, subscript —
and index 10 is "aa" — digit 5, superscript). Both bytes are
printable ASCII. DISPLAY FORM (humans only, never sent):
the symbol plus a real Unicode superscript/subscript digit,
via `WordDict.displayForm()`.

ESCAPES: words outside the dictionary — names, places, rare
words, numbers, emoji, ALL-CAPS — travel spelled out inside
an escape: '~' + two decimal digits of length + the raw run,
chunked at 99 chars. Nothing ever fails to send. Punctuation
and whitespace pass through verbatim. A '~' in a message
travels inside an escape, so it can never be mistaken for
one. Length counts UTF-16 chars, the units both Java
endpoints share.

CAPITALIZATION (one rule): a word encodes when it matches
ignoring case AND (all-lowercase, or exactly the stored
form, or Capitalized at a sentence start). Sentence start =
first word, or a word after sentence-ending punctuation plus
whitespace. On decode, a sentence-start code ALWAYS decodes
capitalized — a lowercase sentence-start normalizes to a
capital, the way phone keyboards autocapitalize. The
dictionary stores "I" and its contractions capitalized, so
"i" normalizes to "I" and "i'm" to "I'm". Those two
normalizations are the only cases where decode does not
reproduce the input byte for byte; everything else
round-trips exactly. Both the encoder and the decoder track
sentence state over the same character stream, so they
always agree.

FRAMING: encoded payloads carry the marker `"wd":1` in the
existing envelope — the relay JSON key
(`LowDataEnvelope.build`, `BridgeRule.buildRelayedText`),
the mesh/Bluetooth 'T' packet key (`MeshNet.putText`,
`BtWalkie.putText`), and the chat envelope
(`ChatThreadView.sendText`). No marker means plain text.
`WordDict.encode()` returns null when coding would not save
bytes (wire + 8-byte marker overhead vs raw), so short
messages keep the exact pre-v1.28 shape and the wire is
never longer than today. Decode sits at the single receive
point per road (`WordDict.decodeMarked`, `LowDataEnvelope.
parse`, `ChatThreadView.unwrap`, the 'T' handlers), so every
road shares the rule. A receiver whose dictionary version
differs, or a payload that fails to parse, renders
UNDECODED with an honest note ("Sent with a newer word
list (vN) — shown as sent." / "Couldn't decode this message
— shown as sent.") instead of decoding wrong; decode never
throws. The note rides the thread entry as `wdNote` and
shows under the text; a noted entry is never spoken aloud
(coded text read aloud is gibberish) and never bridged (the
gateway cannot vouch for content it cannot read, and
re-coding coded text would hand the far side a
doubly-wrapped payload).

OLD-BUILD DEGRADATION (honest): a pre-v1.28 build ignores
the unknown "wd" key and shows the coded text — odd-but-
printable ASCII, never garbage bytes, never a crash. Both
phones need v1.28 for the savings and for readable text;
mixed-version pairs still communicate, with the old phone
seeing codes on longer messages.

MEASURED SAVINGS (harness-printed, UTF-8 bytes, marker
charged): 100 -> 78, 89 -> 63, 143 -> 90 on sample
messages; a 879-byte all-common message codes to 549 bytes
(37% smaller). Short and mostly-unknown messages stay
plain — the savings rule guarantees the wire never grows.

ALSO IN THIS VERSION: `ChatThreadView`'s open/unkeyed
branch now unwraps the chat envelope like the keyed branch
does, so a word-coded message decodes on an unkeyed channel
exactly as on a keyed one. Side effect, and an improvement:
a plain envelope on an unkeyed channel now shows its text,
not its JSON.

FILES: new `WordDict.java`, `harness/WordDictHarness.java`;
edited `LowDataEnvelope.java`, `RelayIngest.java` (Decoded
gains `wordNote`, 7-arg constructor — SubscriptionHarness
updated to match, no behavior change), `BridgeRule.java`,
`MeshNet.java`, `BtWalkie.java` (Listener.onText gains the
`wordNote` parameter), `WalkiePage.java`,
`SubscriptionService.java`, `ChatThreadView.java`,
`build-apk.sh`, `run-launcher-harnesses.sh`.

VERIFICATION: WordDictHarness 157/157 (new) + all 23 prior
suites green (24 total; Relay 28/28 live). APK 20,718,974
bytes, versionCode 33, same cert, 27 permissions.

ON-DEVICE CHECKLIST (two phones): both on 1.28, coded
messages decode exactly on all three roads including
names/numbers/punctuation; a phone on 1.27 sees printable
coded text on longer messages and plain text on short
ones; the 1.27 -> 1.28 update arrives through Settings ->
Updates (this release is published through the updater —
see the site notes below). Nothing in the codec has run on
hardware: the harness proves the rules, not the Android
plumbing.

SITE PUBLISH (v1.27 updater rule): every release regenerates
`version.json` from the copied APK. v1.28 was published to
launcher.eyeknow.app (APK + version.json + card facts +
history line) and the live round trip verified (version.json
200 with exact contents; APK 200, byte-identical SHA-256).

## v1.27 (2026-11-16) — in-app updater, served from launcher.eyeknow.app

Adam (2026-11-16): "Can we push updates without installing a new apk?"
The straight answer: no — Android changes an app's code only
through a package install, and the install tap is the user's,
never skippable (the only exceptions are Play auto-update, a
managed device, a system app, or root — none apply here). What
can be built is the closest thing: the app checks for a new
version, downloads it, verifies it, and hands it to the system
installer. Adam said to build that.

CHICKEN-AND-EGG: v1.27 itself arrives the old way — Adam installs
it from the chat attachment, as usual. It is the first version
carrying the updater; every version after it can arrive through
the updater itself.

### The flow

One Settings row, "Check for updates" (new "Updates" group,
before About), carries the whole flow — no popups, no banners
anywhere else. The row always shows the installed version, and:

- On launch, `UpdateManager.onAppLaunch` (from
  `MainActivity.onCreate`) quietly fetches the manifest,
  throttled to once an hour (`shouldQuietCheck`), failures
  silent — no toasts on launch. The row reports the last
  check honestly when Settings is opened, then refreshes.
- The manifest is `https://launcher.eyeknow.app/version.json`,
  fetched cache-busted (`?t=<millis>`) so a stale cached copy
  can never hide an update. HTTPS only, always; redirects are
  followed manually (max 5), each hop re-checked to be HTTPS.
- An update is offered only when the manifest's versionCode is
  STRICTLY greater than installed: no downgrades, no
  same-version reinstalls. The row then names the new version
  and size; tapping downloads the APK — never silently, never
  in the background: 20 MB on mobile data downloads only on
  the user's tap. Progress shows on the row.
- Before any install is offered, the download must pass all
  five checks in `UpdateVerify.verifyDownload`, in order:
  SHA-256 equals the manifest's; size equals the manifest's;
  package is `app.control.launcher`; version code equals the
  manifest's; signing certificates are EXACTLY the running
  app's (same set, no more, no fewer — an APK signed with any
  other key is never offered). Any failure deletes the file
  and names what failed on the row; no install is offered.
- Install goes through the standard `ACTION_VIEW` intent (APK
  MIME, content URI through the v1.25 `ApkShareProvider`
  token pattern — the build has no AndroidX, so no
  FileProvider). The install tap is Android's and can never
  be skipped; no copy in the app promises otherwise. If the
  "install unknown apps" grant for Control is missing
  (`canRequestPackageInstalls`), the row says so honestly and
  opens the exact system setting
  (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package).
- The verified file lives in the app's private cache. It is
  deleted on any verification failure, and on launch once its
  version is the installed version (the update landed). It is
  deliberately NOT deleted the moment the install is offered:
  the installer reads the file through the provider after the
  intent fires, and deleting it first would break the install.
  A verified file for a newer version survives restarts, so a
  downloaded update can be installed later; a fresh manifest
  naming a still-newer version retires it.

### Seams and permissions

- `UpdateManifest` (pure Java): manifest parsing through
  `RelayJson` (org.json stubs throw off-device), strict field
  validation, cache-bust/HTTPS/resolution URL rules, the
  strictly-greater decision, the quiet-check throttle.
- `UpdateVerify` (pure Java): digest/certificate-set rules and
  the five-check download decision with first-failure reasons.
- `UpdateManager`: the I/O (fetch, download with progress,
  streaming hash, `PackageManager` certificate reads with an
  API 26/27 `GET_SIGNATURES` fallback) plus the pure row-line
  and tap-decision functions the harness proves.
- New permission (27 total): `REQUEST_INSTALL_PACKAGES`
  (normal-level; the grant itself is the user's tap in
  Settings). No other new permissions. `RECEIVER_NOT_EXPORTED`
  untouched (x6); zero `AccessibilityService` references.

### The site as the update source

`~/workspace/launcher-site/version.json` is the manifest the
app reads:
`{"versionCode":32,"versionName":"1.27","apk":"control-launcher.apk",
"sha256":"<full hex>","size":20706686}`. The site copy of the
APK was replaced (v1.5 → v1.27), the download card and band
updated, and one v1.27 history line added (CURRENT pill moved).
Deployed as dpl_BEHTe1H6SuxCktvfANysbeCKJ5DG (READY); verified
live: version.json 200 + application/json with the exact
contents above, the APK downloading byte-identical
(SHA-256 matches version.json and the local build), the page
showing 1.27. EVERY future release must regenerate
version.json from the copied APK — the app refuses anything
whose checksum or key does not match it. (The site's README
records this; the README itself is excluded from the deploy.)

Verified: `./build-apk.sh` BUILD-OK; versionCode 32 /
versionName 1.27; same signing certificate
(`f646377d…f372a1c52`); apksigner verifies. APK: 20,706,686
bytes, SHA-256
`e9b3d5f0cc33cb0c4a30b4faf4c0261b0db099a514c639a4d068c044cf519338`.
UpdaterHarness 143/143 (new) + all 22 prior suites green
(Share 145, AudioModem 71, NfcArm 40, Bridging 156, WalkieMode
70, WalkieLayout 30, Relay 28 live, AdbConnect 15,
AppActivities 36, AppListCodec 22, BridgeGate 44, History 78,
HoldWatch 64, LowData 124, Nat 190, RingMath 101, Setup 85,
Subscription 75, Terminal 29, TerminalScroll 32, Wallet 56,
Watchdog 58).

On-device checklist (Adam): fresh install of 1.27 → Settings →
Updates row shows "Version 1.27 — you're on the latest version"
after its check, and no update is offered (the manifest names
the installed version). The REAL update proof needs a FUTURE
version on the site: publish 1.28, open Settings, and the row
must offer the download → progress → verified → install (with
the unknown-apps setting step on first use). The download,
certificate check, install handoff, and grant flow have never
run on hardware — the harness proves the rules, not the
Android plumbing.

## v1.26.1 (2026-11-16) — Control itself appears in the share-an-app list

Adam (2026-11-16): "The launcher app isnt in the share list."
Root cause, verified from code: `AppListStore.scan()` deliberately
excludes the app's own package ("hide ourselves from the drawer"),
and `ShareAppActivity.loadAsync()` fed the picker from
`InstalledApps.all()` — that cached list. `SharePicker.shape()`
sorts the own package first, but it never received it, so Control
was absent from the share list even though v1.25 intended
"Control first". The sort was never the bug; the feed was.

Fix, in the share feed only: `loadAsync()` builds the self entry
explicitly from its own `ApplicationInfo` — the real app label
via `getApplicationLabel`, and the APK size through the same
readable-file rule as every other row — and prepends it through
a new pure-Java seam, `SharePicker.prependSelf()`, before
`shape()`. The self row goes through shape()'s usual rules: an
unreadable own APK (size <= 0) drops like any other row, never a
row that fails when tapped, and a duplicate own entry in the
feed collapses to the prepended one (first entry wins). The
drawer exclusion in `AppListStore.scan()` is untouched — the
drawer keeps hiding Control; only the share picker gains it.
The share-sheet and hotspot/QR paths are byte-identical in
behavior, and the self row shares exactly like any other row:
the same signed APK bytes through the same provider/token path.
No new permissions, no manifest change, no receiver change.

ShareHarness grows a SELF_ROW leg (15 checks): own package
present and first, real label + size, dropped when unreadable
(size 0 and negative), duplicates collapsing to one with the
prepended label/size winning, null feed yielding only the self
row, blank label falling back to the package, input list
unmodified. All 22 harnesses green (Share 145/145).

On-device check: open Settings → Share an app → Control is the
first row, with its true APK size, and shares like any other app.

## v1.26 (2026-11-16) — NFC-triggered audio sharing: type tones + mesh join

Adam's design (2026-11-16): an NFC tap puts the receiving phone
in listening mode; the sender then plays audio packets carrying
contact cards, messaging keys, walkie channel keys, and mesh
configurations; initial tones identify the packet type. His two
decisions: the frequency is "audible and reliable but close to
ultrasonic" — as high as reliability allows — and the mesh
packet contains instructions telling the receiver which mesh to
connect to and how. APK: 20,694,398 B, sha256
`469ba06cd01b22b44207c938fe2924ff54656ab06d157392ee31b28e0110528f`,
versionCode 30 / "1.26", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.25. NO new permissions (26,
unchanged: NFC and RECORD_AUDIO were already held). NOT uploaded
to the launcher site.

### The NFC trigger: HCE + reader mode, arm signal only

Android Beam is gone (removed in Android 10), so the tap is
built on card emulation plus reader mode, asymmetric on
purpose: reader mode disables card emulation on the device
running it, so the SENDER shows the play screen (its HCE
service presents) and the RECEIVER shows the listen screen
(reader mode armed). The tap carries only an arm signal — ten
bytes, `CTL-LISTEN` — and no key material or mesh data ever
crosses NFC; all data travels by audio afterwards.

- `ControlHceService` (HCE, `BIND_NFC_SERVICE`, category
  `other` — never a payment, so no default-app selection) answers
  a SELECT for the AID `F0` + `CONTROL` (`F0434F4E54524F4C`,
  proprietary range) with the arm payload + `90 00`, and
  anything else with `6A 82`. It is stateless and presents
  whenever the system routes a tap here; the tap means
  something only to a receiver whose listen screen is armed.
- The listen screen arms reader mode on resume
  (`FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK`, IsoDep
  transceive on the binder thread) and disarms on pause. No
  NFC hardware, or NFC off, means no reader mode — the screen
  still listens by sound alone and says nothing about a tap it
  cannot hear.
- `ListenArm` is the arm state machine (pure Java, harness-
  proven): a tap NEVER arms listening by itself — it only
  confirms a listen the user already armed by opening the
  screen. Opening the screen is the arm; the tap is a
  shortcut, never the only way in. A tap with no armed screen
  does nothing. UI copy states the honest requirements: screen
  on, phones back to back.
- There is NO background launch: a TECH_DISCOVERED intent
  filter would fire for any IsoDep tag (transit cards,
  passports), so the tap works only while the listen screen is
  open. NDEF Type-4 tag emulation is out of scope.
- The listen timeout is unchanged from v1.8: 30 seconds, then
  the screen says nothing was heard and offers Listen again.
  The microphone is released on success, timeout, pause, and
  destroy, as before.

### The high-band modem: 14–17.5 kHz

The modem moved from 16 kHz sampling / 1.25–2.75 kHz tones to
48 kHz sampling with tones at 14000/14500/15000/15500 Hz (low
group) and 16000/16500/17000/17500 Hz (high group) — a 500 Hz
grid, 32 ms symbols, same dual-tone 16-ary FSK structure, same
CRC-16 + preamble-estimated symbol length. The 16 kHz rate
could not represent the high band at all (Nyquist 8 kHz); 48
kHz is the native rate on most Android devices, and
AudioKeyAudio propagates the rate from the one constant, so
playback (MODE_STREAM, v1.9) and recording (MIC source) both
moved with it.

Why the 500 Hz grid and the Hann window: absolute frequency
error scales with tone frequency, so a 1% clock offset is a
170 Hz shift at 17 kHz — nearly the whole 200 Hz spacing of
the old voice band. The wide grid plus a Hann-weighted 8 ms
Goertzel window (neighbors near the window's first null, a
shifted tone inside the wide main lobe) is what holds the
impairment gates up here. The harness proves it: the full v1.8
gate set passes at the new band — noise to 0 dB SNR, 30/80 ms
echo, ±1% clock offset, 0.05x amplitude, and the combined
leg. Margin beyond the gates, measured: noise holds to −5 dB,
amplitude to 0.02x, clock offset fails past ±1% (real phone
crystals are ~0.01%, so the gate keeps 100x margin). Air time
grew by two symbols (the type tones): contact 5718 ms, invite
and chat 3542 ms, mesh 3926 ms.

BREAKING CHANGE, stated plainly: frames on the high band are
NOT decodable by v1.25 and older (which listened at
1.25–2.75 kHz), and vice versa. Both phones need v1.26 to
share by sound. The on-device speaker→mic proof at the new
band is Adam's test — the harness bounds the risk with
simulated impairments only, and cannot see whether a phone's
speaker and microphone reproduce 14–17.5 kHz cleanly, or
whether OEM preprocessing filters those frequencies (the MIC
source is already used, which avoids the voice-call processing
path; whether a given device still filters is an on-device
unknown).

### Type-prefix tones and the type registry

Every frame now starts with two type symbols after the sync,
carrying the packet's kind before the payload: [silence]
[preamble 8] [sync 2] [type 2] [length] [payload] [CRC] [silence].
The decoder announces the type (`onTypeHeard`) as soon as the
type symbols are settled behind a valid length read — the
listen screen names what is coming ("Hearing a channel
invite…") before the payload finishes — and on delivery the
type must equal the CRC-protected kind byte in the payload, or
the frame is refused exactly like a CRC failure. A flipped type
tone can therefore never misroute a key. Unknown types are
named honestly on the listen screen and ignored on delivery,
never misrouted. The registry (`AudioKeyPayload.isKnownKind`)
admits exactly:

| kind | name | payload |
| 1 | contact card | name + relay pubkey + DM key |
| 2 | channel invite | number + mode + label + key (private only) |
| 3 | chat key | name + channel key |
| 4 | mesh join | road + host + address + channel id + name + keyed |

The listener routes each decoded payload to the EXISTING import
path for its type (contact import, chat-key import, channel
join) via the same legacy JSON text the QR scan used to
deliver — no second import system was built.

### The mesh join packet (kind 4)

Layout: `[kind:1][road:1][hostLen:1][host ≤32][addrLen:1]
[addr ≤24][idLen:1][channelId ≤32][nameLen:1][channelName ≤32]
[keyed:1]` (~127 bytes max, far under the 200-byte cap). Road 1
is Wi-Fi Direct (addr = the host's device address); road 2 is
LAN (addr = the host's IPv4 address). The channel identity is
the slot identity (`Profile.slotIdentity`, wireId respected),
so a slot that arrived by invite and moved numbers still
matches; `keyed` says whether the channel needs a key the
receiver must already hold.

Sending ("Share mesh by sound" in the Mesh & LAN section) works
only while hosting a live mesh or LAN session. Wi-Fi Direct
needs the phone's own device address via
`requestDeviceInfo` (API 29+ — below that, an honest failure);
LAN uses the phone's site-local IPv4 address from interface
enumeration (no permission, never loopback). The play screen
states the PBC honesty note: Wi-Fi Direct still needs one
system tap — accepting the connection on the sender's phone.

Receiving ("Join by sound") decodes the packet on the armed
listen screen and acts, naming the mesh first in the toast and
the status line. The NFC tap + being on that screen IS the
consent; there is no extra confirm dialog per packet, and
nothing joins silently. The receiver matches the channel id
against its own 50 slots: no match means a plain refusal
("ask for the channel invite first") and no join. On a match
it retunes to that slot (which stops any local session), then
reuses the existing join paths: Wi-Fi Direct via
`connectToPeer` with a stub device (the CONNECTION_CHANGED
path creates the mesh session when the group forms, exactly as
the manual Join mesh flow), LAN via `newMeshSession("LAN")` +
`connectToHost`. A keyed-channel mismatch is refused at the
mesh door by the existing hello keycheck, with its honest
message. An unparseable address or an unknown road is refused
plainly, never joined.

### Listen-side proof

The v1.8 listen path (AudioRecord MIC) was never proven
on-device. This build proves it as far as the VM allows: the
AudioModem harness decodes recorded-style buffers in 64-,
320-, and 8192-sample chunks at the new band, with type tones
and all four payload kinds, under the full impairment set —
and the on-device checklist (in the delivery report) covers
the two-phone exchange end to end: tap → listen → play →
import/join, on both roads, including the keyed-channel
refusal and the no-matching-channel refusal.

### Files changed

- `AudioModem.java`: 48 kHz, 14–17.5 kHz tones, Hann-weighted
  Goertzel, type symbols + `onTypeHeard` + type/payload
  cross-check.
- `AudioKeyPayload.java`: kind 4 (mesh join), capped text
  helper, `isKnownKind` registry, mesh JSON shape, kind names.
- `AudioKeyAudio.java`: `onTypeHeard` plumbed through the
  listener callback (playback path untouched).
- `AudioKeyListenActivity.java`: EXPECT_MESH + `meshIntent`,
  NFC reader mode, `ListenArm`, type announcements.
- `AudioKeyPlayActivity.java`: `meshIntent` factory.
- `WalkiePage.java`: "Share mesh by sound" / "Join by sound"
  buttons, mesh packet sending, `handleHeardText` dispatch on
  the JSON `app` field, mesh join handling.
- `NfcArm.java`, `ListenArm.java`, `ControlHceService.java`,
  `res/xml/apduservice.xml`: new. `AndroidManifest.xml`: HCE
  service entry. `res/values/strings.xml`: service description.
- `build-apk.sh`: versionCode 30 / "1.26".
- `harness/AudioModemHarness.java`: 71 checks (50 prior + 21
  new: type tones, mesh payload, band facts). New
  `harness/NfcArmHarness.java`: 40 checks. All 22 harnesses
  green (see the version footer in the delivery report for
  counts).

Untouched: walkie/bridging, Chat page, bridge/watchdog/overlay,
subscriptions, wallet, terminal, share-an-app.

## v1.25 (2026-11-16) — share an app with a nearby phone

Adam's orders (2026-11-15): share the launcher (and the eyeKnow
native app) with a nearby device; he approved hotspot + QR after
being told a true captive portal needs root; then added the
share-sheet method as well, with the ORDER: share sheet PRIMARY,
hotspot + QR FALLBACK. The share covers ANY installed app — one
build, no separate share flow inside the eyeKnow app. APK:
20,686,137 B, sha256
`8cca3c67317ecd09041d4ae7059ab8ce47af09f050b35c3f2e3023220a2bd4f2`,
versionCode 29 / "1.25", same signing key (cert SHA-256
f646377d…a1c52) — installs over v1.24. NO new permissions (26,
unchanged). NOT uploaded to the launcher site.

### The share screen (ShareAppActivity, from Settings)

A "Share an app" row in Settings opens the picker: every
launchable app, Control first, then alphabetical by label
(SharePicker — pure Java). Each row shows the icon, label,
package, and APK size. The list reuses the cached app list
(AppListStore, which scans synchronously on first run, so the
picker is complete even before any background scan) with a
background refresh re-rendering on change. System apps with no
launchable activity never appear: the cache holds launchable
apps only, and sharing a system component's APK would help
nobody on the other phone. Apps whose APK file cannot be read
are dropped rather than shown as rows that fail when tapped.
Tapping an app shows the two methods, share sheet first.

### PRIMARY: the share sheet

The app's installed APK bytes (`ApplicationInfo.sourceDir` —
APK files are world-readable, no root) go out through
`ACTION_SEND` with MIME `application/vnd.android.package-
archive`, via a purpose-built provider, `ApkShareProvider`.
The build carries no AndroidX, so there is no FileProvider to
use — and the custom provider is narrower by design: it serves
exactly ONE file, the APK registered for the current share,
addressed by an unguessable 128-bit token, read-only. There is
no path mapping and no way to ask for any other file. The
provider is not exported; the receiving app reads only through
the URI grant, which rides both the intent flag and ClipData
(the combination the chooser reliably forwards). The copy
states the receiving phone allows the install once (Android's
own prompt) and that the file is the same signed build already
on the phone, so updates install cleanly.

### FALLBACK: hotspot + QR

`WifiManager.startLocalOnlyHotspot` starts a hotspot with no
internet; the SSID and password are system-generated, shown on
screen, and encoded in a QR code. A minimal HTTP server
(ShareHttpServer) binds to the hotspot interface's address
only — never a wildcard — on an ephemeral port, and serves
exactly two things: one download page and the selected APK.
Routing is ShareHttp's exact-match rule (GET/HEAD, `/` or
`/app.apk`, no percent-decoding), so every other path,
traversal attempt, or encoded variant is a 404: no directory
listing, no other files. The receiver — any Android phone, no
app needed, no data needed on either end — joins the hotspot,
scans the second QR (the download URL), and installs from its
browser.

Two QR codes, on purpose: one code holds one payload. The
Wi-Fi config joins the network but opens nothing; the URL
opens the browser but joins nothing. The screen shows both,
with the network name, password, and URL as text for anyone
who would rather type.

NO captive portal, stated plainly in the code and the copy:
the auto-popup portal page works by hijacking the client's
internet check, which needs DNS control over the hotspot that
Android does not give regular apps (root-only). The second QR
code is the honest replacement, and nothing claims the page
pops up on its own.

The hotspot and server stop when sharing ends: the Stop
button, picking a different app, backing out, or the screen
closing. Rotation does not end a share (the manifest holds
the configuration change) and a theme change does not rebuild
the screen mid-share. The hotspot password is shown on screen
by design — it is the user's own hotspot — and never logged.

Two honest limits, both on-device questions: (1) Android
exposes no API for the local-only hotspot's gateway address,
so the address is picked by a heuristic over the enumerated
interfaces (`ap`/`softap`, then `wlan`, then `p2p`, then any
up interface with a site-local IPv4 — ShareHotspotState,
harness-pinned). If the A16's hotspot interface does not
match, the receiver cannot reach the page and the screen says
so. (2) Whether the hotspot's password arrives quoted or bare
from the reservation is handled both ways (one outer pair of
quotes stripped), and the on-screen text lets the user verify
it by eye.

### Files

New: `SharePayload.java` (QR strings, URL, page HTML, sizes),
`ShareHttp.java` (routing), `SharePicker.java` (picker rules),
`ShareHotspotState.java` (lifecycle + address selection),
`ShareHttpServer.java`, `ApkShareProvider.java`,
`ShareAppActivity.java`, `harness/ShareHarness.java`. Edited:
`AndroidManifest.xml` (activity + provider, no permissions),
`SettingsActivity.java` (the row), `build-apk.sh` (version
bump), these notes. Untouched: walkie/bridging, Chat, bridge,
watchdog, overlay, subscriptions, wallet, terminal.

### Harnesses

**ShareHarness (new, 130/130)**: Wi-Fi payload shaping and
escaping, URL validation, page HTML + escaping, size
formatting, the full routing table (including traversal and
encoded variants), picker shaping, the hotspot state
machine's full transition table, address selection, quote
stripping. The server is not exercised over a loopback socket
in the harness: the sandbox garbles JVM IPv4 loopback traffic,
so a socket test would fail for sandbox reasons — the routing
it would test is ShareHttp, pinned directly instead. All 20
prior suites green at identical counts (Bridging 156/156,
WalkieMode 70/70, WalkieLayout 30/30, Relay 28/28 live,
AdbConnect 15/15, AppActivities 36/36, AppListCodec 22/22,
AudioModem 50/50, BridgeGate 44/44, History 78/78, HoldWatch
64/64, LowData 124/124, Nat 190/190, RingMath 101/101, Setup
85/85, Subscription 75/75, Terminal 29/29, TerminalScroll
32/32, Wallet 56/56, Watchdog 58/58).

### On-device checklist (A16)

1. Settings → Share an app: the picker lists every launchable
   app, Control first, with sizes; eyeKnow is listed.
2. Share-sheet send to a second phone (Bluetooth and Quick
   Share): the receiver gets the APK, allows the install once,
   and the app runs; sharing Control installs over the
   receiver's existing copy when present.
3. Hotspot + QR send to a phone with no launcher: the hotspot
   starts, both codes scan, the page loads in the browser, the
   APK downloads and installs.
4. Hotspot + server stop on close: after Stop, back-out, or
   closing the screen, the hotspot is gone (the other phone
   loses the network) and nothing keeps serving.
5. Rotation mid-share keeps the hotspot up; the screen's
   wording never promises a captive portal or internet.

## v1.24 (2026-11-16) — messages cross transports; Auto mode is the default

Adam's order (2026-11-15): "I want the messages to be available to
anyone regardless of the connection method. For example if a
device is connected through a mesh, it gets passed to all devices
and then gets published to a channel when a phone has data." Plus
a same-night addition: AUTO MODE IS ENABLED BY DEFAULT (below).
No new permission (26 total).

### Bridging: live only, no queue

A message received on any transport — mesh/LAN, Bluetooth, or the
internet relay — is forwarded live to all OTHER connected
transports. A phone with data republishes mesh/Bluetooth messages
to the relay channel immediately on receipt. There is NO
store-and-forward queue in this version: messages that arrive
while NO phone has data stay within the mesh, and only NEW
messages publish once a phone gains data. The walkie Menu copy
says exactly this, and no copy promises delivery to people who
were offline.

One switch in the walkie Menu, "Bridge messages across transports",
default ON, turns all forwarding off; with bridging off, behavior
is exactly v1.23. The tuned channel's messages AND subscribed
channels' messages both bridge: subscription arrivals are stored
by the service, and the walkie page's pending scan (on the
subscription broadcast and on page show) makes each stored relay
entry's one bridge decision — the local sessions live on the page,
so the service itself never forwards. Each entry gets exactly one
decision (`bridgeSeen`), forwarded or not, so a later scan never
republishes an old message when a road comes up; entries stored by
a pre-v1.24 build (no bridge id) are marked seen and never bridged,
so upgrading never republishes history.

Bridged arrivals flow through the SAME pipelines — RelayIngest /
store, read-aloud (QUIET absolute), played → History (unplayed
until played). Mode (Auto/Voice/Text) applies to what THIS phone
sends, never to bridged content. Bridging never triggers during an
active transmission from this phone (`recording || transcribing`),
each transport has a single-flight guard (a skipped forward is
never retried), failures on one transport never block the others,
and nothing logs message contents or keys.

### Provenance: the gateway vouches for delivery, not authorship

The gateway CANNOT sign as the sender — a relayed message is
signed/sent by the gateway, and the UI marks it "relayed by
\<gateway name\>". Provenance rides the PAYLOAD, not the client
message id: the relay's idempotency is per-sender and the id is
1–128 chars, so it can neither hold the fields nor dedup across
gateways. The fields are the bridge id (`bid`), the original
sender's name and relay pubkey (`orig`, `origPub`), the original
timestamp (`ots`, a string of millis — one representation for
both parsers), the gateway's name (`via`), and the traversed
roads (`trav`).

- Relay texts: the fields are extra keys in the LowDataEnvelope
  object. Old builds ignore the unknown keys and show the text
  as from the gateway.
- Relay bursts: one header line of JSON + newline + the raw
  ADPCM bytes verbatim. New builds strip the header before
  playback; old builds play a ~25ms click from the small header
  and then the real audio intact. Header parsing works on BYTES
  (the first 0x0A), never on a decoded string.
- Mesh/Bluetooth packets: the same keys in the packet JSON; the
  `from`/`name` field already carries the original sender's
  name. Old receivers ignore the unknown keys.

### One identity per message: the mid/bid design

Two gateways forwarding the same message must mint the SAME
bridge id, or receivers land it twice — and the originator's own
relay post must share the bid, or every voice burst doubles (own
voice streams live AND always posts to the relay). So the bid is
derived from the message's origin identity, identically by the
originator and by every gateway:

- Every mesh/Bluetooth packet carries a `mid` (UUID, minted per
  message/spurt). A direct local receipt's bid is `"m:" + mid`.
- The page mints ONE mid per voice spurt, passes it to the local
  spurt-starts, and the relay post uses client message id
  `"m:" + mid` — so the originator's post and any gatewayed copy
  share one bid and receivers dedup.
- A relay receipt's bid is the carried bid (relayed), else the
  client message id verbatim when it starts with `"m:"`, else
  `"r:" + clientMsgId`, else a fresh UUID.
- A packet with no mid (a pre-v1.24 sender) gets a fresh bid per
  receiver: duplicates across gateways are then possible, and
  accepted — the sender predates bridging.

Every received entry is stamped with its derived bid at ingest,
and the cross-road duplicate check applies to ALL arrivals. Own
entries are stamped with the same bid a gatewayed copy would
carry, so a phone's own message coming back through a gateway
dedups instead of landing twice.

### Loop prevention, and its honest limit

Two mechanisms: (1) the bid — a phone that already forwarded a
bid never forwards it again (the forwarded set is persisted in
the store, cap 500 FIFO, ids only, so a restart cannot
re-forward), and a thread entry carrying a bid suppresses the
same message arriving on another road; (2) the traversed set —
never forward back to a road the message already traversed.
Consequence: mesh→relay→mesh is blocked, so TWO SEPARATE MESH
GROUPS CANNOT BRIDGE VIA THE RELAY. That is a deliberate limit
of the traversed-set rule, not a bug; lifting it needs a
different loop rule and is future work.

Private channels: only members holding the channel key forward,
checked explicitly at every forward decision (the crypto already
enforces it in practice). A non-member gateway carries nothing.

### Voice bridging mechanics

Live mesh/Bluetooth bursts normally leave no thread record — but
a bridged burst gets one (with its blob, unplayed, never
auto-played), so tap-to-play works and the bid dedups the relay
copy. When nothing forwards, NO entry is created: exactly v1.23.
Spurt capture is capped at 60 seconds of audio (PCM 1,920,000
bytes / ADPCM 480,000 bytes); a longer spurt plays live but is
not bridged, rather than bridged truncated. Voice re-stream:
mesh = ADPCM decoded to PCM in 1280-byte chunks (the live path);
Bluetooth = ADPCM in 800-byte frames. Socket backpressure paces
the re-stream; no sleeps. Mesh and Bluetooth sessions are
mutually exclusive on one phone, as before.

### Auto mode is the default (v1.24 addition)

The default walkie mode choice is now Auto — for fresh installs
AND for upgrades where the user never explicitly picked
Voice/Text. The v1.23 one-time migration (which wrote Voice/Text
from the old low-data switch) is REMOVED: it must never write an
implicit choice. A stored mode value is respected as the user's
explicit choice; absence of a stored value means Auto. (v1.23
never reached a device, so no installed base carries migrated
values — but the stored-value case is handled correctly anyway.)
The mode UI shows Auto selected by default, a manual override
takes effect immediately, the face shows the Auto measurement
line by default, and the `lowdata_on` sync from Auto's decision
is unchanged. `WalkieMode.parseChoice` now falls back to AUTO
for null/empty/unknown values; `migrateChoice` and the
`KEY_WALKIE_MODE_MIGRATED` pref are gone. The WalkieMode harness
gains a defaults leg (fresh prefs → Auto; stored Voice/Text
respected; explicit switches stick): 70 checks.

### Files

New: `BridgeRule.java` (pure Java + org.json: the fan-out rule,
provenance shapes, bid derivation, entry stamping) and
`harness/BridgingHarness.java` (156 checks: envelope/burst/packet
round-trips, the fan-out matrix, bid derivation, two-phone
ping-pong and three-phone chain loop simulations, two-gateway
dedup, private member-only, switch-off, stamping, RelayIngest
decode of relayed payloads, forward-stamp accumulation).
Changed: `RelayIngest` (decode parses relay provenance, strips
burst headers), `MeshNet` / `BtWalkie` (mid in packets, provenance
in listener callbacks, `*As` forward sends), `WalkieStore`
(persisted forwarded-bid set), `WalkiePage` (bridge switch +
copy, spurt capture, fan-out, relay re-publish, pending scan,
relayed marking, spurt mid plumbing, bid stamping),
`SubscriptionService` (bid stamping + cross-road dedup on
background arrivals), `Prefs` / `WalkieMode` (Auto default, no
migration), `harness/WalkieModeHarness` (defaults leg),
`harness/SubscriptionHarness` (the 6-arg `Decoded` constructor),
`build-apk.sh` (versionCode 28 / "1.24"). Untouched: Chat page,
bridge/watchdog/overlay, subscriptions polling schedule,
tuner/face layout, low-data path, wallet, terminal.

### Verification

BridgingHarness 156/156 + all 19 prior suites green
(WalkieMode 70, WalkieLayout 30, Relay 28 live, AdbConnect 15,
AppActivities 36, AppListCodec 22, AudioModem 50, BridgeGate 44,
History 78, HoldWatch 64, LowData 124, Nat 190, RingMath 101,
Setup 85, Subscription 75, Terminal 29, TerminalScroll 32,
Wallet 56, Watchdog 58). On-device proof still needed: two-phone
bridging in all directions, relayed marking, private member-only,
switch off = v1.23, 60s+ spurts, transmission-during-receipt,
subscription-arrival bridging, Auto default on a fresh install
and stored-choice respect, both themes.

## v1.23 (2026-11-16) — the walkie looks like a real walkie; the mode follows the connection speed

Adam's orders (2026-11-15): "The walkie talkie looks way cluttered.
Make it look like a real walkie. Automatically set the mode by
testing the network speed." (The third order in that message —
messages available regardless of connection method, mesh bursts
bridged to the relay — is v1.24, a separate build, and is NOT in
this version.) No new permission (26 total).

### The radio face (WalkiePage, WalkieLayout, TunerView, SpeakerGrilleView)

The walkie page is now a handheld radio. A rounded-rectangle body
in the theme's surface tones holds, top to bottom: a channel
display window (row 1: the channel number + name + FRS frequency +
open/private; row 2: the road/signal line; row 3: the mode line),
the channel knob row (CH−, the tuner, CH+), a status strip of up
to three lines (talker / members / channel status — each line
only when it has something to say), a drawn speaker grille that
absorbs the remaining space, and a LARGE round PTT button as the
dominant control. Below the body, one access row: **Messages**,
**Menu**, **Scan**.

The v1.20 rule stands: the face never scrolls. `WalkieLayout`
owns the face budget — header 32dp, body padding 10/10, display
88, knob 56, status 18 per line (max 3), grille minimum 40, PTT
128, access row 36 — 450dp with one status line, 486dp with
three, against 568dp of usable height on a 360×640 screen with
gesture navigation (82dp of slack, absorbed by the grille). The
v1.20 corner case (the speech-model block clipping the roads row
on a 360×640 screen with 3-button navigation) is gone: the model
block moved off the face entirely.

The tuner IS the channel knob: the same drag-with-detents,
haptics, and glide, now drawn as a knurled knob cylinder
(rounded rect + ridges sliding with the drag offset, ticks and
numbers and the needle kept). Circular drag was rejected: the
knob is a wide bar, not a circle, and a ring gesture would be a
second gesture to learn. Tap the display window for direct tune
(the same dialog); CH± and Scan remain.

Everything else moved behind clean access points, with no copy
changed where it moved and nothing dropped:

- **Messages** opens the messages panel: the thread (scrolls
  internally within the panel, with the same bottom padding for
  the join-sound block), the composer row, and History in the
  composer row. The panel is a 480dp dialog that resizes for the
  keyboard (`SOFT_INPUT_ADJUST_RESIZE`); the join-sound block
  overlays the thread's bottom as before. The access row's
  Messages button and the display window both carry the
  unplayed count ("N new").
- **Menu** opens the radio menu (a scrolling dialog): the
  send-mode section (below), read-aloud, the subscription
  switch, the private-channel action row (Listen / Share /
  Invite / Private), and the roads row — each with its
  verbatim info dialog. The roads row opens the same dialogs,
  re-parented; nothing about the roads changed.

The panels are built once with the page and live in an offscreen
holder; opening one re-parents it into a dialog and closing
returns it to the holder, so the thread's adapter, the model
download state, and the join-sound block never rebuild. The
pager still claims horizontal swipes except over the tuner and
the PTT.

`SpeakerGrilleView` is a new pure view: 5 columns × 3 rows of
rounded slots, centered as a group, in `Ui.PANEL` on the body.
Both themes use the same structure; the display window is the
inset `Ui.PANEL` tone in both, and the PTT is the accent fill in
both.

### Automatic mode by speed test (WalkieMode, RelayCore, RelayClient, Prefs)

"Mode" is voice bursts vs low-data text. The old low-data switch
is now a three-way choice in the radio menu — **Auto / Voice /
Text** — and Auto is opt-in, default OFF: the stored choice
migrates exactly once from the old switch (on → manual Text,
off → manual Voice), so current behavior is unchanged until
Adam turns Auto on. The choice lives in `walkie_mode`
(`walkie_mode_migrated` guards the one-time migration); the old
`lowdata_on` key is kept in sync with the mode in force, so the
existing low-data path — `LowDataSpeaker`, `SpeakGate`, the
envelope, the model block, the transcription dialog — is fed by
one seam (`lowDataOn()` = effective mode is TEXT) and nothing
downstream changed.

The probe is a timed round-trip, not a throughput test: a
signed `GET /v1/channels` to the relay (the same signed,
timestamped request the relay loop already makes, returning no
message content), timed in milliseconds, bounded at 5s connect
/ 5s read. Any HTTP round trip counts — even a 401 — because
the question is whether the road answers, not whether the
request was authorized. No user data, no new endpoint, no new
request shape. `RelayCore.call` was refactored into `callRaw`
(returning HTTP status, parsed JSON, and elapsed ms) plus the
unchanged `call` wrapper, so the probe shares the exact signing
and timeout code; `RelayClient.probe()` runs it on the relay
pool and reports back on the main thread. The face reports
milliseconds, never kb/s — a round-trip time is not a speed,
and the UI must not claim it is.

The decision (`WalkieMode.decide`, pure Java, harness-proved)
has four precedences, in order:

1. A manual choice (Voice / Text) always wins.
2. A live local session (mesh, LAN, or Bluetooth) decides
   VOICE under Auto — a nearby link is fast by definition, and
   no probe is spent on it.
3. A probe result, with hysteresis: TEXT at 1200ms or slower,
   VOICE at 400ms or faster; between the two, the mode stays
   where it was, so a borderline connection does not flap.
   With no previous mode, the first probe decides (slow →
   TEXT, otherwise VOICE).
4. No probe result (none yet, or the probe failed) keeps the
   last decision; with no previous mode, VOICE.

Re-test triggers: on entering the walkie page, on road change
(a local session starting or ending), and once a minute while
idle on the page (60,000ms, `PROBE_INTERVAL_MS`). Never during
an active transmission (recording or transcribing), never
doubled (single-flight), never after the page is destroyed.
The probe costs one small signed request per minute at most —
well inside the relay's 600 req/min per-key limit, and it
shares the poller's budget, not a second one.

The face is honest about all of it. The mode line reads, for
example, "Auto · 142 ms · Voice", "Auto · local · Voice",
"Auto · no probe yet · Voice", "Auto · relay unreachable ·
Voice", "Voice", or "Text (model not downloaded)". The
road/signal line shows signal bars from the same round-trip
time (≥4 bars at ≤150ms, 3 at ≤400ms, 2 at ≤1200ms, 1 above,
4 bars for a local session, none when unmeasured) next to the
road: the live local session with its member count or peer
name, "Relay unreachable" after a failed probe, else
"Internet". A failed probe never changes the mode — only the
wording.

### Harnesses

- **WalkieModeHarness (new, 64/64)**: choice parse/store/
  migrate, the full decide() precedence table (manual >
  local > probe+hysteresis > previous, null-previous and
  boundary values at 400/1200ms), signal bars/text, and the
  exact face wording of `describe()` for every state.
- **WalkieLayoutHarness (rewritten for the face, 30/30)**:
  the face budget at 360×640 (gesture and 3-button) and A16
  sizes, the display-window rows, the knob/PTT/grille
  geometry, and the no-scroll invariant.
- All 18 prior suites green (RelayHarness 28/28 against the
  live relay, proving the `RelayCore` refactor).

## v1.22 (2026-11-16) — view and launch an app's activities

Adam's order (2026-11-15): "I also want a way to view and launch
activities of an app." Long-pressing an app in the Apps sheet now
opens a popup menu (the anchored-PopupMenu pattern from the v1.13
Connections redesign) with two entries: the favorite toggle the
long-press used to do directly, and **View activities**. Tap-to-
launch, the cached app list, and everything else are untouched. No
new permission (26 total).

### The activities screen (AppActivitiesActivity)
- **Queried on demand, never cached**: `getPackageInfo` with
  `GET_ACTIVITIES` runs on a background thread when the screen
  opens, because a manifest can change with an update. A query
  failure is an honest line on the screen ("That app is no longer
  installed" / the failure's message), never a crash; an app with
  no declared activities gets an honest empty state. The screen
  header carries the app's icon + name + package, a count line
  ("N activities · M openable"), and the one line that explains
  the rule: Android only lets an app open another app's exported
  screens.
- **Every row**: the activity's label, its full class name, and
  its exported state. The label is the activity's OWN label when
  it declares one — the platform lookup falls back to the
  application's label, which would print the app's name on every
  row of an app whose activities declare no labels; when the
  activity declares none (`labelRes == 0` and no
  `nonLocalizedLabel`), the row shows the class's short name
  instead. Rows sort alphabetically by label (case-insensitive,
  class name breaking ties), not in manifest order.
- **Tapping an exported activity** launches it with an explicit
  intent naming its component (`ComponentName` + NEW_TASK). An
  exported activity can still refuse — a permission requirement,
  a removed target — so a SecurityException shows a toast naming
  the refusal ("Android refused to open …" + the platform's
  reason, capped) instead of crashing; any other launch failure
  toasts honestly too.
- **Non-exported activities are shown but marked** ("Not openable
  from here"); tapping one restates the rule in a toast. The
  screen promises nothing more: launching a non-exported activity
  through the shell bridge is a governed-control decision for
  later, and no copy on this screen mentions it.
- **Activity aliases** appear as their own rows, launch by the
  alias component name, and name their target ("alias for …").

### Shared rules (ActivityList, pure Java)
The shaping — short-name extraction, the label fallback, the
alias flag + target short name, the sort, the count line — lives
in `ActivityList`, so the harness proves the exact code the
activity runs. Nameless entries produce no row; the input list is
never modified.

### Verification
- `AppActivitiesHarness` (new): 36 checks — short names (dotted,
  bare, inner-class `$`, trailing dot, null/empty), the label
  fallback (null/empty/blank vs. a real label), build (dropped
  entries, alias flags, input untouched), sort (case-insensitive,
  class-name tiebreak), summary (singular/plural, zero/all
  openable, empty/null).
- All 17 prior harnesses re-run on the new tree at identical
  counts (fresh compiles; Relay 28/28 against the live relay).
  RECEIVER_NOT_EXPORTED intact; zero AccessibilityService
  references in source, manifest, resources, and the dex.
- No on-device test — Adam's tests: long-press an app, open its
  activities, launch an exported one, check a non-exported one is
  marked and refuses with the toast, check an app with many
  activities reads alphabetically, check an alias row names its
  target.

## v1.21 (2026-11-16) — the terminal transcript stops flashing away

Adam's report (2026-11-15, on 1.18): "when I send a command to the
terminal, the text clears before I can read it" / "I just see text
flash." Two mechanisms fit the symptom, and this build kills both.
TerminalActivity only — no other screen, no new permission (26
total), no behavior change outside the terminal.

### Mechanism 1: recreation wiped the transcript
The transcript lived only in an in-memory StringBuilder; the
activity had no `configChanges` handling and no saved instance
state, so any recreation (rotation, any config change) rebuilt
the screen back to the two header lines and the output was gone.
Fix: `onSaveInstanceState` stores the transcript text (through
the same cap rule the live transcript obeys — see below) and
`onCreate` restores it instead of appending the headers. The
headers are appended only on a genuinely fresh open, so a
restored transcript never gains a second copy of them. The saved
form holds redacted text only — the bridge token is masked at
append time — so no secret is stored. The same path also covers
process death while the terminal sits backgrounded. The
theme-deferral is untouched (`recreateOnThemeChange` still
false); a theme change still applies on the next open.

### Mechanism 2: a layout change stole the scroll
The only bottom-scroll was a one-shot `post(fullScroll)` after
each render. A layout change after that post — the keyboard
showing or hiding with `adjustResize`, a rotation — could reset
the ScrollView's scroll position away from the new output, so
the output flashed and was gone. Fix: the activity now tracks
whether the user is pinned to the bottom (`TranscriptScroll
.atBottom`: the visible window's end reaches the content's end
within an 8dp slop, recomputed on every scroll change) and
re-pins on layout changes — but ONLY while pinned. Two
`OnLayoutChangeListener`s, because neither alone sees both
causes: the ScrollView's own bounds change on resize, the
child's on new output. `renderTranscript` captures the pin
BEFORE `setText` (replacing the text can itself move the scroll
offset, and the scroll listener would then report the transient
position): pinned → scroll to the new output after layout;
unpinned → restore the offset the user was reading at, so new
output neither yanks them to the bottom nor jumps them to the
top. A user who scrolled up to read earlier output is never
moved; returning to the bottom re-pins.

### Shared rules (TranscriptScroll, pure Java)
The cap/trim rule and the at-bottom test live in the new
`TranscriptScroll` class so the harness proves the exact code
the activity runs: the cap (120,000) and keep (100,000) values
moved there, and the activity's constants delegate to them.
`trim` is the same rule the live transcript always applied
(keep the last KEEP characters, `[earlier output trimmed]` at
the head) — now shared between the append path and the
saved-state path, and provably stable (trimming an
already-trimmed transcript is a no-op, since KEEP + marker fits
inside CAP). Echo lines, redaction, history, the Clear button,
the preset flow, and the 120KB cap behavior are byte-identical.

### Verification
- `TerminalScrollHarness` (new): 32 checks — the at-bottom
  boundaries (exact bottom, one pixel above, slop edge,
  short/empty content, negative slop, overscroll), the trim
  rule (under/at/over cap, tail-keeping, null/empty, keep 0,
  stability), and the shipping-constants precondition.
- All 16 prior harnesses re-run on the new tree at identical
  counts (fresh compiles; Relay 28/28 against the live relay).
  RECEIVER_NOT_EXPORTED intact; zero AccessibilityService
  references in source, manifest, resources, and the dex.
- No on-device test — the flash in hand is Adam's test: send a
  command and read the output; rotate the phone mid-output and
  confirm the transcript survives; scroll up, send a command,
  confirm the view is not yanked; long output still trims at
  the cap with the marker.

## v1.20 (2026-11-16) — the walkie page does not scroll

Adam's order (2026-11-15): "the walkie talkie page shouldn't scroll."
The page is now a FIXED vertical column — header, readout, tuner,
control rows, status lines, talk button, three switch rows, thread,
composer, roads row — and ONLY the message thread region scrolls,
internally. No control was dropped; the layout was rearranged and
resized to fixed proportions, with the thread taking whatever height
is left. Walkie page only — every other page, and every walkie
behavior (mesh/Bluetooth/relay, tuner, hold-to-talk, low-data,
read-aloud, subscriptions, History, bridge/watchdog/overlay, Chat),
is untouched.

### How the fixed layout works
- **The ScrollView is gone.** WalkiePage builds its children directly
  into itself (a vertical LinearLayout). `WalkieLayout` (pure Java)
  owns every fixed height in one place, and the layout code builds
  to those numbers: page padding 8/4, header 32, readout 54, tuner
  52, step row 32, action row 34, status margin 4 + 20dp per line
  (max 3), talk button 66 + 6 margin, three switch rows at 32 each,
  composer 40, roads row 34. Fixed total in the typical state
  (one status line): 482dp. The thread region (weight 1, floor
  44dp) takes the rest: 86dp on a 360x640 screen with the system
  bars, ~385dp on Adam's A16.
- **Compact buttons, page-local.** `compactButton()` (32dp, no
  vertical margins) replaces `Ui.smallButton` (40dp + 8dp margins)
  on the page's own rows only — the shared Ui factory is untouched,
  and the dialog-hosted road sections keep the roomier buttons.
- **Readout condensed to one row.** The big number is 28sp (was 64),
  frequency 15sp, label 14sp single-line ellipsized; the Open/Private
  tag rides in the label line ("Worksite · Private") instead of the
  old second mode line (the `modeLine` view is deleted; the mode is
  still named on every retune). The tuner hint line is dropped — the
  band, CH ±, and Scan are self-evident, and the roads explanation
  lives in the Internet dialog.
- **Status lines are bounded.** Status, talker, and members lines are
  each capped at one line (ellipsized), so the status block can never
  grow past 64dp; empty lines still collapse to zero height.
- **Switch rows carry their status inline.** Low-data, read-aloud,
  and background-listening are each one row — switch + one-line
  status — so switch states never grow the page. The subscription
  switch text is now the fixed "Listen in the background"; the
  channel number rides in the status line ("Channel 12 — …"). The
  three sections' full explanations survive VERBATIM in info dialogs
  behind each status line (tap the line, marked ⓘ), which also show
  the full current state — so a truncated blocker line is never a
  mystery. The read-aloud status now reflects the switch ("On —
  never speaks while Quiet is on." / "Off.").
- **History rides in the composer row** (History / text field / Send),
  still naming its count; the empty hint overlays the top of the
  thread region, still managed by refreshThreadHint.
- **The road sections moved into dialogs.** Mesh & LAN, Internet,
  and Bluetooth are one tap away on the roads row. Each section view
  is built ONCE into a hidden GONE holder and re-parented into its
  dialog on open, back to the holder on dismiss — same live views,
  same buttons, same listeners, full copy intact, never rebuilt.
- **The join-sound block overlays the thread region.** On private
  channels it sits at the bottom of the thread region (a FrameLayout)
  instead of below the page, costing zero layout height; the list
  pads its scrollable bottom by the block's measured height
  (clipToPadding off), so no message hides under it. It stays pager
  chrome: `ownsHorizontalGesture` returns false for touches starting
  on it, exactly as when it was pinned below the old scrolling
  column. The tuner anchor is now actually set (it was declared but
  never assigned; the tuner already protected itself via
  disallow-intercept, so this only makes the gesture check honest).

### What does not fit, honestly
One transient state can exceed a 360x640 screen WITH the 3-button
navigation bar (568dp usable): the speech-model download block
(~110dp, only while low-data mode is on and the model is missing).
All three status lines populated at once still fits (thread 46dp,
floor 44dp). In the download corner the roads row (the last child)
can clip until the download finishes or is cancelled; with gesture
navigation, or on any taller screen — including Adam's A16, where
every state fits with 200dp+ to spare — it cannot happen. The model
block stays inline (its progress must be visible while downloading);
the corner is pinned by the harness, not hidden.

### Verification
- `WalkieLayoutHarness` (new): 35 checks — the constants are sane
  (talk button ≥ 64dp, tuner ≥ 48dp, thread floor ≥ 40dp), the budget
  arithmetic (status-line growth, model-block addition, clamping),
  fit at 568/600/616/867dp usable in the realistic states, and the
  known model-download corner pinned at a 68dp shortfall on 568dp.
- All 15 prior harnesses re-run on the new tree at identical counts
  (fresh compiles; Relay 28/28 against the live relay).
  RECEIVER_NOT_EXPORTED intact; zero AccessibilityService references
  in source, manifest, resources, and the dex; no new permissions
  (26 total); ScrollView survives in WalkiePage only inside the two
  dialog builders. No on-device test — the fixed layout in hand,
  the thread's internal scroll, the dialogs, the overlaid join-sound
  block, and the small-screen fit on Adam's A16 are his tests.

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
