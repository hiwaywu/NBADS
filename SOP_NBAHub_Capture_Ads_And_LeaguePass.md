# SOP — NBA Hub: capturing the mirror's real stream, removing ads, and what League Pass actually blocks

> **Scope of this document.** Everything that was done to this app, in order: what was broken when
> it arrived, how the mirror pages were taken apart, how the media playlist was recovered, where the
> ads come from and which of them can be removed, how playback was made to start by itself in the
> app's own window on both platforms, and every trap that cost time. Written so it can be re-run on
> another machine without re-deriving anything.
>
> **Target.** `C:\AI STUFF\DeepSeek\NBADS-main` → deployed at `C:\AI STUFF\NBAHub`; repo
> `github.com/hiwaywu/NBADS`; Android build `com.nbahub.app` (versionName 2.0, versionCode 4).
> **Reference games.** Atlanta at Washington (plain re-stream, `tims-001.sytes.net`), Dallas at
> San Antonio and Cleveland at Oklahoma City (League Pass, `lpnba.akamaized.net/…/hls-wvpr/…`).
> **Outcome.** Games whose mirror publishes a plain playlist play **inside our own player**
> (1920×1080, `readyState 4`, measured); League Pass-only games are **located and classified** but
> not decoded — §5 says exactly why and what three inputs would change that. Ads are removed at
> three of four layers; segment-inserted ads (SSAI) cannot be removed at all.

---

## 0. One-page timeline

| # | Step | Result |
|---|---|---|
| 1 | Phone showed a black rectangle; `/api/play` handed it `http://127.0.0.1:…` | URL was built for the server, not the client; relay bound loopback only |
| 2 | Fixed URL derivation + `--host 0.0.0.0`, then the page still looked like a desktop | mobile layout added; games handed to a browser because WebView on `http://192.168.x.x` has **no EME** |
| 3 | Fullscreen stretched the picture | native video fullscreen ignores `object-fit`; fullscreen the **container** instead → 1680×945 / 1080×609 measured |
| 4 | Latency sawtooth 14 → 22.7 → 33.7 s | three separate causes: play waited for 3 segments, relay batched edge updates, hls.js defaults → **0.4–1.2 s** to first frame, **14.6 s** / **8.2 s** low-delay, 0 stalls |
| 5 | "Why Edge?" | default browser was `MSEdgeHTM`; both open paths used it → explicit Chrome→Chromium→Firefox→Brave order, Edge opt-in only |
| 6 | Game page read as `about:srcdoc`, and the first `<video>` was an ad | the real stream is an XHR behind 2 embed layers → `harvest.py` |
| 7 | Harvest returned the media playlist | `tims-001.sytes.net/main/secure/<token>/<epoch>/nba-<id>.m3u8`, **200 with no Referer** → played in our own player |
| 8 | Other games resolved to `lpnba.akamaized.net/…hls-wvpr…` | 27 renditions, all `SAMPLE-AES-CTR`, Widevine PSSH + PlayReady WRM → §5 |
| 9 | "Make the video play when I click" | autoplay kick (mute → click overlay → play → retry 20×) injected in both runtimes, proved on a fixture |
| 10 | Phone fullscreen should be landscape | `Hub.setFullscreen` bridge → `SENSOR_LANDSCAPE` on enter, `UNSPECIFIED` + `leaveFullscreen()` on exit |
| 11 | Mirror page is an ad wall, and clicks walked the WebView onto ad landings | 43 hosts blocked via `shouldInterceptRequest`, top-level navigation whitelisted; `nav blocked:` proved |
| 12 | Merged the standalone Android branch (hub+relay inside the APK) | `main` = their 3 commits + this work; APK 25 KB → 189 KB; localhost makes EME available |
| 13 | Documented all of it | `docs/PROCESS.md`, this SOP, releases `v2.0`–`v2.3` with APK/EXE/zip |

---

## 1. Prerequisites & toolkit

