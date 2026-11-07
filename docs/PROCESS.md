# NBA Hub — the full process, measured

This is the handover document: what the app is, what was broken, what fixed it, how each claim
was verified, and the traps that cost the most time. Everything here was run on this machine
(`haoattorney\hiway`, Windows 11, Chrome 156, Android 15 Fold 7 emulator) between
**2026-11-03 11:34** and **2026-11-05 02:50** local time. Where a number appears, it came from a
command in §7, not from eyeballing a screen.

Read this before touching `nba/hub/index.html`, `nba/relay.py` or `android/src/…/MainActivity.java`.
`SOP_NBA_Stream_Hub.md` remains the lab notebook (§13.1–13.21); this file is the map.

---

## 1. What the thing actually is

```
PC (optional but fastest)                     Phone (standalone since v2.0)
┌───────────────────────────┐                 ┌──────────────────────────┐
│ NBAHub.exe  (launcher)    │                 │ NBAHub.apk               │
│   └─ pythonw hub.py       │                 │   └─ LocalHub.java        │  same endpoints,
│        ├─ /            page│                │        ├─ /  page          │  same page,
│        ├─ /api/…  JSON    │  LAN, 8790      │        ├─ /api/…           │  served from
│        └─ relay.py        │  relays 8900+   │        └─ Relay.java      │  127.0.0.1:8790
└───────────────────────────┘                 └──────────────────────────┘
                     └──────────── the page (nba/hub/index.html) ───────────┘
```

Three things play through it:

| Kind | Source | Who plays it |
|---|---|---|
| **NBA TV channels** (`feeds.txt`, 4 mirrors) | IPTV re-streams (`23.237.104.106`, Akamai FAST, Amagi, backup) | our `<video>` through `relay.py` / `Relay.java` |
| **Games, plain mirrors** | the mirror page's own player (`tims-001.sytes.net/…/nba-<id>.m3u8`, clear HLS) | our `<video>` through the relay (§4) |
| **Games, League Pass** | `lpnba.akamaized.net/…/hls-wvpr/…` — Widevine | the site's player page, in an iframe (PC) or in the WebView (phone); §5 |

The page is one HTML file with no build step. `hls.min.js` is vendored next to it (415,253 B).

---

## 2. The session, in order

Each line: the symptom the user reported → what was actually wrong → the fix → the number.

### 2.1 "this android player is not working at all, the nba stream does not play"

1. **URL handed to the phone was `http://127.0.0.1:<port>/live.m3u8`.** `127.0.0.1` resolves on
   the *client*, so the phone asked itself. `relay.py` also bound `127.0.0.1` only.
   → `/api/play` builds the URL from the request `Host`; relay gets `--host 0.0.0.0` when the
   hub does.
2. **The page had no phone layout** (fixed 352 px rail + 16:9 stage).
   → `@media (max-width:900px)`, in-app fullscreen, working Back, latency readout.
3. **`/api/resolve` returned `frame:"about:srcdoc"`** — a document that only exists inside its
   parent, so the frame was unplayable; and the probe accepted a 480×480 ad as "playing".
   → `playable_target()` rejects non-http frames and falls back to the feed page;
   `probe_play.py` reports the nearest http(s) ancestor and ranks by live/readyState/area.
4. **Games opened a black rectangle on the phone.** They are Widevine and a WebView on
   `http://192.168.x.x` has no EME (measured `"no EME"`).
   → hand the URL to a browser (later superseded by the standalone build, §2.6).

### 2.2 "when I fullscreen, it is stretching the picture"

Two causes, both real:

- the button fullscreened the **video element** (`v.requestFullscreen()`, `v.webkitEnterFullscreen()`);
  the native path ignores `object-fit`, so any non-16:9 screen stretches. → fullscreen the
  **container** (`#stage`) and let CSS decide.
- the mobile layout's own `aspect-ratio:16/9` + `max-height:46vh` followed us into fullscreen.
  → `#stage:fullscreen{width:100vw;height:100vh;max-height:none;aspect-ratio:auto;display:flex}`.

Measured: **1680×945** picture on a 16:10 1680×1050 screen (bars 53/52, aspect 1.778 = 16:9),
**1080×609** on a 1080×2400 phone (expected 607.5).

Two measurement traps found here: the native controls cover the bottom of the frame (set
`v.controls=false` while measuring), and a `canvas.captureStream()` video is *not* an aspect
reference in Chromium — use a real file (`docs/white1080.mp4`).

### 2.3 "I really hate the lag"

Three separate causes, none of them "the network":

