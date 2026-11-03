# SOP — NBA Stream Hub: schedule-synced game list, forced-quality HLS relay, and a Fold-native Android shell

Reusable procedure for building a **local, ad-free stream aggregator** that keeps a game
list in sync with the real NBA schedule, forces the highest rendition on every open HLS
stream, survives mid-game feed failures, and ships to a foldable phone as an APK — written
from a real run on **Windows 11** against the `methstreams` / `crackstreams` family of
aggregators, with the target device being a **Samsung Galaxy Z Fold 7**.

Everything here was built and verified in one session, including the failures. The parts
that could **not** be verified are marked `UNVERIFIED` rather than described as working.

Two artefacts are produced:

| Artefact | What it is |
|---|---|
| `nba/` | Python + PowerShell hub. Scrapes the schedule, probes feeds, runs a local buffering HLS relay, serves a browser UI. |
| `android/` | A 16 KB WebView shell (`com.nbahub.app`) built **without Gradle**, installable on Android 7+. |

---

## What was delivered, in order

1. **A game list that cannot lie about the date.** ESPN's scoreboard API is the source of
   truth; the aggregators only supply stream URLs. Nothing is inferred from a URL again
   (that mistake cost a full rebuild — see §3.3).
2. **Quality forcing.** The relay reads the HLS master playlist and picks the top rendition
   by `RESOLUTION` then `BANDWIDTH`. Verified: `1920x1080 @ 59.940 fps, 8.25 Mbps` on a feed
   whose first variant is 640x360.
3. **A local buffer that removes stutter.** ~10 segments held in RAM, served from
   `127.0.0.1`, prefetched over 6 parallel keep-alive sockets. Measured **80–200 Mbps** pull
   against a 6–8 Mbps stream.
4. **A page that never shows site chrome.** The real player host is extracted from inside
   the aggregator page and re-hosted in a local 16:9 sandboxed shell; popups are made
   impossible, not blocked.
5. **A switching policy with a 2-minute grace**, 15-second stall detection, ask-before-upgrade
   and a 3-freeze threshold — plus an honest account of what code cannot see (§8).
6. **A signed APK built by hand** (`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`),
   fullscreen, fold-safe, with an error page instead of a black screen.
7. **Verified on a foldable emulator** at the Fold 7's inner geometry — layout, fullscreen,
   LAN fetch, manifest parse at 1080p60. Video **decode** did not start on the emulator and
   is marked `UNVERIFIED` (§10.3).

---

## 1. Prerequisites & toolkit

| Tool | Version used | Purpose |
|---|---|---|
| Python | 3.13.15 | hub, relay, probes (`requests` 2.34.2) |
| Node | 18+ | not required by the app; only used incidentally |
| ffmpeg / ffprobe | 9.0.2 (winget `Gyan.FFmpeg`) | one-off stream validation, icon generation |
| Playwright (Python) | chromium 156.0.8078.4 | headless probes: does this page *play*? |
| JDK | 21.0.12.1 (Microsoft) | `javac`, `keytool` |
| Android SDK build-tools | **36.0.0** | `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android platform | `platforms/android-35/android.jar` | compile-time API |
| platform-tools | 37.0.1 | `adb` |
| Emulator AVD | API 34, `google_apis`, `x86_64`, device profile **8in Foldable** | install-and-screenshot loop |
| `gh` CLI | authenticated as the repo owner | `gh repo clone`, push |

Machine-specific setup that costs time if missed:

- SDK lives at **`C:\Android\Sdk`**, not under `%LOCALAPPDATA%`.
- **`build-tools\34.0.0`'s `d8` crashes on any anonymous inner class** compiled by javac 21
  (`NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`,
  origin `Foo$1.class`). `build-tools\36.0.0` handles the same classes. Use 36.
- PowerShell 5.1: native tools writing to **stderr** become terminating errors when
  `$ErrorActionPreference='Stop'`. `keytool -genkeypair` prints progress to stderr and will
  abort the build after it has already created the keystore. Use `'Continue'` + explicit
  `$LASTEXITCODE` checks, or `*> $null`.
- **`adb install` streams incrementally by default** on API 30+. The APK is served from the
  host; when that session drops, the package is torn down and the running app dies with
  `Got obituary of <pid>`. Symptom: the app vanishes after a few taps, with no crash in
  logcat. Always `adb install --no-incremental` when testing.
- The workspace path contains a space (`C:\AI STUFF\…`); quote every path passed to
  `Start-Process`, `jar`, `d8`, `javac`.

---

## 2. What the sources actually are (recon before writing any code)

### 2.1 The three independent data flows

| Need | Source | Shape | Keyless? |
|---|---|---|---|
| Which games, what time, are they final | `site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=YYYYMMDD` | JSON, `events[].competitions[0].competitors[]` | yes |
| Stream page per game | `https://methstreams.st/nba`, `https://crackstreams.st/nba` | HTML containing a `schema.org ItemList` block | yes |
| The actual video | pushed from inside the player iframe | HLS `.m3u8` | yes, but DRM (see §2.4) |