| Tool | Version used | Purpose |
|---|---|---|
| Windows | 11, PowerShell 5.1 and 7 (`pwsh`) | everything below |
| Python | 3.13.7 (`C:\Users\hiway\AppData\Local\Programs\Python\Python313`) | hub, relay, harvest, every harness script |
| `requests` / `urllib3` | 2.32.x | hub fetches, relay upstream |
| Playwright | 1.5x, `channel="chrome"` | driving a real Chrome for page inspection and harvest |
| Chrome | 156 | the only local runtime with a CDM; also the app window (`--app=`) |
| .NET Framework / `csc.exe` | 4.x | builds `NBAHub.exe` (25,088 B launcher) |
| JDK + Android SDK | build-tools **36.0.0**, `platforms/android-35`, `platform-tools` | `aapt2`, `javac`, `d8`, `adb` (no Gradle) |
| AVD | `Fold7_Test` (Android 15, `x86_64`) | install-and-test loop |
| `ffmpeg` / `ffprobe` | 8.x | generating `docs/white1080.mp4`, judging fragments |
| `gh` CLI | logged in as `hiwaywu`, `repo` scope | releases, asset uploads |

Machine-specific gotchas, collected so they are not rediscovered (all of these bit me):

- **PowerShell splits `--jq` expressions containing spaces** → `gh` exits with `accepts 1 arg(s)`.
  Put the expression in a variable: `$jq='.assets[] | .name'; gh api … --jq $jq`.
- **`curl.exe` output is an array of lines in PowerShell.** `($body).Length` is a *line count*, and
  `-match` returns matching lines, not a boolean. Join first: `$txt = $body -join "`n"`.
- **`Test-Path 'C:\Users\'` is `true`** — a directory satisfies it. Every "is pythonw.exe there"
  check needs `-PathType Leaf`.
- **`Start-Process` children inherit the tool's stdout pipe**: a healthy GUI app looks dead because
  the harness waits for EOF. Redirect to files, or launch via `explorer.exe`.
- **`adb exec-out screencap -p > x.png` can hang or corrupt.** Use
  `adb shell screencap -p /sdcard/x.png` then `adb pull`.
- **`git pull --rebase` refuses a dirty tree** and prints "Please commit or stash them" *after* the
  commands that follow it have already run — read the whole transcript, not the last line.
- **`gh release upload` must run inside a git repo** (`failed to run git: fatal: not a git
  repository`), i.e. from the clone, not from the deploy folder.
- **GitHub's download URL can return `504 Gateway Time-out` (92-byte HTML)** while the API works.
  Verify assets with `gh release download`, not only with `curl -L`.

---

## 2. The system under test

```
PC                                             Phone (standalone since v2.0)
┌──────────────────────────┐                   ┌───────────────────────────┐
│ NBAHub.exe (launcher)    │                   │ NBAHub.apk                │
│  └─ pythonw hub.py       │  LAN 8790         │  └─ LocalHub.java         │ same endpoints,
│      ├─ /        page    │  relays 8900+     │      ├─ /        page     │ same page,
│      ├─ /api/…   JSON    │                   │      ├─ /api/…            │ served from
│      └─ relay.py         │                   │      └─ Relay.java        │ 127.0.0.1:8790
└──────────────────────────┘                   └───────────────────────────┘
                    the page: nba/hub/index.html + vendored hls.min.js
```

| Source | Who plays it |
|---|---|
| NBA TV channels (`nba/feeds.txt`, 4 mirrors: `23.237.104.106`, Akamai FAST, Amagi, backup) | our `<video>` through the relay |
| Games on plain mirrors | our `<video>` through the relay (§4) |
| Games only on League Pass | the mirror's page in an iframe (PC) / in the WebView (phone) — §5 |

Key endpoints added or used: `/api/play?u=`, `/api/harvest?u=`, `/api/lp?u=`, `/api/games`,
`/api/dates` (+`streams{}`, `season{}`), `/api/next`, `/api/resolve`, `/api/candidates`,
`/api/health`, `/api/freeze`, `/api/ping`, `/api/shutdown`, `/play?u=` (proxy), `/watch?u=` (shell),
`/ext?u=` (subresource passthrough), `/seg/<n>.ts`, `/live.m3u8`, `/stats`.

---

## 3. Phase 1 — Recon: what the mirror page actually does

Three layers stand between a click and a picture. Observed on
`methstreams.st/event/m-<teams>-<date>`:

```
event page    https://methstreams.st/event/m-<teams>-<date>
   ↓   the page's own player is an <iframe srcdoc>, i.e. it has no URL (about:srcdoc)
player page   https://foxiy.st/event/m-<teams>-<date>/main/1
   ↓   its first <video> is a 14–15 s portrait ad bumper on subduepaler.cyou
embed         https://ehgembxe.cfd/trefoxy/pl/<JWE>
   ↓   XHR, token and epoch inside the path
media         https://tims-001.sytes.net/main/secure/<sha>/<epoch>/nba-<id>.m3u8
```

Evidence, in the order it was obtained:

1. **Naive network tap finds nothing** — `harvest.py` first version returned `media: []`, `keys: []`.
   The player is inside `about:srcdoc`, so its requests are attributed to the parent frame.
2. **Reading the shell** (`docs/srcdoc_probe.py`) — the srcdoc is **35,617 bytes**, contains
   `<video>`/`<source>`/`canvas`/`button`s and one absolute media URL: a *portrait ad*. That is what
   the DOM will show you. The stream is not in the DOM.
3. **Watching what actually plays** (`docs/watch_player.py`, clicks every `video`/`button`/overlay
   in every frame, then reports `currentSrc`, duration, size and all media requests):

   ```json
   {"url":"about:srcdoc","video":{"src":"https://subduepaler.cyou/lp/v/99/c6/….mp4",
    "duration":15,"w":1080,"h":1920},
    "media_requests":[{"url":"https://tims-001.sytes.net/main/secure/…/nba-401909923.m3u8",
    "type":"xhr","referer":"https://ehgembxe.cfd/"}]}
   ```

   Two facts fall out: the ad is `1080×1920` and 15 s (so a probe that only checks "a video is
   playing" is fooled), and the real media is an **XHR** — nothing you can read from the DOM.
4. **Base64 in the shell is a red herring** (`docs/decode_srcdoc.py`): 3 blobs, all decode to
   duplicate inline PNGs. No hidden config, no `eval(function(p,a,c,k,e,d))` packer.

Rule adopted: **capture requests, never the DOM.** Everything after this phase works on URLs.

---

## 4. Phase 2 — Capture: `nba/hub/harvest.py`

`/api/harvest?u=<page>` runs it as a subprocess (120 s cache, `fresh=1` to bypass) and returns JSON.
Algorithm:

1. launch **real Chrome** (`channel="chrome"`, headful offscreen `--window-position=-2400,-2400`,
   `--autoplay-policy=no-user-gesture-required`, `--mute-audio`) — headless is fingerprinted and the
   player refuses;
2. abort ad/tracker hosts while loading (same list as the app, §6) so the pre-roll never competes;
3. load the page, follow `a[href*="/main/"]` if we are on the event page;
4. every 1.2 s, in **every** frame: `video.muted=true`, `video.play()`, `video.click()`, click
   `button`, `[class*=play]`, `[id*=play]`, `.overlay`, `#play` — the ad layer has to finish or be
   skipped before the player asks for media;
5. record every request matching `\.m3u8(\?|$)` and `\.key(\?|$)|/key/`; keep the newest unique
   manifest and its `Referer`;
6. fetch each candidate (8 s timeout) and classify:

```json
{"ok":true,
 "m3u8":"https://tims-001.sytes.net/main/secure/0bb9bfcc…/1793858160/nba-401909923.m3u8",
 "referer":"https://ehgembxe.cfd/",
 "source":"https://foxiy.st/event/m-atlanta-hawks-vs-washington-wizards-1105/main/1",
 "drm":false,
 "stream":{"league_pass":false,"game_id":null,"widevine":false,"sample_aes":false,
           "key_uri":null,"license":null},
 "alternates":[…],"checked":[{"url":"…","drm":false,"bytes":1267}],"seen":1}
```

Classification rules (`describe()`): `league_pass` = host `lpnba.akamaized.net`; `game_id` = the
NBA id inside the path (`g\d{10}[a-z]{3}\d{6,8}[a-z]{2,3}`); `widevine` = `hls-wvpr` in the URL or
`widevine` in the body; `sample_aes` = `METHOD=SAMPLE-AES`; `key_uri`/`license` = the `URI=` of the
first `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`.

Then the hub hands the manifest to the **same relay** the channels use, and the page attaches with
hls.js. Measured on the Atlanta game:

```
title            Atlanta Hawks vs Washington Wizards
video            1920x1080, readyState 4, playing
relay source     https://tims-001.sytes.net/main/secure/0bb9bfcc…
relay pull       16 Mbps, 25 s buffered, 11.7 s behind live
iframeMode       false
```

Why this is not "cracking League Pass": the manifest answers **200 with no Referer, no cookie, no
login** (1,267 bytes). The mirror does the entitlement work; we reuse the URL it ends up using.

---

## 5. What "defeating League Pass" means

League Pass is Widevine. Two ways a mirror can put it in front of a visitor, and only the first is
reusable by us:

| Mechanism | What the visitor's browser receives | Can we replay it? |
|---|---|---|
| Mirror re-encodes / re-saves the picture once (has access on their side) | a **plain** HLS stream of their own | **yes** — that is §4 |
| Mirror hands out NBA's own locked URL and unlocks it in its page (key on their side) | `hls-wvpr` manifest + their player doing the licence exchange | no — needs the key |

Measured on the League Pass route (`/api/lp?u=<game>`, Dallas at San Antonio):

```json
{"ok":true,"playable":false,"protection":"METHOD=SAMPLE-AES-CTR",
 "stream":{"league_pass":true,"game_id":"g0022600184dal1000445sas","widevine":true,"sample_aes":true},
 "renditions":27,"best":{"w":1920,"h":1080,"mbps":9.86,"codecs":"avc1.64002A"}}
```

- master `…/index.m3u8` = 25,346 bytes, 27 renditions, 400×224 → 1920×1080 @ 9.86 Mbps;
- every rendition carries `#EXT-X-KEY:METHOD=SAMPLE-AES-CTR` with **two** descriptors: a Widevine
  PSSH (`…edef8ba9-79d6-4ace-a3c8-27dcd51d21ed…`) and a PlayReady WRM header
  (`<WRMHEADER xmlns="http://schemas.microsoft.com/DRM/2007/03/PlayReadyHeader">`);
- no clear rendition exists to fall back to;
- a 1080p fragment downloads fine: **200, 702,916 bytes, 76 ms, `video/mp4`** (`moof`+`mdat`);
  `ffprobe` then says "invalid data found" — the payload is keyed.

What would make it playable in our player, and what is missing here:

1. an **entitlement** — a logged-in League Pass session the licence server will answer;
2. a **CDM** — a Widevine L3 device blob (`device.wvd`);
3. wiring: PSSH → signed challenge → licence URL → `pywidevine` content keys → decrypt each
   fragment (`cbcs`/`cenc`, or SAMPLE-AES for CBC) → serve cleartext to the relay. Same
   `/api/play`, no page changes; the bar pill would read `relay - <game>`.

Without 1 and 2 there is nothing to decrypt with, so `playable:false` is **data, not a bug**.

The iQIYI SOP's trick (`SOP_iQIYI_Paywall_Workaround.md`: replay the page's own signed `/dash`
against a public media host, then `QiyiPlayerBID=…500` for 720p) has **no analogue here**: iQIYI
left a signed URL and a bid ceiling in the page; NBA leaves an encrypted manifest. There is no
cheaper host to switch to — the cryptography is the wall, not the front door.