| Cause | Evidence | Fix | After |
|---|---|---|---|
| `/api/play` waited for `/health == 200`, i.e. three buffered segments | 6–10 s spinner before anything appeared | return as soon as the relay *listens* (200 **or** 503); the player retries the manifest itself (`manifestLoadingMaxRetry: 10`, 700 ms) | **0.4–1.2 s** to first frame on PC, 2.9 s on the emulator |
| the relay's pump slept up to 3 s, so a poll picked up **two** segments at once → the playlist edge jumped 12 s → the player was suddenly further behind | latency sawtooth 14 → 22.7 → 25 → 33.7 → 14 | poll at `target/3` (≤2 s), fetch newest-first, `ready` on the edge segment | steady **14.1 s** |
| hls.js defaults | — | `enableWorker`, `liveSyncDurationCount` 2 (1.25 in low delay), `maxLiveSyncPlaybackRate` 1.35, `liveMaxLatencyDurationCount` target+1.5 | 14.6 s avg / **8.2 s** low delay, **0 stalls** in 60 s |

Plus a visible `N s behind live` pill and a hard ceiling that snaps once past `target*6+4`.

### 2.4 "why does it keep using Edge?"

Because the Windows default browser is Edge (`UserChoice = MSEdgeHTM`) and two of the three code
paths asked for "the default browser":

| Path | Before | Now |
|---|---|---|
| `NBAHub.exe` window | Chrome, but unlogged | logged `browser=chrome exe=…`; order Chrome → Chromium → Firefox → Brave |
| `hub.py /api/open` | `webbrowser.open()` → Edge | same picker as the launcher |
| `games.ps1` | `Start-Process $target` → Edge | Chrome/Firefox first |
| Android `Hub.openExternal` | implicit `ACTION_VIEW` → system default | `setPackage("com.android.chrome")` → Firefox → chooser |

Edge is now **opt-in only** (`--browser edge` or `browser.txt` containing `edge`). The Android
half needed a `<queries>` block in the manifest: Android 11+ hides other packages, so
`getLaunchIntentForPackage("com.android.chrome")` returned **null** silently and the code fell
through to the system default.

### 2.5 "is the exe standalone?"

No. `NBAHub.exe` is 23–25 KB of launcher; the app is the folder + a Python + a browser.
Full dependency list (measured, not assumed): `hub.py`/`index.html`/`hls.min.js`/probes ~0.5 MB,
`relay.py` + `feeds.txt` ~20 KB, Python 3.13 23 MB, `requests`+`urllib3` 3 MB, a browser,
**.NET Framework 4.x** (in Windows), and Playwright (~200 MB) only for game probing.

Portable build: Python 3.13.7 embeddable in `<root>\python`, `import site` enabled in
`python313._pth`, `pip install --target … requests urllib3 certifi idna charset_normalizer`,
launcher looks for `..\..\python\pythonw.exe` **before** PATH. Verified from a clean extract:

```
using the bundled interpreter
pythonw=…\python\pythonw.exe
/api/play → 5 segments, 35 s buffered, upstream 51.7 Mbps
```

Sizes: **11.8 MB zip**, 23.7 MB extracted, 181–189 KB APK.

### 2.6 The standalone Android build (merged from branch `ccr-12b0ca1f-xibrej`)

Three commits (`ae4d185`, `a2641af`, `7f4204e`) put the hub and the relay **inside the APK**
(`LocalHub.java` 26 KB, `Relay.java` 25 KB, `Games.java` 15 KB, `Http.java` 4 KB) and serve the
page on `http://127.0.0.1:8790/`. Merged into `main` as **8e68156**; one conflict
(`index.html`) resolved by keeping their page and re-adding my harvest step guarded by
`!STANDALONE && !IS_MOBILE`.

Why it matters more than it looks: **localhost is a secure context**, so
`isSecureContext=true` and `navigator.requestMediaKeySystemAccess` exists, i.e. Widevine is
available *inside the WebView*. Over `http://192.168.x.x` it is not. Measured on the emulator
after a clean install:

```
versionCode 4 / versionName 2.0
url      http://127.0.0.1:8790/
secure   true          eme true
hubKeys  [hubReady, openExternal, openInternal, retry, standalone, version]
```

### 2.7 "I want click → video inside the same app, PC and Android"

- **PC**: the game opens in the stage; when a plain manifest exists it plays in our own
  `<video>`, otherwise the site's player loads inside our window (`iframeMode`).
- **Android**: the confirm dialog ("No feed answered the probe…") was removed — before tip-off
  every mirror answers "not yet", so it was only ever an extra click. The tap now navigates
  straight to the site's player page **inside the app**.