### 2.2 The aggregator list is machine-readable — don't scrape the DOM

Each NBA page embeds one `<script type="application/ld+json">` block:

```json
{"@type":"ListItem","position":1,
 "name":"Oklahoma City Thunder vs Cleveland Cavaliers",
 "url":"https://methstreams.st/event/m-oklahoma-city-thunder-vs-cleveland-cavaliers-1103"}
```

Sixteen entries, stable, no browser needed. Parsing the rendered page instead is a dead end:
those pages are JavaScript apps and the raw HTML contains **no** `.m3u8` and **no** `<iframe>`.

### 2.3 The UTC date trap — the bug that produced "no games today"

The URL suffix (`-1103`) is the **UTC** date. A 7:00 PM Eastern tip-off is already past
midnight UTC, so tonight's games are stamped with *tomorrow's* number. Reading that suffix as
the local game date files the entire slate one day forward and leaves "today" empty — which
is exactly what happened here, and what made the first build look broken while 15 games were
sitting in the list.

```text
ESPN says:   Nov 2 slate = 15 games, first tip 2026-11-03T00:00Z   <- game night = Nov 2
Aggregator:  every one of those games is suffixed -1103            <- UTC date
```

**Rule that came out of this:** a date is not a fact to be inferred from a URL. Join the
stream to an authoritative schedule by **team name** and take the date and time from the
schedule. Never guess.

### 2.4 The video is DRM'd — this decides the whole architecture

Following one game page to its actual stream:

```text
methstreams /event/m-toronto-raptors-vs-la-clippers-1103
  -> "Main 1" link  ->  foxiy.st/event/.../main/1
  -> <iframe>       ->  topstreamer.site/iframe/nba/clippers
  -> network        ->  lpnba.akamaized.net/live-pz/p/hls-wvpr/g0022600171tor1000460lac/index.m3u8
```

The master playlist carries:

```text
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT="urn:uuid:edef8ba9-…"   <- Widevine
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT="urn:uuid:9a04f079-…"   <- PlayReady
```

`ffprobe`, VLC and hls.js all fail on it. Only a browser with a CDM can decrypt. Consequence,
and it governs everything after this point:

- **Channels** (open HLS, no DRM) → can play in our own player, buffered by our relay.
- **Games** (Widevine) → can only play inside a browser engine. Our job becomes: pick the
  right page, present it cleanly, and know when it has gone bad.

Extracting the raw URL is still useful — it takes ~2 s with a headless browser and gives the
health signal in §8 — but it is not playable outside a browser.

---

## 3. Architecture

```text
nba/
  feeds.txt            permanent channel list      NAME | URL
  live.txt             written by refresh.ps1, read first
  sites.txt            pages the scraper may try
  relay.py             local HLS relay: rendition pick + buffer + stats
  sniff.py             headless-browser stream hunter (experimental)
  play.ps1             CLI launcher (channels)
  games.ps1            CLI game list
  refresh.ps1          harvest + probe, rewrites live.txt
  hub/
    hub.py             HTTP server: games API, resolve API, shell, proxies
    index.html         the UI (single file, hls.js)
    probe_play.py      "does this page actually play?" (all frames)
    find_stream.py     "what is the real playlist behind this page?"
    clean/             MV3 extension (see §7.4 — does not load on modern Chrome)
    NBAHUB.bat         launcher: restart server, open app window
android/               WebView shell, built without Gradle
```