---

## 6. Phase 3 — Ads: four layers, three of them removable

| Layer | Where it lives | What was done |
|---|---|---|
| Mirror page pre-roll (14–15 s portrait mp4, `subduepaler.cyou`) | the site's own player | never reaches us on the harvest path; aborted inside the harvest browser |
| Page furniture (nav, chat, banners, 30+ trackers) | the site's HTML | `/play?u=` proxy injects `HIDE_CSS`; phone blocks hosts and hides blocks (§6.2) |
| HLS ad breaks with markers (`#EXT-X-CUE-OUT/-CONT/-IN`, `#EXT-X-SCTE35`, `#EXT-X-DATERANGE`) | inside the stream | relay drops those segments, publishes `#EXT-X-DISCONTINUITY`, reports `ads_skipped` (§6.3) |
| SSAI / DAI (`ads=mr` on NBA's CDN) | mixed into the variant playlist, no markers | **cannot** be removed; the only honest options are another mirror or accept it |

### 6.1 Blocking, PC side

`hub.py` proxies player pages through `/play?u=`: it injects `<base>`, `HIDE_CSS` (header/nav/chat/
banner/notice → `display:none`), `PATCH_JS` (rewrites `fetch`/XHR/`src` through `/ext?u=`) and
`AUTOPLAY_JS` (§7.3). Its own page never loads mirror JS.

### 6.2 Blocking, phone side (`MainActivity`)

```java
private static final String[] AD_HOSTS = { /* 43 entries */ };
public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
    if (host matches AD_HOSTS) return new WebResourceResponse("text/plain","utf-8",empty);
    return null;
}
```

Host families: `subduepaler.cyou`, `doubleclick.net`, `googlesyndication.com`, `adservice.google.com`,
`googletagmanager.com`, `google-analytics.com`, `histats.com`, `dtscout.com`, `sharethis.com`,
`crwdcntrl.net`, `lijit.com`, `intentiq.com`, `mrktmtrcs.net`, `dtscdn.com`, `cloudflareinsights.com`,
`reliedhounder.com`, `trend48.st`, `adcash`, `popads`, `popcash`, `adsterra`, `highperformance`,
`hilltopads`, `exoclick`, `tsyndicate`, `profitableratecpm`, `onclickalgo`, `adnium`, `clickadu`,
`galaksion`, `revcontent`, `taboola`, `outbrain`, `zedo`, `mgid`, `trafficjunky`, `juicyads`,
`adspyglass`, `ad-maven`, `tynt.com`, `pxdrop`, and the rest.

Measured: **30 blocked requests on a single game-page load** (`ad blocked: <url>` in logcat), page
still plays.

**The one that mattered most** was not a host but a navigation. Their
`shouldOverrideUrlLoading` let every `http(s)` navigation through (`if (scheme.startsWith("http"))
return false;`), so a click on an ad **replaced the game page with an ad landing page** — same
window, same app, but the viewer is now on a strange site and the only way out is Back. That is what
"it goes outside the app" actually was. Now:

```
our hub                          allowed
same host (main/1 -> main/2)     allowed
foxiy / methstreams / methfoxiy / nbahub   allowed
anything else                    refused + "nav blocked: <url>"
```

Proved: from the game page, `location.href='https://example.com/ad-landing'` →
`nav blocked: https://example.com/ad-landing`, WebView stays on the game. Popups were already dead
(`setSupportMultipleWindows(true)` + `onCreateWindow` returning false logs `popup blocked`), so
`window.open` was never the leak.

### 6.3 Ad-break stripping (`nba/relay.py`)

`parse_media()` marks every entry with an `ad` flag from cue markers, `Relay.ad_seqs` holds them,
they are **never downloaded and never served**, and `playlist_text()` states the holes:

```
segments served   100 101 102 105 106      (103/104 were the ad break)
discontinuity     true
ads_skipped       2
```

Proof: `docs/adstrip_check.py` runs a local origin with a `CUE-OUT`/`CUE-IN` block and asserts all
four facts. Note the history — this was built, proved, then **lost** when a folder sync overwrote
`relay.py` with the committed copy; `docs/reapply_adstrip.py` restores it idempotently.

---

## 7. Phase 4 — Playback engineering

### 7.1 Latency: three causes, three fixes

| Cause | Evidence | Fix | After |
|---|---|---|---|
| `/api/play` waited for `/health == 200` (3 segments) | 6–10 s spinner | return as soon as the relay *listens* (200 **or** 503); hls.js retries the manifest 10× with 700 ms | **0.4–1.2 s** PC, 2.9 s phone |
| relay pump slept up to 3 s → a poll picked up 2 segments → edge jumped 12 s | sawtooth 14 → 22.7 → 25 → 33.7 s | poll at `target/3`, fetch newest-first, `ready` on the edge segment | steady 14.1 s |
| hls.js defaults | — | `enableWorker`, `liveSyncDurationCount` 2 (1.25 low), `maxLiveSyncPlaybackRate` 1.35, ceiling `target*6+4` | **14.6 s** avg, **8.2 s** low, **0 stalls** in 60 s |

Floor is the source: 6 s segments, ~8 s is the honest minimum for this kind of stream.

### 7.2 DVR and failover

RAM ring 10 segments (~70 s); anything older spills to `<nba>\dvr\<port>\` (cap 15 min / 1200 MB,
8 min in low-delay); playlist spans RAM ∪ disk. Measured **7.4 min / 247 MB** after 8 minutes, and
the folder is gone after the relay stops (relay `finally` + `stop_relay()` + `sweep_dvr()`).
Channel failover signals: no progress 4 s / 3 `waiting` in 60 s / fatal after one retry /
`/api/play` false → next untried mirror, ≤1 per 15 s, `(mirror N/M)` in the title. **A seek is not a
stall** — treating it as one rotated mirrors and wiped the DVR, which is why the rewind buttons were
removed rather than shipped half-working.

### 7.3 Autoplay: "make the video play when I click"

The click that opens a page does not travel into the new document, so the player sits on its poster.
Same script in both runtimes:

- PC: `AUTOPLAY_JS` injected into the `/play`-proxied page;
- phone: `MainActivity.kickPlayback()` via `evaluateJavascript` on every non-home `onPageFinished`,
  fired at **1.2, 3, 5, 8, 12 s** because these pages rewrite their own document.

Behaviour: mute → click poster/overlay/`button` → `play()` → retry up to 20× → on success set
`data-nbaautoplay="playing"`, show `muted - tap the picture for sound`, unmute on first tap.
Proved on a fixture under `--autoplay-policy=document-user-activation-required`:
PC `playing / paused=false / muted=true`; phone `playing / readyState 4 / clock at 3 s`.
Sound starts muted because both Chrome and Android block unmuted autoplay without a gesture.

### 7.4 Fullscreen

- **Letterbox, not stretch**: fullscreen the **container** (`#stage`), never the `<video>`;
  `#stage:fullscreen{width:100vw;height:100vh;max-height:none;aspect-ratio:auto;display:flex}` +
  `video{object-fit:contain}`. Measured 1680×945 on a 16:10 screen and 1080×609 on a phone.
- **Landscape on the phone**: `Hub.setFullscreen(on)` bridge →
  `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` + immersive on enter; `UNSPECIFIED` + `leaveFullscreen()` on
  exit; called from the click handler *and* `fullscreenchange` (a WebView does not reliably fire the
  latter for a div). Measured: stage 935×842, `screen.orientation=landscape-primary`, video
  1920×1080; exit → list back (6 rows).
- **Scrolling**: verified the list really scrolls (programmatic 400 px sticks, `adb input swipe`
  0 → 391 px, no reset over 20 s, also while a channel plays); hardened anyway with
  `touch-action:pan-y`, `overscroll-behavior:contain`, `-webkit-overflow-scrolling:touch`, and body
  transitions off.

---

## 8. Verification harness (`docs/`)

Every claim above is reproducible; each script prints JSON and PASS/FAIL where it matters.

| Script | Proves | Last reading |
|---|---|---|
| `lag_check.py [url] [secs] [low]` | click→frame, latency, stalls, pull | 0.4–1.2 s, 14.6 s / 8.2 s, 0 stalls |
| `phone_lag_check.py` | same over CDP | 2.9 s, 14.2 s, 0 stalls |
| `harvest.py <page>` | the media playlist + classification | `ok:true, drm:false` |
| `game_in_player.py <page>` | harvest → relay → our player | 1920×1080, `readyState 4`, 10.2 s |
| `watch_player.py <page>` | what a page *actually* plays | ad bumper, then the real m3u8 |
| `srcdoc_probe.py` / `decode_srcdoc.py` | the player shell and its blobs | 35,617 B srcdoc, 3 PNG blobs |
| `adstrip_check.py` | cue-marked ads dropped | 100·101·102·105·106, `ads_skipped 2` |
| `autoplay_fixture.py` | `/play` autoplay under strict policy | `playing`, overlay hidden |
| `autoplay_check.py [game]` | autoplay through the real page | same-origin frame, attribute set |
| `fs_hub_check.py` / `phone_fs_check.py` | letterbox geometry, pixel-measured | 945/1680, 1080×609 |
| `dvr_check.py` | disk tier fills, wipes on stop | 7.4 min / 247 MB |
| `mirror_failover_check.py` | dead mirror → next, no dialog | `relay failed` → `(mirror 2/5)` |
| `click_game.py [name]` | one tap on the real page | title=game, `readyState 4` |
| `cdp.py eval "<expr>"` | any expression in the phone's WebView | used everywhere |
| `later_days_check.py` | schedules far past the warm window | Nov 10 → 6 games + note |

```powershell
# live hub on 8790 first
python docs\lag_check.py http://127.0.0.1:8790/ 45
python docs\adstrip_check.py
python docs\autoplay_fixture.py
python docs\harvest.py "https://methstreams.st/event/m-atlanta-hawks-vs-washington-wizards-1105"
# phone: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
python docs\cdp.py eval "location.href"
```

Release-asset verification (do this, not just `ls`): pull the asset back and hash it.

```powershell
$jq='.assets[] | .name'; gh api repos/hiwaywu/NBADS/releases/tags/v2.3 --jq $jq
gh release download v2.3 -p NBAHub.apk -D $env:TEMP\apk-api
(Get-FileHash "$env:TEMP\apk-api\NBAHub.apk").Hash -eq (Get-FileHash releases\NBAHub.apk).Hash
```

---

## 9. Traps (each: symptom → cause → fix)

1. **`127.0.0.1` in a URL handed to a client** — the phone asked itself. Build URLs from the request
   `Host`; the relay needs `--host 0.0.0.0`.
2. **`frame:"about:srcdoc"`** — not a URL, only exists inside its parent. Walk to the nearest http(s)
   ancestor.
3. **A probe that "plays" a 480×480 ad** — `duration`/`currentTime` lie. Rank by `state==='in'`,
   area, `readyState`.
4. **No EME on `http://192.168.x.x`** — secure context only. Serve from localhost (the APK does) or
   https. This single fact decides whether games play in-app at all.
5. **Android 11 package visibility** — `getLaunchIntentForPackage("com.android.chrome")` returns
   `null` silently. Needs `<queries>` in the manifest.
6. **`object-fit` ignored by native video fullscreen** — fullscreen the container, not the video.
7. **Mobile CSS leaking into fullscreen** — override `width/height/aspect-ratio/max-height` with
   `!important` in the `:fullscreen` rule.
8. **`free_port()` bind-tested only** — `HTTPServer` sets `SO_REUSEADDR`, so on Windows a bind to
   `127.0.0.1:8900` succeeds while another process holds `0.0.0.0:8900`; a dead stream then answered
   `ok:true` and played a *foreign* relay. Fix: connect probe + skip `RELAYS` ports + refuse unless
   `/stats.source == url`.
9. **`terminate()` skips the child's cleanup** — DVR folder survived. Wipe in `stop_relay()` too.
10. **A seek is not a stall** — honour `v.seeking` and a `seekGraceUntil` window, or the watchdog
    rotates mirrors and wipes the DVR.
11. **`Test-Path` on a directory is `true`** — use `-PathType Leaf`.
12. **`Start-Process` children inherit the stdout pipe** — a healthy GUI looks dead.
13. **`cmd /c start "" "x.exe" --port 8801`** — `cmd` treats `--port` as the program.
14. **`[CmdletBinding()] param()` must be the first statement** in a script.
15. **PowerShell splits `--jq` with spaces** — put the expression in a variable.
16. **`curl.exe` output is an array of lines** — join before measuring or matching.
17. **A folder that refuses to launch the .NET launcher** — `C:\AI STUFF\Local\nba\hub\NBAHub.exe`
    exits before running a line; the same SHA-256 runs from the repo, `%TEMP%` and
    `C:\AI STUFF\NBAHub`. No AppLocker/MoTW/Defender cause found; deploy elsewhere.
18. **The first `<video>` is an ad** — capture requests, never the DOM.
19. **Tokens rotate** — a harvested manifest is good for minutes. Cache 120 s, `fresh=1`, re-harvest
    on failure.
20. **Sync-from-git deletes uncommitted work** — the ad-strip vanished this way. Prove *and* commit
    in the same sitting; keep an idempotent re-apply script.
21. **A guard that skips itself on a method name** — `if (-notmatch 'shouldOverrideUrlLoading')`
    skipped because the method already existed, so the "fix" silently did nothing and the release
    could still be walked onto an ad page. Check the *inserted text*, not the method name.
22. **A build artifact built in the clone but not copied into the clone before `git add`** — the
    release had the new APK, the tree had the old one (`releases/NBAHub.apk` 189,089 vs 184,993).
    Commit the artifact in the same commit as the code.
23. **Release timestamps are publish times, not freshness** — "2 hours ago" only means nobody
    uploaded since. Prove freshness by hashing the downloaded asset, not by reading the clock.
24. **GitHub's download endpoint can 504 while the API works** — verify with
    `gh release download`, not only `curl -L`.
25. **Do not restart the live hub while the user is watching** — it kills relays and the app's
    session, and the app window dies with it. Test on a second port/instance.

---

## 10. Artifacts, releases, how to publish

| Artifact | Path | Build |
|---|---|---|
| APK | `releases/NBAHub.apk` (189,089 B, `versionName 2.0`, `versionCode 4`) | `powershell -File android\build.ps1` (SDK 36, platform-35, no Gradle) |
| PC launcher | `nba/hub/NBAHub.exe` (25,088 B) | `powershell -File pc\build-pc.ps1` (`csc.exe`) |
| Portable bundle | `releases/NBAHub-portable.zip` (12,356,861 B) | copy `nba/*` into `dist/NBAHub-portable/`, keep `python/`, re-zip |
| Live install | `C:\AI STUFF\NBAHub\` | `powershell -File tools\deploy.ps1` |

Publishing (order matters):

```powershell
cd $env:TEMP\NBADS-git                       # gh release upload needs a repo
git add -A; git commit -F msg.txt; git push origin HEAD:main
gh release create vX.Y releases\NBAHub.apk nba\hub\NBAHub.exe releases\NBAHub-portable.zip `
   --title "vX.Y - …" --notes-file notes.md --latest
gh release download vX.Y -p NBAHub.apk -D $env:TEMP\apk-api   # verify by hash
```

Version labels: the release tag (`v2.3`) and the app's own version (`2.0`) are different numbers and
that mismatch confused the user twice. If they must match, bump `--version-name` in `build.ps1`.

Releases currently published: **v2.3** (ad click-through guard + list hardening), v2.2 (phone ad
blocking), v2.1 (republish, unambiguous), v2.0 (standalone, one-tap, autoplay), v1.3 (history).

Verified content of the shipped APK (not just its size):

```
classes.dex contains: "nav blocked", "ad blocked", "trend48", "popup blocked", "setFullscreen",
                      "kickPlayback", "leaveFullscreen", "data-nbaautoplay"
assets/index.html 52,806 B, sha F02F6B42FAE8 — byte-identical to nba/hub/index.html
```

---

## 11. Standing and maintenance

- **Current state, not a guarantee.** The harvest depends on the mirror chain keeping the shape
  `event → /main/N → embed → XHR`. If a mirror changes hosts, `harvest.py` still works (it reads
  requests, not URLs) but the whitelist in `MainActivity.shouldOverrideUrlLoading` needs the new
  host, or the game page will not open.
- **League Pass ceilings are commercial, not cryptographic, from our side.** `hls-wvpr` renditions
  and their PSSH are stable; only a CDM + entitlement changes the outcome.
- **Ad hosts drift.** Every blocklist entry is a substring match in one Java array (phone) and one
  Python tuple (harvest). Add, rebuild, ship.
- **The mirror page's own overlays cannot be removed** without breaking the player that shares the
  page. If that ever becomes the top complaint, the answer is to clip the page to the video box
  (inject `body>*:not(player){display:none}`), not to fight each overlay.
- **Seek-back past the player's buffer** still trips a seek-hole fatal and restarts the relay; the
  proper fix is re-attaching hls to the same relay instead of failing over, then bringing the
  rewind buttons back.

---

## 12. Changelog

### v2.3 — the one where clicks stay in the app

| Fix | Why it mattered |
|---|---|
| `shouldOverrideUrlLoading` whitelist | ad clicks **replaced the page with an ad landing**, which reads as "it left the app" |
| 43 ad hosts (was 21) | the visible ad wall, not just the trackers |
| `#list` touch-action / no body transition | "scroll down breaks, stuck at top" |

### v2.2 — phone ad blocking

`shouldInterceptRequest` + injected CSS + the kick repeated 5× (pages rewrite their own document).

### v2.1 / v2.0 — standalone, one-tap, autoplay

Hub and relay inside the APK (`LocalHub.java`, `Relay.java`, `Games.java`, `Http.java`), served from
`127.0.0.1` so EME exists; one tap opens the game in-app; the picture starts itself, muted.

### v1.3 and earlier — the PC side

Loopback URL fix, mobile layout, container fullscreen, three latency causes, browser pinning,
portable Python bundle, DVR, failover, harvest.