- **Autoplay** (§6.4): the tap that opens a page does not travel into the new document, so
  nothing started. Now the duplicated-in-both-runtimes "kick" script mutes, clicks the overlay,
  calls `play()` and retries ~20× over 18 s; it sets `data-nbaautoplay="playing"` when the
  picture actually moves and shows `muted - tap the picture for sound`.

### 2.8 Other fixes in the same pass

- **Fullscreen on the phone is landscape**: page calls `Hub.setFullscreen(on)`;
  `MainActivity` answers `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` + immersive on enter and
  `UNSPECIFIED` + a new `leaveFullscreen()` on exit. Measured: stage 935×842,
  `screen.orientation=landscape-primary`, video 1920×1080, exit → list back (6 rows).
- **Schedules beyond +3 days** (§7 `later_days_check.py`): ESPN window widened to −3…+10 and any
  other date fetched on demand (`day_cache`, 10 min, capped); `/api/dates` also returns the
  season span and `streams:{date:n}`. An empty night (Nov 26 = Thanksgiving, ESPN really returns
  0 events) now says so and links `next games: 2026-11-27 (11)` via `/api/next`.
- **Mirror failover** (§6.1): a channel that stalls or dies hops to the next untried mirror.
- **Disk DVR** (§6.2): minutes that leave the RAM ring go to `<nba>\dvr\<port>\`, capped and
  wiped when the relay stops.

---

## 3. The repository, file by file

```
nba/                  hub.py, relay.py, harvest.py, feeds.txt, CLI .bat/.ps1
nba/hub/              index.html, hls.min.js, probe_play.py, find_stream.py,
                      harvest.py, NBAHub.exe, NBAHUB.bat, FIREWALL.bat