The hub is deliberately **stdlib-only** apart from `requests`: one file to read, no
dependency resolution, no build step, starts in under a second.

### 3.1 Endpoints

| Route | Does |
|---|---|
| `/api/games?date=YYYY-MM-DD` | games for that night, joined to ESPN, with `state` (`in`/`post`), tip time, stream URL, and source health |
| `/api/dates` | every date in the ESPN window that has games |
| `/api/channels` | parsed `feeds.txt` |
| `/api/play?u=<feed>` | starts/keeps a relay, returns `http://127.0.0.1:PORT/live.m3u8` |
| `/api/resolve?u=<event>` | probes the game's feeds until one plays, returns the **player-host iframe URL** |
| `/api/watch?u=<event>` | same, then opens a clean app window (desktop path) |
| `/api/health?u=<event>` | upstream playlist advancing? (§8) |
| `/api/freeze?u=<event>` | records a user-reported freeze |
| `/api/candidates?u=<event>` | every feed for that game with resolution, best first |
| `/watch?u=<iframe>` | 16:9 sandboxed shell around the player (§7) |

Every response is sent with `Cache-Control: no-store` and the page appends `?_=<ts>`. Without
that, a browser will happily serve yesterday's "0 games" from cache and the fix looks broken.

---

## 4. The relay (`relay.py`) — quality and stutter

Two separate problems, one process.

### 4.1 Force the best rendition

A master playlist can list several renditions; the CDN decides which one a player gets, and
many players start low. The relay parses the master itself and keeps only the best:

```python
variants.sort(key=lambda v: (v["h"], v["bw"]), reverse=True)
self.url = variants[0]["url"]
```

Verified on a feed whose first-listed variant is 640x360:

```json
{"url": ".../tracks-v1a1/mono.m3u8", "w": 1920, "h": 1080,
 "bw_mbps": 8.25, "fps": "59.940", "seg_MB": 4.92, "fetch_Mbps": 74.17}
```

### 4.2 Buffer ahead so a slow segment cannot freeze the picture

The upstream hands out segments on its own schedule; one slow segment stalls the player
because it has nothing else to show. The relay keeps a rolling window fully downloaded and
publishes a segment only once it is complete in RAM.

```python
# window/2 pacing, parallel fetch, retry with backoff, skip rather than stall
for seq in missing:
    futures = {pool.submit(grab, s): s}
...
while len(self.segs) > self.window:
    old, _ = self.segs.popitem(last=False)
```

Measured on the main channel:

| Metric | Value |
|---|---|
| Window | 10–12 segments (~60–85 s of video) |
| Upstream pull | **65–206 Mbps** on a 6–8 Mbps stream |
| Local read speed | ~2,600 Mbps (RAM) |
| Segments served 404 | 0 |

The player never touches the internet; it reads localhost. Bad segments are retried and then
skipped, so the picture degrades by one segment instead of freezing.

### 4.3 Keep-alive matters

`urllib` opens a new socket per request. For 6-second segments that is measurable waste, so
the relay uses `requests.Session` with a pooled adapter (`pool_maxsize=24`), which is what
turns "parallel prefetch" into aggregate throughput rather than connection churn.

---

## 5. The game list: joining streams to a schedule

```python
rows = schedule_window()            # ESPN, day-2 .. day+3
for g in scraped:                   # aggregator JSON-LD
    away, home = slug_match(g["url"])        # "oklahoma-city-thunder" ...
    hit = match_espn(away, home, rows)       # last word of displayName in slug
    if hit:
        g["date"], g["time"] = hit["date"], local_time(hit["start_utc"])
        g["state"] = hit["state"]            # in / post
```

Two deliberate properties:

1. **A scheduled game with no stream still appears**, greyed, badge `none`. The list is a
   mirror of the schedule, so it cannot silently shrink.
