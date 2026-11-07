# APK Stream-Resolution SOP — making a pirate-aggregator Android app play the game *inside its own window* (2.6)

Reusable procedure for taking a standalone Android "stream hub" that **hands every game to the site's
own player page** (which means: full website, ad overlays, popups, and sometimes an external browser)
and turning it into an app that **resolves the actual HLS manifest itself, decrypts the feed with the
key the provider leaks, and plays it in its own `<video>` element** — picture *and* sound.

Written from a real run: **NBA Hub v2.6** (`com.nbahub.app`, Java, no Gradle, `aapt2` + `javac` +
`d8` + `apksigner`, WebView shell serving `127.0.0.1:8790`), against the crackstreams/methstreams/
foxiy family of NBA aggregators whose "Main 1 / Main 2" links wrap an **official NBA League Pass**
Akamai feed (`lpnba.akamaized.net/.../hls-wvpr/...`).

This is the fourth in the series — after `APK_Ad_Removal_SOP.md` (TVBAnywhere phone),
`APK_Ad_Removal_SOP_TV.md` (TV build), `APK_Ad_Removal_SOP_BVR.md` (split bundle → single APK).

**Read this one if the symptom is "it opens the website instead of playing the stream."**
Ad-removal SOPs fix the *symptoms* of ads. This one removes the *reason the page loads at all*: the
player is never opened, because the manifest is fetched directly.

---

## What was delivered, in order

1. **The game plays in the app** — the wrapper chain (`event page → Main N → wrapper → wrapper`) is
   walked server-side/bridge-side, one plain GET per hop, until a manifest name appears (Section 3).
2. **The decryption is the provider's own recipe** — the page leaks `masterinf = {"<kid>": "<key>"}`
   next to the manifest; the hub serves it back as an `org.w3.clearkey` license and **shaka-player**
   (bundled, 563 KB) plays the CENC feed. hls.js could not: it prefers Widevine and there is no
   license server (Section 5).
3. **A `&sessionId=` gate discovered and automated** — without that parameter the Akamai edge answers
   **HTTP 400** to the very URL it just handed out (Section 4).
4. **Sound** — the feed keeps audio in a *separate* playlist (`#EXT-X-MEDIA:TYPE=AUDIO`); the relay
   only carried video, so the picture was genuinely silent. A second relay plus a synthesised master
   fixes it, and the mute state that made the button unusable is gone (Section 6).
5. **No more site hand-off** — failure now shows an in-app card (retry / player page), and game
   failover hops the game's *other* links instead of laundering into the NBA TV channel list
   (Section 7).
6. **Verified live** — 1080p advancing in real time, `webkitAudioDecodedByteCount` climbing
   ~21 KB/s, `/relay/master.m3u8` + `/relay/a.m3u8` = 200 (Section 8).

---

## 1. Prerequisites & Toolkit

| Tool | Exact version used | Purpose |
|---|---|---|
| JDK | 21 (`javac`, `keytool`) | compile + sign |
| Android SDK build-tools | 36.0.0 (`aapt2`, `d8`, `zipalign`, `apksigner`) | no Gradle in this repo |
| Android platform | `platforms/android-35/android.jar` | `-bootclasspath` |
| platform-tools | `adb` | install + `logcat` + `forward` |
| shaka-player | **4.7.10** compiled (`shaka-player.compiled.js`) | the decrypt path; bundled as `nba/hub/shaka.js` |
| hls.js | already in repo | everything without DRM (channels, plain mirrors) |
| curl | Windows `curl.exe` | probing the providers without the app in the way |
| Emulator | API 34 `sdk_gphone64_x86_64` | install-and-test loop |
| CDP harness | `tools/devtools_eval.ps1` (this repo) | evaluate JS inside the app's WebView — no Playwright |

Setup notes:

- SDK tools are not on PATH. Per session:
  `$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0;$env:Path"`
- Windows PowerShell 5.1: no `&&`; chain with `;`. `powershell.exe -File` is what runs the build.
- Build with the repo script, never by hand:
  `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1`
  → prints `OK -> android\NBAHub.apk` (a `[exit code: 1]` can still accompany success: `javac` writes
  deprecation notes to **stderr**, and the tool wrapper treats stderr as failure. Check for the `OK` line).
- **`Get-ChildItem`/`Remove-Item` are not the point of this SOP; the point is `curl`.** Every provider
  claim in this document was first proven with curl outside the app.

---

## 2. Reconnaissance: what the aggregator actually serves

### 2.1 The three "links" are not streams

`crackstreams.mx/stream/<slug>` is a rotator with the whole player list in plain JS:

```js
const allStreams = [
  {"label":"Link-1 HD","type":"external","value":"https://yieldz.gs/crack-nba/<slug>/"},
  {"label":"Link-2 HD","type":"external","value":"https://taifood-blog.asia/embed/nba/2026-11-06/por-lal"},
  {"label":"Link-3 HD","type":"external","value":"https://gblog.asia/cs/UG9ydGxhbmQgVHJhaWwgQmxhemVycyB2cyBMb3MgQW5nZWxlcyBMYWtlcnM/"}
];
```

Decoding Link-3's base64 gives the matchup name, nothing more — a slug, not an ID, and the host
404s. **Two of three links were dead on arrival.** Conclusion: never trust the number of links a
page advertises; walk until a manifest URL appears.

### 2.2 Two different backend families — classify before coding

| Family | Chain | Backend | DRM |
|---|---|---|---|
| `crackstreams.mx` rotator | `yieldz.gs → fingerz.cfd/ziangel7/application1` | own CDN restream, `naz.elcojodan.cyou` / `aew.binazina.sbs`, ~3.1 Mbps | **none** |
| `crackstreams.gdn` / `foxiy.st` | `trendy48.site → topstreamer.site/iframe/nba/<team>` | **NBA League Pass** `lpnba.akamaized.net/live-pz/p/hls-wvpr/...` | clear-key pair leaked in page |

Prove which one you have with one header read:

```powershell
curl.exe -sS -k -o seg.bin -w "%{http_code}`n" -A "Mozilla/5.0 Chrome/131" "<segment-url>"
# first byte 0x47 (71) => plain MPEG-TS, no DRM
```

The League Pass family is the one that needs Section 5. The own-CDN family plays with plain hls.js
from day one.

### 2.3 Read the player's source, not the page's HTML

`topstreamer.site/iframe/nba/lakers` names its manifest in a plain script constant:

```js
var key = 'lakers';
var globalurl = 'https://lpnba.akamaized.net/live-pz/p/hls-wvpr/g0022600032por1001455lal/index.m3u8?...&State=GP&csid=nba.com_live_nba-tv_web...';
var masterinf = {"5db28d7cffa282ea84de11a9b520ed3b": "3a263465f84278d8b3fffad6aa7474f8"};
```

- `globalurl` = the manifest
- `masterinf` = **`{key-id hex: key hex}`** — the provider's own clear-key pair for that game
- `isVodManifest(globalurl)` + `drm.clearKeys = clearKeysFor(masterinf)` in their shaka config

That last line is the whole trick, and it is why Section 5 uses shaka: *we are re-implementing the
provider's own player, minus their page.*

---

## 3. The resolver: walking the chain

### 3.1 Rules that survived contact with the sites

| Rule | Why it matters |
|---|---|
| One plain `GET` per hop, max 6 hops, keep a visited set | the chains are 2–3 deep, necklaces of wrappers exist |
| Rank iframes; never take the first | **the first iframe on the event page is the chat widget** (`chat.21aims.shop`). Chasing it produced `hop 1 names no iframe and no stream` while the player sat right there |
| Referer = previous hop | the wrapper pages 403 without it |
| Treat "no manifest in page" as data, not failure | pre-game pages have chat but no player; the UI says so (7) |

### 3.2 Frame ranking (the fix that stopped the chat-widget loop)

```js
const RX_CHATFRAME = /chat|discord|tawk|intercom|facebook\.com\/plugins|youtube\.com\/embed\/live_chat|disqus|comment|zendesk|crisp|livechat/i;
function frameScore(host, src){
  let s = 0;
  if (RX_CHATFRAME.test(host) || RX_CHATFRAME.test(src)) return -100;   // never
  if (/\/main\//.test(src))   s += 6;
  if (/\/top\//.test(src))    s += 5;
  if (/\/iframe\//.test(src)) s += 5;
  if (/\/embed/.test(src))    s += 4;
  if (/\/player/.test(src))   s += 4;
  if (/\/live/.test(src))     s += 2;
  if (/stream|topstream|trend|embed/.test(host)) s += 3;
  return s;
}
```

Order of preference in `nextHopIn(text)`: **named** player frame
(`<iframe id="stream…|player…|videoFrame…">`) → **highest-scoring** iframe → **JS-built**
iframes (`.src = "https://…"` with the word `iframe` within ±260 chars).

### 3.3 Manifest extraction (in priority order)

```
globalurl = '<…>'            // League Pass clones
DIRECT_STREAM_URL = "<…>"    // crackstreams own-CDN (no extension, still an HLS playlist)
https?://…\.(m3u8|mpd)…      // anything hard-coded
```

### 3.4 Where the code lives

| Layer | File | Function |
|---|---|---|
| page (JS) | `nba/hub/index.html` | `linksFromEvent`, `harvestChain`, `nextHopIn`, `frameScore`, `directIn`, `keysFrom` |
| bridge (Java) | `MainActivity.java` | `Hub.fetchText(url, referer)` → plain `Http.get`, else render in the **visible-scrim engine** |
| server (Java) | `LocalHub.java` | `/api/harvest` (same walk, Java copy), `/api/keys`, `/api/clearkey` |

Two walks exist on purpose: the **page** walk is the one that runs (it can use the bridge per hop),
and the **hub** walk is the fallback for anything a future client calls over HTTP.

### 3.5 Cloudflare: why a JVM fetch is not a browser

`HttpURLConnection` to `trendy48.site` returned **403** while the same URL answered 200 to curl and
to the WebView. That is TLS/JA3 fingerprinting, not a rule.

Fix: `Hub.fetchText` tries the plain fetch first, and on failure renders that **single page** in an
invisible engine — full-size (`MATCH_PARENT`), under an **opaque black scrim** with a status line.
Rules:

- the engine's client answers every ad host with an empty body,
- `onCreateWindow` returns a throw-away view (popups die),
- **same-host navigation is allowed** (a Cloudflare challenge navigates itself once or twice),
  cross-host is blocked,
- the page hands back only `document.documentElement.outerHTML`.

> A 1×1 hidden WebView is not enough: wrappers check viewport/visibility before building a player.
> That single change turned "hit=null" into a working capture.

---

## 4. The `&sessionId=` gate (the 400 that looks like a dead stream)

With everything else correct, the Akamai host still answered:

```
GET https://lpnba.akamaized.net/live-pz/p/hls-wvpr/g0022600032por1001455lal/index.m3u8?...&State=GP...
→ HTTP 400, 0 bytes
```

Referer matrix (none / nba.com / self / player page) — **400 every time**. Reading the provider's own
player `recover()` path showed the answer:

```js
const baseUrl = globalurl.split('&sessionId=')[0];
await player.load(baseUrl + '&sessionId=' + guid());
```

Appending `&sessionId=<random guid>` → **HTTP 200**, and the playlist arrives:

```
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,KEYID=0x5DB28D7CFFA282EA84DE11A9B520ED3B,
  KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"   ← Widevine system id
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,KEYID=0x7C8DB25DA2FFEA8284DE11A9B520ED3B,
  KEYFORMAT="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"   ← PlayReady system id
```

`0x5DB28D7C…` **is** the `masterinf` key id from Section 2.3 — the pair is right, the gate was the
only thing missing.

Implementation: append when absent, both in the page (`harvestChain`) and in the hub
(`followChain`):

```js
if (!/sessionId=/i.test(streamUrl)) streamUrl += '&sessionId=' + guid();
```

---

## 5. DRM: why hls.js fails and shaka works

### 5.1 The failure, measured

With hls.js configured `emeEnabled + drmSystems: {'org.w3.clearkey': {licenseUrl: '/api/clearkey'}}`:

```
logcat: WVCdm-DrmFactory … createMediaKeys        ← Widevine system was selected
video:  videoWidth 1920, currentTime advancing, buffered 39 s
errors: keySystemNoKeys FATAL ×2, internalException FATAL
```

The playlist declares the key under **Widevine's** system id; hls.js asks Widevine for a license,
there is no license server (the key is a *leaked clear key*, not a licensed one), so it fails — while
the manifest metadata is convincing enough that the element reports 1920×1080 and a moving clock.

### 5.2 The fix: shaka + `drm.clearKeys`

Bundle the compiled player and use the provider's own config shape:

```powershell
curl.exe -sS -L -o nba\hub\shaka.js "https://cdn.jsdelivr.net/npm/shaka-player@4.7.10/dist/shaka-player.compiled.js"
```

```js
shaka.polyfill.installAll();
const p = new shaka.Player(v);
p.configure({
  drm: { clearKeys: keys },                      // {kidHex: keyHex} straight from masterinf
  streaming: { lowLatencyMode: false, bufferingGoal: 40, rebufferingGoal: 2 },
  manifest: { retryParameters: { timeout: 20000, maxAttempts: 10, backoffFactor: 1.4 } }
});
await p.load(url);
```

Server side, `/api/clearkey` answers the JWK challenge the CDM sends
(`{keys:[{kty:"oct", kid:<b64url>, k:<b64url>}]}`, hex → unpadded base64url), fed by `/api/keys`
(JSON `[{kid, key}]`) which the page POSTs the moment the harvest finds a pair.

### 5.3 Play through the relay, never the CDN directly

shaka reading `lpnba.akamaized.net` from `http://127.0.0.1:8790` is cross-origin. Point it at the
relay instead — same origin, no CORS, and the DVR ring comes along:

```
shaka  →  http://127.0.0.1:8790/relay/master.m3u8  →  /relay/live.m3u8 (+ /relay/a.m3u8)
```

---

## 6. Sound: two independent causes

### 6.1 Cause A — our own mute

The old path did `v.muted = true; v.play()` (a workaround for autoplay policy) and then the shaka
success handler called `hideUnmute()` — hiding the only control that could undo it. Result: a greyed
mute icon and no way back.

The WebView already runs `mediaPlaybackRequiresUserGesture(false)`, so there is **no policy to
appease**. Sound is now plain state with four entry points:

| Entry point | Code |
|---|---|
| start of playback | `attachShaka` success: `v.muted=false; v.volume=1; soundState()` |
| chip | `#unmute.onclick = soundOn` |
| the picture | `stage.addEventListener('click', …, true)` when muted |
| bar button | `#snd` toggles, label `sound on` / `muted` |

`tryPlay()` now tries **unmuted first** and only falls back to muted + chip if the promise rejects.

### 6.2 Cause B — the audio is a different playlist (the real silence)

Measured `webkitAudioDecodedByteCount` = **0** while video advanced: not a mute bug, no audio track
at all. The master declares

```
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",...,URI="…audio…m3u8"
#EXT-X-STREAM-INF:…,AUDIO="aud"
```

and the relay only served the **variant** playlist, which carries video only. (`Relay.resolve()` had
noticed it and written `"separate audio rendition - may play silent"` into stats — read that field
before debugging sound.)

Fix, three parts:

1. **`Relay`** captures the audio URI during `resolve()` (`audioUris`, `DEFAULT=YES` wins) and
   exposes `audioUrl()`; a `prefix` field makes its playlist emit `a/seg/N.ts` instead of `seg/N.ts`
   so two relays can share `/relay/`.
2. **`LocalHub.startRelay`** starts a **second relay** for that URL with `setPrefix("a/")`, and stops
   the previous one. `/api/play` answers `audio:true` + `master:<base>/master.m3u8`.
3. **`/relay/master.m3u8`** is synthesised per request: `#EXT-X-MEDIA …URI="a.m3u8"` plus a single
   `#EXT-X-STREAM-INF … AUDIO="aud"` pointing at `live.m3u8`. The page prefers `master` when
   `audio` is true.

Verified after the change, same live game:

```
aBytes: 0 → 395 752 → 680 681 → 935 061   (12 s later, ≈21 KB/s AAC)
relay audio note: "separate audio rendition (mp4a.40.5-134826-48000-2) - relayed"
/relay/master.m3u8 = 200   /relay/a.m3u8 = 200
```

---

## 7. Failure behaviour: the part users judge

### 7.1 The laundering bug

`mirrorFail()` always iterated `channels[]` — the NBA TV list. So **every dead game feed hopped into
NBA TV**, which is exactly the reported symptom ("it goes down the list until it hits NBA TV and
plays that instead"). Fixed with one flag:

```js
if (gameNow){                       // a game is on screen: hop the game's own links
  for (const c of pol.cands) { … watchGame(gameNow, c.frame); return true; }
  toast('every game link failed …'); return false;
}
// otherwise: the channel mirror list, as before
```

`gameNow` is set by the game path and cleared the moment a channel is picked.

### 7.2 No more auto-navigation into the site

Old fallback: `Hub.openInternal(target)` → the full event page → "click the link inside the website"
→ ad overlays. Now the resolver either produces a manifest or shows a card **on our own page**:

```
could not get the direct feed
  retry                     ← runs the resolver again
  player page               ← explicit user action, in-app WebView, ads blocked, ⌂ returns
```

and when the game has not tipped off:

```
no player on the page yet
  this game has not tipped off (8:00 PM), and the wrapper pages only put a player in place at game time.
```

Pre-game detection is `g.state !== 'in'` from the schedule join — the wrapper pages genuinely have no
player iframe before tip-off, and that is not an error worth an ad-wall.

---

## 8. Verification (what "done" looks like)

| Check | Command / probe | Passing output |
|---|---|---|
| build | `powershell -File android\build.ps1` | `OK -> android\NBAHub.apk`, ≈381 KB |
| install | `adb install -r android\NBAHub.apk` | `Success` |
| hub alive | `curl 127.0.0.1:18790/api/ping` (after `adb forward tcp:18790 tcp:8790`) | `{"ok":true,"version":"2.6",…}` |
| manifest reachable | `curl 127.0.0.1:18790/relay/master.m3u8` | 200 |
| audio relay | `curl 127.0.0.1:18790/relay/a.m3u8` | 200 |
| relay stats | `curl 127.0.0.1:18790/api/relaystats` | `ready:true`, `variant 1920x1080`, `audio:"…relayed"` |
| picture | CDP: `v.videoWidth`, `v.currentTime` twice 20 s apart | `1920`, and `t` advances by ≈20 |
| sound | CDP: `v.webkitAudioDecodedByteCount` twice | grows ≈21 KB/s |
| mute state | CDP: click `#snd` twice | `false→true→false`, label `sound on`/`muted` |

CDP probe (no Playwright; the app enables WebView debugging):

```powershell
adb shell "ps -A | grep nbahub"                     # take the pid
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
powershell -File tools\devtools_eval.ps1 -ExpressionFile tools\state.js
```

> Expect `screencap` to return a **black** frame while a DRM feed plays: EME-protected video is not
> captured by SurfaceFlinger. Judge playback by `videoWidth` + advancing `currentTime` +
> growing `webkitAudioDecodedByteCount`, never by a screenshot.

---

## 9. Gotchas that cost the most time

1. **The first iframe is the chat.** Ranking frames (3.2) fixed a resolver that "worked" and returned
   nothing.
2. **`&sessionId=` is mandatory** on the League Pass family; without it the CDN 400s its own URL and
   it looks like a dead stream (4).
3. **hls.js picks Widevine** when the playlist names it, then fails with `keySystemNoKeys`; the feed
   is *not* unplayable — it is the wrong player (5).
4. **Audio can be a separate rendition**; `webkitAudioDecodedByteCount == 0` means "no track", not
   "muted" (6.2).
5. **A 1×1 WebView reads as hidden** to wrappers; render full-size behind a black scrim (3.5).
6. **`mirrorFail` is not a general queue.** It is the *channel* queue; a game needs its own links (7.1).
7. **`javac` writes notes to stderr** and the wrapper reports exit 1 even on success — look for `OK ->`.
8. **Base64 in a page is not an ID.** `gblog.asia/cs/UG9ydGxhbmQ…` decodes to the matchup *name* (2.1).

---

## 10. File map (2.6)

| File | Change |
|---|---|
| `nba/hub/index.html` | `linksFromEvent`, `harvestChain`, `frameScore`/`nextHopIn`, `sessionId` append, `/api/keys` POST, shaka path, `soundOn`/`soundOff`/`#snd`, `tryPlay` unmuted-first, `showResolveFailed`, `gameNow` failover, relay `master` preference |
| `nba/hub/shaka.js` | **new** — shaka-player 4.7.10 compiled |
| `android/src/…/MainActivity.java` | `Hub.fetchText` (plain → scrimmed engine), `sniffStreamAsync` on a full-size scrimmed view, ad-host guard in `knowntHosts`, popup/engine guards |
| `android/src/…/LocalHub.java` | `/api/harvest`, `/api/keys`, `/api/clearkey`, `/relay/master.m3u8`, `/relay/a*`, audio relay start/stop, `r=` referer override, `VERSION 2.6` |
| `android/src/…/Relay.java` | `audioUrl()` capture, `setPrefix()`, prefixed `#EXT-X-MAP`/segment URIs |
| `android/build.ps1`, `build.sh` | package `shaka.js` into assets; version 2.6 |
| `android/AndroidManifest.xml` | `versionCode 6`, `versionName 2.6` |
| `tools/devtools_eval.ps1` | CDP evaluate with `-ExpressionFile` |

---

## 11. Reusable checklist (next app, same class)

1. `curl` the event page; dump the whole `allStreams` / `data-e` / `globalurl` block. Classify the
   backend (own CDN vs League Pass) with one segment header byte.
2. Build the chain walker with **frame ranking** and a visited set; log every hop.
3. Try the manifest with and without `&sessionId=<guid>`; watch for 400.
4. Read the player's JS for `masterinf` / `clearKeys`; if present, plan on **shaka**, not hls.js.
5. Put the relay in front (same-origin, DVR) and serve a **master that names the audio rendition**.
6. Measure `webkitAudioDecodedByteCount` before claiming sound.
7. Make failure a card, and give games their own failover queue.
8. Verify with numbers (Section 8), not with screenshots.