pc/                   NBAPC.cs (launcher), build-pc.ps1, app.ico
android/              AndroidManifest.xml, build.ps1, build.sh, src/com/nbahub/app/*.java
tools/                deploy.ps1 (repo → live install, restart, verify)
docs/                 harness (§7) + screenshots + player_srcdoc.html
releases/             NBAHub.apk (181–189 KB, v2.0 / vc4); portable zip is a release asset
SOP_NBA_Stream_Hub.md lab notebook, §13.1–13.21
```

Versions: `HUB_VERSION = "1.2"` (hub), APK `versionName 2.0 / versionCode 4`.
Git: `main` is the only branch that matters; `ccr-12b0ca1f-xibrej` is fully contained in it.
Releases: `v2.0` (current binaries), `v1.3` (kept, APK clobbered to the current build).

---

## 4. How the game stream was actually obtained ("the crack")

Chain, as observed on live pages (methstreams → foxiy.st → an embed):

```
event page  https://methstreams.st/event/m-<teams>-<date>
   ↓  (the page's own player is an iframe with srcdoc, i.e. about:srcdoc)
player page https://foxiy.st/event/m-<teams>-<date>/main/1
   ↓  (an ad-wrapped embed; first <video> is a 14–15 s portrait ad bumper on subduepaler.cyou)
embed       https://ehgembxe.cfd/trefoxy/pl/<JWE>
   ↓  XHR (rotating token + epoch in the path)
media       https://tims-001.sytes.net/main/secure/<sha>/<epoch>/nba-<id>.m3u8
```

`nba/hub/harvest.py` automates this in a real Chrome:

1. load the page, follow `a[href*="/main/"]` if we are not already there;
2. record every request whose URL matches `.m3u8|.mpd|/manifest|/hls/` (and abort ad/tracker
   hosts — `subduepaler.cyou`, doubleclick, googlesyndication, histats, dtscout, shareintent, …);
3. click every `video`, play button and overlay, repeatedly, until the player asks for its playlist;
4. keep the newest manifest and fetch it: plain HLS → `ok:true, drm:false`; `hls-wvpr`/`SAMPLE-AES`
   /`com.widevine` → `drm:true`;
5. classify with `describe()`: `league_pass`, `game_id` (`g0022600180cle1000468okc`), `widevine`,
   `sample_aes`, `key_uri`, `license`.

The manifest needs **no Referer, no cookie, no login** (verified: `200`, 1,267 bytes). Our relay
then plays it, so the game appears in our own player — measured 1920×1080, readyState 4, 16 Mbps
pull, 11.7 s behind live, `iframeMode=false`.

What this is *not*: it is not DRM decryption. The mirror page does the entitlement work; we read
the media URL it ends up using, which is public. That is enough for clear re-streams and not
enough for League Pass (§5).

---

## 5. League Pass: what is public and what is not

`/api/lp?u=<game page>` returns the truth instead of a vague failure:

```json
ok=true  game=g0022600184dal1000445sas  league_pass=true
protection=METHOD=SAMPLE-AES-CTR       playable=false
renditions: 27   best 1920x1080 @ 9.86 Mbps (avc1.64002A)
```

Facts, measured on Dallas at San Antonio:

- master `lpnba.akamaized.net/live-pz/p/hls-wvpr/<game-id>/index.m3u8` returns 25,346 bytes,
  27 renditions from 400×224 to 1920×1080 at ~9.86 Mbps;
- every rendition carries `#EXT-X-KEY:METHOD=SAMPLE-AES-CTR` with **two** descriptors: a
  **Widevine PSSH** (`…edef8ba9-79d6-4ace-a3c8-27dcd51d21ed…`) and a **PlayReady WRM header**
  (`<WRMHEADER xmlns="http://schemas.microsoft.com/DRM/2007/03/PlayReadyHeader">`);
- a 1080p fragment downloads fine: `200`, **702,916 bytes in 76 ms**, `video/mp4` (`moof`+`mdat`);
- it does not decode: `ffprobe` → "invalid data found", because the payload is keyed.

So the wall is **keys**, not bytes. To play League Pass in our own player:

1. an **entitlement** — a logged-in League Pass session whose licence server will answer;
2. a **CDM** — a Widevine L3 device blob (`device.wvd`);
3. wiring: read the PSSH → POST a signed challenge to the licence URL → `pywidevine` returns
   content keys → decrypt each fragment (`cbcs`/`cenc`, or SAMPLE-AES for CBC) → hand cleartext
   HLS to the relay → the same `/api/play` path serves it, bar pill says `relay - <game>`.

Without 1 and 2 there is nothing to decrypt with, which is why `playable=false` is data, not a bug.
The mirror's own page keeps working because its browser has a CDM — that is the only reason
"the good picture" exists at all tonight.

Honest comparison: the `iqiyi` repo's trick (host swap to an edge that does not enforce the
paywall → cleartext 720p HLS) has **no analogue here** — NBA's manifest itself is Widevine, so
there is no cheaper host to switch to.

---

## 6. Ads, streams, and what "no ads" can mean

Four separate sources of advertising; three of them we can do something about:

| Layer | Where it lives | What we do |
|---|---|---|
| **Mirror page pre-roll** (14–15 s portrait mp4 on `subduepaler.cyou`) | the site's own player | never reaches us on the harvest path; aborted in the harvest browser (§4 step 2) |
| **Page furniture** (nav, chat, banners) | the site's page | `/play?u=` proxy injects CSS that hides them and rewrites subresources through `/ext` |
| **HLS ad breaks with markers** (`#EXT-X-CUE-OUT/-IN`, `#EXT-X-SCTE35`, `#EXT-X-DATERANGE`) | inside the stream | relay drops the segments, marks the hole with `#EXT-X-DISCONTINUITY`, reports `ads_skipped` (§6.3) |
| **SSAI / DAI ad insertion** (NBA CDN's `ads=mr`) | mixed into the variant playlist with no markers | **cannot** be stripped reliably; the honest options are another mirror or accept it |

### 6.1 Channel mirror failover (page)

Signals, all local because the page owns that player:

| Signal | Threshold |
|---|---|
| no progress | `currentTime` unchanged **4 s**, not paused/seeking, `readyState < 3` |
| repeated buffering | **3** `waiting` events in 60 s |
| fatal feed error | hls.js `ERROR(fatal)` surviving the one automatic retry |
| dead mirror | `/api/play` returned `ok:false` |

On any of them: hop to the next untried channel in `feeds.txt`, at most once per **15 s**, toast
the move and show `(mirror N/M)`; when all are spent, say *pick one manually*. A manual click
clears the tried set. A **seek** is explicitly not a stall (`v.seeking` / `seekGraceUntil`), which
was a real bug: it rotated mirrors and wiped the DVR mid-seek.

Games use a different policy because they are cross-origin: `/api/health` probe every 10 s,
require *lagging* + 120 s age, then a confirm dialog before switching, or auto-switch after three
"frozen?" reports when there is no better candidate.

### 6.2 Disk DVR (relay)

RAM ring = 10 segments (~70 s). Anything older is written to `<nba>\dvr\<port>\seg-NNNNNN.ts`
instead of being dropped, and the published playlist spans RAM + disk. Caps:
`--dvr-minutes 15` (8 in low delay), `--dvr-max-mb 1200`, oldest first.
Measured: 189 s stored 3 minutes in, **7.4 min / 247 MB** after 8 minutes.
Cleanup is belt-and-braces because `terminate()` never runs the child's `finally`:
the relay wipes on a clean exit, `stop_relay()` wipes the folder, and `sweep_dvr()` runs at start.

Known limit, measured: seeking **back past the player's own buffer** makes hls.js raise a
seek-hole fatal → mirror failover restarts the relay → the DVR is wiped. `−30 s` landed at
−22.7 s, `−60 s` at −8.4 s with the stored minutes reset to 0. The rewind buttons were removed
rather than shipped half-working; the fix is to re-attach hls to the *same* relay on a seek
fatal, not to fail over.

### 6.3 Ad-break stripping (relay)

`parse_media()` marks each entry with an `ad` flag from cue markers; ad seqs are never
downloaded and never served; the playlist states the holes and adds `#EXT-X-DISCONTINUITY`;
`stats` exposes `ads_skipped`. Proof (synthetic origin, `docs/adstrip_check.py`):

```
segments served   100 101 102 105 106      (103/104 were the ad break)
discontinuity     true
ads_skipped       2
```

This work was **built, proved, then lost** — a later "sync the folder from git" overwrote
`relay.py` with the committed copy. Restored via `docs/reapply_adstrip.py` (idempotent) and
committed as `7d474d5`. Lesson: prove it *and* commit it in the same sitting (§8).

### 6.4 Autoplay

Same script in two places, because the runtime differs:

- **PC**: `/play?u=` injects `AUTOPLAY_JS` into the proxied page (same-origin, so it can also be
  inspected from the parent).
- **Android**: `MainActivity.kickPlayback(web)` runs it via `evaluateJavascript` on every
  `onPageFinished` that is not our own page.

Behaviour: mute → click poster/overlay/`button` → `play()` → retry ~20× over 18 s → on success set
`data-nbaautoplay="playing"`, show `muted - tap the picture for sound`, unmute on first tap.

Proved with a fixture (`docs/fixture_player.html` + `white1080.mp4`, browser autoplay policy set
to *requires user activation*): PC `data-nbaautoplay=playing`, overlay hidden, `paused=false`,
`muted=true`; phone `data-nbaautoplay=playing`, `readyState 4`, clock at 3 s.

Sound is muted on purpose: unmuted autoplay without a direct gesture on the player is blocked by
both Chrome and Android. Tap-to-unmute is the only honest behaviour.

---

## 7. The verification harness

Every claim in this document is reproducible. `docs/` holds the scripts; each prints JSON and a
PASS/FAIL where it matters.

| Script | Proves | Last reading |
|---|---|---|
| `lag_check.py [url] [secs] [low]` | click→first frame, seconds behind live, stalls, relay pull | 0.4–1.2 s, 14.6 s (8.2 low), 0 stalls |
| `phone_lag_check.py [samples]` | same over CDP on the phone's WebView | 2.9 s, 14.2 s steady, 0 stalls |
| `fs_hub_check.py [url] [chrome]` | fullscreen letterbox, pixel-measured | 945/1680 = 16:9, bars 53/52 |
| `phone_fs_check.py` | same on the phone, row-scan of the screenshot | 1080×609, aspect 1.773 |
| `pause_behaviour.py [secs]` | what pause does to both buffers | 70 s RAM ring stays; player seeks back to the edge |
| `dvr_check.py` | disk tier fills, wipes on stop | 7.4 min / 247 MB, folder gone after stop |
| `adstrip_check.py` | ad breaks dropped + discontinuity + counter | 100·101·102·105·106, ads_skipped 2 |
| `mirror_failover_check.py` | dead mirror → next mirror, no dialog | `relay failed` → `(mirror 2/5)`, playing |
| `autoplay_fixture.py` | `/play` proxy autoplay under strict policy | `playing`, overlay hidden |
| `autoplay_check.py [game]` | same through the real page | frame same-origin, autoplay attribute |
| `later_days_check.py [date]` | fixtures far past the warm window + note | Nov 10 → 6 games, "fixtures only" note |
| `click_game.py [name]` | one tap on the real page: title, video, relay, ads | title = game, readyState 4 |
| `game_in_player.py [page]` | harvest → relay → our player | 1920×1080, readyState 4, 10.2 s |
| `watch_player.py [page]` | what a page *actually* plays (frames, media requests) | found the ad bumper, then the real m3u8 |
| `srcdoc_probe.py`, `decode_srcdoc.py` | read the player shell, decode what it hides | 35,617-byte srcdoc, base64 blobs |
| `cdp.py eval <expr>` | one expression in the phone's WebView over CDP | used everywhere |
| `bname_check.py` | which browser hosts the page (UA parsing) | `Chrome 156.0.0.0` |
| `desktop_check.py`, `stats_check.py`, `pc_fs_shot.py` | desktop layout, relay→pill wiring, screenshots | — |

Typical run:

```powershell
# live hub on 8790 first (tools\deploy.ps1 -Port 8790 or start it by hand)
python docs\lag_check.py http://127.0.0.1:8790/ 45
python docs\adstrip_check.py
python docs\autoplay_fixture.py
python docs\click_game.py            # needs the emulator only for the phone variants
```

Phone-side harness needs the WebView's devtools socket:

```powershell
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
python docs\cdp.py eval "location.href"
```

---

## 8. Traps catalogue (each: symptom → cause → fix)

1. **`127.0.0.1` in a URL handed to a client.** The phone asked itself. → build URLs from the
   request `Host`.
2. **`frame:"about:srcdoc"`.** A document that only exists inside its parent is not a URL. →
   walk to the nearest http(s) ancestor (`probe_play.py`).
3. **A probe that "plays" a 480×480 ad.** `duration` and `currentTime` alone lie. → rank by
   `state==='in'`, then area, then readyState; reject tiny frames.
4. **EME is unavailable on `http://192.168.x.x`.** Secure context only. → either https, or serve
   the page from **localhost** (the standalone build does exactly that). This single fact decides
   whether games play in-app.
5. **Android 11 package visibility.** `getLaunchIntentForPackage("com.android.chrome")` returns
   **null** with no exception. → `<queries>` in the manifest.
6. **`object-fit` is ignored by native video fullscreen.** → fullscreen the container, not the
   `<video>`; keep `object-fit:contain`.
7. **A CSS rule from the phone layout applies inside fullscreen.** → override `width/height/
   aspect-ratio/max-height` in the `:fullscreen` rule with `!important`.
8. **`free_port()` bind-tested only.** `HTTPServer` sets `SO_REUSEADDR`, so on Windows a bind to
   `127.0.0.1:8900` **succeeds** while another process holds `0.0.0.0:8900`; a dead stream then
   "started", answered `/health` from the *other* relay and played it. → `connect()` probe,
   skip ports already in `RELAYS`, and refuse unless `/stats.source == url`.
9. **`terminate()` skips the child's cleanup.** The DVR folder survived. → wipe it in
   `stop_relay()` as well, and sweep at start.
10. **A seek is not a stall.** Treating it as one rotated mirrors and wiped the DVR. → honour
    `v.seeking` and a `seekGraceUntil` window.
11. **`Test-Path 'C:\Users\'` is `true`.** A directory is not `pythonw.exe`. → `-PathType Leaf`.
12. **`Start-Process` children inherit the tool's stdout pipe.** A healthy GUI app looks dead
    while the harness waits for EOF. → redirect to files, or launch via `explorer.exe`.
    Linux/Windows caveat: a force-killed command may report `[exit code: 1]` — that is
    termination, not failure.
13. **`cmd /c start "" "x.exe" --port 8801`** makes `cmd` treat `--port` as the program. → quote
    and pass args the way `start` expects, or avoid `start`.
14. **`[CmdletBinding()] param()` must be the first statement** in a script; a variable or
    `$ErrorActionPreference` above it is a parse error.
15. **PowerShell splits `--jq` expressions containing spaces.** → put the expression in a variable.
16. **`curl.exe` output in PowerShell is an array of lines.** `.Length` is the line count and
    `-match` returns matching lines, not a boolean.
17. **A folder that refuses to launch the .NET launcher.** `C:\AI STUFF\Local\nba\hub\NBAHub.exe`
    exited before running a line: no log, no window, no AppLocker/Defender event, same SHA-256
    runs fine from `C:\AI STUFF\NBAHub\`, `%TEMP%` and the repo; `whoami.exe` runs in the
    poisoned folder, so native exes are fine. → deploy to `C:\AI STUFF\NBAHub\`; keep the
    shortcut there; `tools\deploy.ps1` defaults there with a comment.
18. **A page's ad bumper is not the stream.** The first `<video>` was a 14–15 s portrait mp4. →
    capture *requests*, not DOM: the real manifest is an XHR to `/main/secure/…`.
19. **Tokens rotate.** A captured manifest is good for minutes. → `harvest` cache TTL 120 s,
    `fresh=1` bypass, and re-harvest after a failure rather than reusing a dead URL.
20. **Sync-from-git can delete uncommitted work.** The ad-strip was lost this way. → prove *and*
    commit in the same sitting; if something is missing, `docs/reapply_adstrip.py` restores it.
21. **Do not restart the live hub while the user is watching.** It kills relays and the app
    window's session. → run tests on a second port/instance, or ask.

---

## 9. Artifacts and how to rebuild

| Artifact | Path | Rebuild |
|---|---|---|
| PC launcher | `nba\hub\NBAHub.exe` (25,088 B) | `powershell -ExecutionPolicy Bypass -File pc\build-pc.ps1` (csc.exe from .NET Framework) |
| Portable bundle | `releases\NBAHub-portable.zip` (11.8 MB) | copy `nba\*` into `dist\NBAHub-portable\`, keep `python\` + site-packages, re-zip, `gh release upload v2.0 … --clobber` |
| Android APK | `releases\NBAHub.apk` (181–189 KB, v2.0/vc4) | `powershell -ExecutionPolicy Bypass -File android\build.ps1` (SDK build-tools 36 + platform-35, no Gradle) |
| Live install | `C:\AI STUFF\NBAHub\` | `powershell -ExecutionPolicy Bypass -File tools\deploy.ps1` |

Deploy script behaviour: copies runtime files only (never `appprofile/`, `browserprofile/`,
logs), backs up as `*.bak-<stamp>`, stops the old hub + relays, starts the new one **detached**
(WMI `Win32_Process.Create`), waits for `/api/ping`, prints every LAN URL with its adapter name
(it filters the WSL bridge so a phone URL is never nonsense).

Logs: `%LOCALAPPDATA%\NBAHubPC\launcher.log` (every launcher decision, plus a `boot` line),
`nba\hub\hub.err.log` (only written when a handler throws).

---

## 10. Known limits, stated plainly

1. **League Pass needs keys.** No entitlement + no CDM here; manifest and fragments are public,
   the payload is `SAMPLE-AES-CTR`.
2. **SSAI ads cannot be removed** when they are mixed into the variant playlist without markers.
3. **Seek-back past the player's buffer** trips a seek-hole fatal and restarts the relay; the
   rewind buttons were removed until that is fixed properly.
4. **Autoplay is muted**, by platform policy.
5. **The emulator is a foldable** whose inner screen is already landscape; rotation is barely
   visible there. Software-rendered emulators also die on 1080p60 decode after a few minutes
   (`0xc0000005`) — that is the emulator, not the app.
6. **The PC has to stay reachable** for the phone when it uses the LAN hub: TCP 8790 and
   8900-8959 (`FIREWALL.bat`); a "guest / client-isolated" network defeats any rule.
7. **`C:\AI STUFF\Local` is a no-go for the launcher** (§8.17).

Next steps, in the order they would pay off:

1. **CDM pipeline** (needs entitlement + `device.wvd`): PSSH → licence → `pywidevine` → decrypt →
   relay serves cleartext. Same `/api/play`, no page changes.
2. **Seek-hole fix**: re-attach hls to the *same* relay on a seek fatal instead of failing over,
   then bring back `−30 s` / `−2 min` / `live`.
3. **Phone-side ad blocking** for the site's player page: `shouldInterceptRequest` in
   `MainActivity` with the same host list as `harvest.py`.
4. **Playwright parity in the APK** (huge; better to leave game harvesting to the PC and keep the
   phone on the site's player).

---

## 2.1 -- "the video does not play in the app window"

Symptom, both shells: the game page loads, the player frame appears, no picture. Press the
browser button and the same page plays. The site was never the problem; the transport was.

Four independent faults, each sufficient on its own:

1. **No `do_POST`.** `Handler` had `do_GET` / `do_HEAD` / `do_OPTIONS`. A Widevine licence
   acquisition is `POST` with an octet-stream body, and `BaseHTTPRequestHandler` answers
   `501 Unsupported method` to anything else. The player asked, the hub said 501, no key,
   black picture, no console error that names it. Fixed with `do_POST`/`do_PUT`/`do_PATCH`/
   `do_DELETE` and a `_body()` that also decodes chunked uploads (a `fetch()` with a stream
   body has no `Content-Length`).
2. **The proxy was a fetcher, not a proxy.** `_passthrough` built a bare GET: no method, no
   body, no `Content-Type`, no `Origin`, and it flattened every upstream status to 200. A
   licence server sees `Origin: http://127.0.0.1:8790` and refuses; a ranged media request
   sees 200 and a whole file. Now: method + body + `COPY_UP`/`COPY_DOWN` headers, real
   status, `Set-Cookie` with `Domain`/`Secure`/`SameSite=None` stripped so the browser will
   store it for the hub host, and redirects rewritten back into `/ext`.
3. **Nested frames escaped the proxy.** `proxy_page` inserted `<base href>` and patched
   `fetch`/`XHR`, but left `<iframe src>` alone, so the player's own frame loaded from the
   site with the hub as its parent: `X-Frame-Options: SAMEORIGIN` or "This stream is not
   available here". `rehost_frames()` rewrites every `iframe`/`frame` `src`, and the injected
   script does the same at runtime for frames created by JS and for `srcdoc` documents.
4. **`window.open()` had nowhere to go.** `#pf` is sandboxed without `allow-popups`, so the
   call returns `null`; mirrors that open their media window that way showed nothing. The
   patch now posts the URL to the hub page, which loads it in the same frame (and the Android
   shell does the same job in `onCreateWindow`).

Traps worth keeping in the file:

- **`<base href>` eats relative proxy URLs.** The first version emitted `src="/play?u=..."`,
  which the page's own `<base>` resolved against the *site*, 404'd there, and the failure
  looked exactly like "the site refuses to be framed". Every proxy URL handed to a proxied
  page must be absolute (`http://<hub host>/play?u=...`), which is why `proxy_page` takes
  `hub_origin`.
- **A sandboxed frame keeps the real `top`.** `Object.defineProperty(window, 'top', ...)`
  works in a normal page (frame-busters are defused silently) but is refused inside `#pf`'s
  sandbox, so the buster *does* fire -- and the sandbox refuses the navigation, which is the
  outcome we want but logs an "Unsafe attempt to initiate navigation" error. Do not treat that
  console line as a regression; assert it.
- **A click on the picture is a pause toggle.** The autostart script used to click the
  `<video>` on every retry; on two of these players that pauses the stream it just started.
  Click the overlay and the play buttons, never the video more than twice.
- **`Origin` on a same-origin GET is a lie that CDNs notice.** Only uploads get an invented
  `Origin`; a GET without one stays without one.
- **Fixtures need a looping clip.** `docs/white1080.mp4` is three seconds; the first harness
  version "failed" because the video had already ended by the time it polled.

### The Android half: three gates, one of them a callback

The phone had the same symptom for a different reason -- it does not use `/play` at all, it
loads the site's own page in a WebView:

1. **`onCreateWindow()` returned `false`.** `setSupportMultipleWindows(true)` +
   `setJavaScriptCanOpenWindowsAutomatically(false)` + a refusing `onCreateWindow` meant every
   `window.open()` was dropped. The mirrors that put their media in a popup therefore had nowhere
   to put the picture. Now the popup is given a throwaway WebView, its first `http(s)` URL is
   taken, and that URL is loaded in the main WebView (`takePopup`). Ad hosts in that list are
   still dropped.
2. **The navigation guard was "same host or nothing".** A player that hands the picture to
   another host of the same site (`/main/1` -> `player-*.sytes.net`) was treated as an ad landing
   page, refused, and the app stayed black while a browser played it. The rule is now:
   known ad host -> block; `request.hasGesture()` -> allow (a tap is a tap); same host or a host
   this session has already talked to -> allow; a URL that names a player (`/main/`, `/embed`,
   `/player`, `/live`, `/secure/`) -> allow; anything else with no gesture -> block.
3. **The popup WebView was destroyed inside its own callback.** `takePopup` called
   `pop.destroy()` from within `shouldOverrideUrlLoading`, which occasionally aborted the load
   the popup was mid-way through and lost the handoff. It is now `postDelayed(700)`.

Two consequences worth remembering:

- `setJavaScriptCanOpenWindowsAutomatically` must be **true** for a script-opened popup to reach
  `onCreateWindow` at all. The old comment said the opposite, and the comment was wrong.
- A phone's WebView cannot be driven over CDP unless the app is `debuggable`, so the harness makes
  the *page* report on itself: `fixture_stream.py` serves a page that beacons
  `path/url/currentTime/readyState/videoWidth/videoHeight/UA` to `/report` once its `<video>` is
  really moving. That is the evidence, and it names the app in the UA, so a browser cannot fake it.

Emulator recipe (no device needed):

```powershell
$sdk\emulator\emulator.exe -avd NovelAI_Pixel -no-snapshot -no-audio -gpu swiftshader_indirect
python tools/verify_android_inapp.py            # 10/10, screenshot written to docs/
```