2. **Finished games are marked `final` and refuse to open.** Before this, clicking a 7 PM game
   at 11 PM produced a spinner and a dead player, which reads as a bug.

Sorting is by `start_utc`, not by the formatted time string — `10:00 PM` sorts before
`7:00 PM` alphabetically, which put the last game first.

---

## 6. Quality probing without downloading the stream

`probe_play.py` answers one question: **is this page actually showing moving video right
now?** It matters because a feed page can load, look fine, and show a black box.

The naive check does not work:

```js
document.querySelectorAll('video')   // 0 elements on the event page
```

The player lives in a nested frame (`topstreamer.site/iframe/nba/clippers`), so the probe
walks **every frame**:

```python
for f in page.frames:
    st = f.evaluate(JS_STATE)        # readyState >= 2 and currentTime > 0.3
```

A good feed answers in **2–3 seconds**; a dead one is abandoned at the timeout. The probe also
returns the winning frame's URL, which is the anchor for §7.

### 6.1 Three probe traps

| Trap | Symptom | Fix |
|---|---|---|
| Only the top document is checked | `video: none` on a page that is playing | iterate `page.frames` |
| Bundled Chromium has no H.264 | `readyState` never reaches 2 | probe headless is fine for *presence*; judge decode on a real browser |
| Site-side refusal text | page shows "not available here" | match the refusal string and fail fast |

---

## 7. Showing a player you do not own

### 7.1 The site forbids embedding

```text
x-frame-options: SAMEORIGIN
content-security-policy: frame-ancestors 'self'
```

A browser enforces this; no script can opt out. But the **player host** inside it is a
different origin, and it does not set those headers:

```text
topstreamer.site/iframe/nba/clippers     ->  200, no XFO, no CSP frame-ancestors
```

So: extract that URL from the page, embed *that*, and the aggregate site — with its nav bar,
chat column and "CLEAR CACHE · TRY A VPN" banner — is never loaded at all.

### 7.2 Re-hosting the page itself fails, and why it is worth knowing

Proxying the aggregate page through the hub (strip XFO, inject `<base>`, patch `fetch`/XHR)
renders a page that says:

> **This stream is not available here.** It plays only on the sites it was made for.

The player checks its own origin. Do not spend time on this; go to §7.1 instead.

### 7.3 A shell that is only the video

`hub.py` serves `/watch?u=<iframe>`:

```html
<iframe src="…" allow="autoplay; fullscreen; encrypted-media"
        sandbox="allow-scripts allow-same-origin allow-forms allow-presentation"
        allowfullscreen></iframe>
```

- `width:min(100%, 100vh*16/9)` — letterboxed, so the player's white page background never
  shows as a strip under the video.
- **`allow-popups` is deliberately absent.** The ad windows are a `window.open()` in a click
  handler; without that flag the browser drops the call before any request leaves. Measured:
  6 clicks on the player, 1 blocked attempt, 0 windows, video still running.
- The ad opened in **Edge** on the test machine because popups are handed to the *default*
  browser — which is why "it has no adblocker" was true and irrelevant. Kill the popup, not
  the browser.
- A `reload stream` button re-points the iframe with a cache-buster, so a frozen feed is one
  click from recovery without losing the app state.

### 7.4 The extension that did not work

An MV3 content-script extension was written to hide nav/chat and override `window.open`.
**Chrome 137+ ignores `--load-extension` from the command line**; `--disable-features=
DisableLoadExtensionCommandLineSwitch` no longer restores it. Verified both on bundled
Chromium and on real Chrome: content script never ran (`window.__nbaClean === false`).

`clean/` is kept in the repo as a working reference for a manually-installed extension, but
the app does not depend on it. The sandbox does the popup work instead — correctly, and
without an install step.

---

## 8. The switching policy

Requested behaviour, implemented verbatim:

| Rule | Implementation |
|---|---|
| Let it play 2 minutes before judging | `Date.now() - pol.start < 120000 → hold` |
| Only switch when it is lagging | server-side stall detection, polled every 10 s |
| If it stops lagging, wait for 3 freezes | freeze counter persists; lag clears → no action |
| Better quality than what is playing → ask first | `window.confirm()` with `better.h` vs `pol.curH` |
| Worst quality + lagging → just switch | falls back to any working candidate after 3 freezes |

Lag is measured from the **upstream playlist**, not the picture:

```python
seq, _ = playlist_state(url)
if seq != h["seq"]:
    h["seq"], h["since"] = seq, now
h["lagging"] = (now - h["since"]) > 15
```

### 8.1 The honest limit

A freeze **inside** the player frame cannot be sensed by any code we run — the frame is
cross-origin and sealed. Two signals exist, and only two:

1. **Upstream stall** — automatic, from the playlist (§8).
2. **User report** — one tap on `frozen?`, which increments the counter the policy reads.

Anything else would be guessing. Saying so is more useful than shipping a watchdog that
notices nothing.

---

## 9. The Android app, built without Gradle

Gradle is not installed and does not need to be. Five tools produce a signed APK:

```powershell
$bt  = 'C:\Android\Sdk\build-tools\36.0.0'
$jar = 'C:\Android\Sdk\platforms\android-35\android.jar'

& "$bt\aapt2.exe" compile --dir res -o build\res.zip
& "$bt\aapt2.exe" link -o build\base.apk -I $jar --manifest AndroidManifest.xml `
    -R build\res.zip --java build\gen --auto-add-overlay `
    --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name 1.1

javac -source 8 -target 8 -bootclasspath $jar -classpath $jar -d build\classes @srcs
& jar cf build\classes.jar -C build\classes .
& "$bt\d8.bat" --lib $jar --min-api 24 --output build\dex build\classes.jar

# add classes.dex to the APK (aapt2 link does not do this)
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, "build\dex\classes.dex", 'classes.dex', 'Optimal')

& "$bt\zipalign.exe" -f 4 build\unsigned.apk build\aligned.apk
& "$bt\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey `
    --out NBAHub.apk build\aligned.apk
```

Result: **16 KB**, `aapt2 dump badging` clean, `apksigner verify` clean.

### 9.1 The app itself

```java
web.setWebViewClient(new WebViewClient() {          // anonymous class: needs d8 from 36
    public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isMainFrame()) showErrorPage();       // not a black screen
    }
});
s.setMediaPlaybackRequiresUserGesture(false);
web.addJavascriptInterface(bridge, "Hub");          // buttons on the error page work
```

- **Fullscreen is not free on a foldable.** `Theme.Black.NoTitleBar.Fullscreen` still leaves
  the One UI taskbar. Use `setDecorFitsSystemWindows(false)` +
  `WindowInsetsController.hide(systemBars())`, and re-apply in `onWindowFocusChanged`.
- **`resizeableActivity="true"`** + `configChanges="…|screenSize|smallestScreenSize"` so
  folding, splitting and rotating do not reload the stream.
- **Autoplay loses the user gesture.** The click handler `await`s the resolve call before
  `play()`, and Android discards the activation. Verified: `1920x1080 @ 59.940fps` in the
  status bar, `<video>` at `0:00`. Fix: start **muted** on mobile, then offer a *tap for
  sound* chip. Muted playback is allowed; unmuted from an async continuation is not.
- **Address that survives DHCP.** Default `http://<lan-ip>:8790/`, long-press to change, plus
  `10.0.2.2` / `127.0.0.1` fallbacks so the same APK works on an emulator.

### 9.2 Three build traps, all hit

| Trap | Message | Fix |
|---|---|---|
| `-source 8` + lambda | `symbol: method metafactory` | anonymous classes, or `-source 8` + no lambdas |
| `d8` from build-tools 34 | NPE on `Foo$1.class` | use build-tools **36** |
| `keytool` writes to stderr under `'Stop'` | build aborts *after* creating the keystore | `$ErrorActionPreference='Continue'` + `$LASTEXITCODE` checks |
| `d8` given 40 absolute paths with a space | `Invalid output` / NPE | `jar cf` the class tree, pass one archive |

---

## 10. Verification

### 10.1 Emulator geometry

```powershell
avdmanager create avd -n Fold7_Test -k "system-images;android-34;google_apis;x86_64" -d "8in Foldable"
emulator -avd Fold7_Test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
adb shell wm size 2184x1968       # Fold 7 inner display
adb shell wm density 420
```

### 10.2 What passed

| Check | Result |
|---|---|
| `adb install --no-incremental` | Success, `versionCode=1` |
| Launch | `topResumedActivity=com.nbahub.app/.MainActivity` |
| Fullscreen | taskbar hidden, no status bar |
| LAN fetch from app | game list rendered, `14 games · 07:34:02` timestamp |
| Channel tap | status bar `1920x1080 @ 59.940fps` |
| hls.js fetch | relay counters: `playlist_req=1`, `segment_req=3`, `404=0` |
| Survives scroll/rotate | pid stable across 6 swipes |

![app](docs/s3.png)
![player](docs/s4.png)

### 10.3 What did **not** pass — `UNVERIFIED`

`<video>` stayed at `0:00` on the emulator even after a direct tap on the play overlay,
while the manifest parsed and segments were fetched. That is a **decode** failure, and the
emulator is the prime suspect (software-rendered x86 image, `-no-audio`, no hardware codec
path). It could not be distinguished from a real device bug without a physical Fold 7.

If the picture is black on the real phone, the fix is known and scoped: **replace the WebView
with ExoPlayer/media3**, which decodes HLS natively on Samsung hardware. That requires the
media3 dependency and a real Gradle (or a manual AAR merge) — not attempted here.

### 10.4 How to reproduce the desktop path

```powershell
cd nba\hub
.\NBAHUB.bat            # restarts the server, opens a Chrome app window
```

Then click a row marked `live`. Channel rows play in-page; game rows render the sandboxed
player shell.

---

## 11. Rules that came out of this run

1. **Never infer a date from a URL.** Join to an authority (ESPN) by name. The UTC-suffix
   bug moved an entire slate by one day and looked exactly like "the app is broken".
2. **Print the fetch time in the UI.** A `14 games · 07:34:02` badge turns "is this live?"
   into a fact the user can check, and it is the cheapest trust you can ship.
3. **`Cache-Control: no-store` on every API response.** A cached "0 games" answer survives
   every code fix and makes them look broken.
4. **Look one level deeper than the page.** The playable thing was in a nested frame on a
   different host with no framing restrictions. The aggregate site was never needed.
5. **Prefer the browser's refusal over your own blocking.** Omitting `allow-popups` is worth
   more than any ad-block list, and cannot be worked around by the page.
6. **Check `page.frames`, not `document`.** A player that "doesn't exist" is usually in an
   iframe.
7. **A cross-origin frame is a closed box.** Health signals must come from upstream
   (playlist) or from the user (one tap). Do not promise a watchdog you cannot build.
8. **`adb install` without `--no-incremental` produces phantom crashes.** The app died with
   no stack trace and a stale package manager entry that later answered
   `Activity class does not exist`. Reinstall with `--no-incremental` before debugging code.
9. **Always verify against the artefact, not the intent.** `aapt2 dump badging`,
   `apksigner verify`, `wm size`, and a screenshot after every install.

---

## 12. Known limits

- **Games are DRM'd** (Widevine/PlayReady, `SAMPLE-AES-CTR`). No custom player can decode
  them. An embedded browser frame is the ceiling.
- **The desktop must be running** for the APK to work; the app is a shell, not a standalone
  client. That is a deliberate trade: all logic stays in one place and fixes ship without a
  rebuild.
- **The aggregator HTML shape is the fragile part.** If they rename the `ItemList` block, the
  game list empties — visibly, which is the failure mode to prefer.
- **`refresh.ps1` harvests nothing useful from the aggregators** (they are JS apps). It is
  still valuable for re-probing the channel list and for the iptv-org fallback.
- **Windows firewall** needs an inbound rule for 8790 on the PC:
  `netsh advfirewall firewall add rule name="NBA Hub 8790" dir=in action=allow protocol=TCP localport=8790`
  (admin). Without it the phone hangs on "connecting".
