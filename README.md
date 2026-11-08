# NBADS â€” NBA stream hub + Android shell

A local hub that lists tonight's NBA games from the real schedule, finds a stream for the one
you click, and plays it with the highest quality the source offers â€” buffered locally so it
cannot stutter, and wrapped so you never see the host site's nav bar, chat column or ads.

Two pieces:

| Piece | Runs on | What it does |
|---|---|---|
| `nba/` + `pc/` | Windows PC (Python 3.11+) | schedule sync, feed probing, HLS relay, browser UI, 20 KB `.exe` launcher |
| `android/` | any Android 7+ phone | **standalone** 180 KB app: the hub and relay run inside it, no PC needed |

The phone app no longer needs the PC: it carries the same hub (schedule, stream sites, the
buffering relay) and serves the same page to itself on `127.0.0.1`. The PC app is unchanged.

---

## Quick start (PC) â€” the app

```
nba\hub\NBAHub.exe
```

Double-click it. It starts the hub if it is not already running, waits until it answers, and
opens a **chromeless chromium window** (Chrome or Edge, `--app`, private profile) showing the
list. That window *is* the app: no tabs, no address bar, its own icon in the taskbar.

### Is the exe standalone? No â€” and here is the honest dependency list

`NBAHub.exe` is 23 KB. It is a **launcher**, not the app. It needs, in the same tree:

| Needs | Why | Size |
|---|---|---|
| `hub.py`, `index.html`, `hls.min.js`, `probe_play.py`, `find_stream.py` in `nba\hub\` | the server and the page | ~0.5 MB |
| `relay.py`, `feeds.txt` in `nba\` | the buffering relay and the channel list | ~20 KB |
| **Python 3.11+** on PATH | runs the server and the relay | 23 MB |
| **`requests` + `urllib3`** in that Python | `relay.py` pulls the stream with them | 3 MB |
| **Chrome / Chromium / Firefox / Brave** | the app window is that browser in `--app` mode | already on the PC |
| **.NET Framework 4.x** | what `NBAHub.exe` itself is compiled against | built into Win10/11 |
| *optional:* `playwright` + its chromium | game probing only (`/api/resolve`); without it channels are unaffected and games still open in the browser | ~200 MB |

### The portable bundle â€” no Python needed on the target PC

`releases/NBAHub-portable.zip` (**11.8 MB**, 23.7 MB extracted) contains its own interpreter:

```text
START.bat                  double-click this
README-PORTABLE.txt        what is inside, what it needs, troubleshooting
python\                    Python 3.13.7 embeddable + requests/urllib3 (23 MB)
nba\hub\NBAHub.exe         the launcher (23 KB)
nba\hub\hub.py â€¦           server, page, hls.min.js, probes
nba\relay.py, feeds.txt    relay + channel list
```

The launcher looks for `..\..\python\pythonw.exe` first (`using the bundled interpreter` in
`launcher.log`), so a PC with no Python at all runs it. What it still wants: a browser and
.NET 4.x â€” both effectively part of Windows. Playwright is not bundled; add it with
`python\python.exe -m pip install playwright` if you care about probing game pages.

Unzip somewhere writable (Desktop, Documents â€” **not** Program Files: the window profile is
written next to the exe).

**Close the window and everything stops** â€” the relays and the Python server are shut down via
a loopback-only `/api/shutdown`. Nothing is left running in the background.

```text
NBAHub.exe                0.0.0.0:8790 (phone can still connect), app window
NBAHub.exe --local        bind 127.0.0.1 only
NBAHub.exe --port 8791    another port
NBAHub.exe --size 1920x1080
NBAHub.exe --dir PATH     where hub.py lives (default: next to the exe)
```

Details worth knowing:

- **Which browser.** Chrome first, Firefox second, Brave, and **Edge only if it is the last
  chromium installed** â€” Edge is the Windows default, and it kept opening when something asked
  for "the browser". The launcher logs its choice:
  `browser=chrome exe=C:\Program Files\Google\Chrome\Application\chrome.exe`.
  Force another one either way:
  - `NBAHub.exe --browser firefox`
  - or drop a `browser.txt` next to `NBAHub.exe` holding `chrome`, `firefox`, `edge`, `brave`
    or a full path to an `.exe`
  Chrome/Brave/Edge get a chromeless `--app` window; Firefox gets its own profile and instance
  (`-no-remote -new-instance -profile appprofile -new-window â€¦`) because it has no app mode.
  `/api/ping` reports the choice (`"browser":"chrome"`), and games opened from the PC go
  through the same function.
- **On the phone** the game hand-off is pinned to Chrome (`com.android.chrome`), then Firefox
  (`org.mozilla.firefox`), then a chooser â€” it no longer follows the system default (which is
  Edge on many phones). That needed a `<queries>` block in the manifest: Android 11+ hides
  installed packages, so `getLaunchIntentForPackage("com.android.chrome")` silently returned
  null without it.
- If a hub is **already running** (started by `NBAHUB.bat`, or a second double-click) the app
  reuses it, opens another window, and does **not** stop it on close â€” the owner keeps owning.
- Log of every decision: `%LOCALAPPDATA%\NBAHubPC\launcher.log`.
- Build/rebuild it: `powershell -ExecutionPolicy Bypass -File pc\build-pc.ps1` (uses the .NET
  Framework compiler that ships with Windows â€” no Visual Studio, no NuGet).
- No Chrome, Firefox or Edge installed? It falls back to your default browser plus a small
  window that keeps the server alive until you close it.

`NBAHUB.bat` still works and now just hands over to `NBAHub.exe` when it is present.

## What the page does (same in the app window or a browser)

- **Games** - click a row marked `live`. The hub resolves the feed, then serves the
  site's own player page to the app window through `/play`: sandboxed to 16:9, ad-free,
  popup-proof (a `window.open` from the player becomes the picture in the same frame),
  and with its licence POST proxied so Widevine can actually decrypt it. See
  [Games play in the app window](#games-play-in-the-app-window-25).
- **NBA TV** â€” always-on channels. These play *inside* the page through the local relay:
  1080p60, ~60 s buffered, 60â€“200 Mbps pull.
- **latency** â€” the bar shows `14.2s behind live` (straight from hls.js). Measured on this PC:
  **~0.4â€“1.2 s from click to first frame**, 14.6 s behind live by default, **8.2 s with
  `low delay`**, zero stalls in a 60 s sample. The relay fetches ahead of the player over 6
  parallel keep-alive sockets, so the picture reads from localhost.
- **Galaxy Z Fold** — cover screen: phone layout. Inner screen upright (842×934): full-width
  picture over the list. Turned (934×842): list left, player right, and the control bar wraps
  so nothing falls off the edge. Folding/unfolding mid-stream does not restart playback.
- **fullscreen** â€” the picture is letterboxed to the stream's own ratio, never stretched:
  measured 1680x945 on a 16:10 1680x1050 screen (16:9 stream), and 1080x609 on a 1080x2400
  phone. `âœ• exit full` fades out after ~2.6 s of quiet and comes back on a touch (the touch is forwarded out of
  the player frame, so tapping the picture counts too). While fullscreen is on, the phone's own
  control bar -- the progress row that used to sit there forever -- is switched off. Esc works
  too, and Back on the phone.
- Keys on the PC: `f` fullscreen, `m` mute, `r` reload the feed (plus Esc to leave fullscreen).
- `frozen?` â€” one tap logs a freeze so the switching policy can act; a cross-origin frame
  cannot be watched by code.
- `reload stream` â€” reconnect the same feed without losing state.
- **long-press the `nba hub` title** â€” diagnostics: secure context, hls.js, Widevine/EME,
  relay state, video readyState. This is the first thing to look at when a stream is black.

CLI equivalents live in `nba/`: `NBA.bat` (channel in VLC), `GAMES.bat` (game list),
`CHECK.bat` (resolution + throughput per feed), `REFRESH.bat` (re-harvest).

Plain browser, no exe: `cd nba\hub; .\NBAHUB.bat` starts the server and opens a Chrome app
window; or open `http://127.0.0.1:8790/` yourself.

## Quick start (Android) — standalone, no PC

Install `releases/NBAHub.apk` and open it. That is all: no PC, no wifi pairing, works on
mobile data too.

> Upgrading: **uninstall the old app first.** 2.0, 2.1 and 2.5 are built with different
> signing keys (the 2.5 APK in this repo was built here, with a fresh `android/debug.keystore`),
> so Android refuses an in-place update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Keep the old
> build around if you need to go back -- that one needs its own key to install, so uninstall
> between them either way. The last 2.0
> build is kept as `releases/NBAHub-2.0-oldkey.apk` if you need to go back -- that one still
> needs its own key to install, so uninstall between them either way.

What runs inside the APK (`android/src/com/nbahub/app/`):

| File | Port of | Does |
|---|---|---|
| `LocalHub.java` | `hub.py` | HTTP server on `127.0.0.1:8790` (loopback only — nothing on the wifi can reach it), serves the bundled `index.html` + `hls.min.js`, answers the same `/api/*` the page already calls |
| `Games.java` | `hub.py` | ESPN schedule (today −3…+10, any season date on demand), methstreams/crackstreams `ItemList`, joined by team name — same UTC-date fix |
| `Relay.java` | `relay.py` | forces the top rendition, RAM ring + disk tier in the app cache (wiped on stop), 6 parallel pulls newest-first, served under `/relay/` |
| `MainActivity.java` | — | WebView, video fullscreen, games handed to Chrome/Firefox, `⌂` chip back |

The page is the **same file** as the PC one, so mirror failover, `low delay`, pause = DVR,
`frozen?`, the latency pill and long-press diagnostics all work on the phone.

Better than the 1.x relay, on both counts it used to drop:

- `#EXT-X-DISCONTINUITY` is kept — ad splices no longer freeze the picture
- `#EXT-X-KEY` (AES-128) is kept and the key is proxied, so encrypted feeds play
- the playlist is only published once the joining batch is complete (newest-first downloads
  used to make the first playlists start *earlier* on every refresh — a cold start on the
  Amagi mirror failed on that)
- a mirror that refuses to start no longer stops the one that is playing

**Channel list updates itself.** `feeds.txt` is bundled, then refreshed from
`raw.githubusercontent.com/hiwaywu/NBADS/main/nba/feeds.txt` on every start (and on
`reload list`). Fix a dead mirror by editing that file on `main` — no APK rebuild. An optional
third column sets a Referer for feeds that need one: `NAME | URL | https://referer/`.

**Games play inside the app** -- and on the phone the app now tries to make the picture its
own before it navigates anywhere: `/api/harvest` walks the wrapper chain server-side, takes
the manifest, and `/api/play` hands it to the relay, so the video is our `<video>` with no
player page, no popups and no ad layers. When the chain does not yield a playable manifest,
it falls back to the site's own player page, and keeps the window the
player asks for (`window.open`) in the app instead of dropping it. A `<iframe id="streamIframe">`
and a click that hands the picture to another host of the same site are no longer
mistaken for an ad, and neither is a `window.open()`: a navigation or a popup is accepted
only when it is the same host, the same site, a host this session already loaded, or a URL
that names a player (`/main/`, `/embed`, `/player`, `/live`, `/secure/`, `.m3u8`) -- a tap
is not a licence to leave the app, because the overlay an ad paints over the picture is a
full-screen link that looks like a play button. `⌂` (or Back) returns to the list; the
chip under it is on every page that is not the hub, appears as soon as a page starts
loading, names the browser that is actually installed (Chrome / Firefox / Brave) and reopens
the same page there when the WebView has no key system at all -- the one case this
app cannot fix, because a phone without a Widevine CDM has nothing to decrypt with.

### Proving it on a phone, or on an emulator

The app can be put in front of any page from adb, which is how the in-app playback path is
tested without waiting for a game:

```powershell
adb shell am start -n com.nbahub.app/.MainActivity --es url https://host/main/1
python tools/verify_android_inapp.py      # 14 checks, needs a booted device/emulator
```

`verify_android_inapp.py` starts `tools/fixture_stream.py` on `0.0.0.0` (an emulator reaches
the host as `10.0.2.2`), installs the APK, and drives the fixture's player page -- the one
that puts its media in a `window.open()` window. It then reads the evidence from `logcat`
(`popup kept in-app: ...`, `page finished: .../popup`) and from the page itself, which beacons
its own `<video>` state back to the fixture (`readyState 4`, 1920x1080, currentTime advancing,
UA `NBAHubApp/2.1`) -- a WebView on a phone cannot be inspected from a PC otherwise. Last
measured on a Pixel 7 / Android 15 emulator: **14/14**, screenshot in
`docs/android_inapp_popup.png`. It also proves the ad gate holds, on the exact shapes that
broke 2.4: an ad's `window.open()` is dropped (`popup dropped (not the player)`), the app stays
on the player while the video keeps decoding, a tap on an ad overlay is refused
(`nav blocked (not the player)`), and a script-driven jump to an unknown host is refused too.

On the phone, a game opens its first `Main N` page whose HTML names a player frame, without
probing it — there is no headless browser to probe with.

Data use: the relay pulls the stream's own bitrate (~5 Mbps for 1080p, ~2.2 GB/hour) plus a
short burst to fill the buffer. It stops by itself 3 minutes after the player stops asking.

Build it yourself (no Gradle, no Android Studio):

```bash
# Linux / macOS
ANDROID_SDK=~/Android/Sdk ./android/build.sh        # build-tools 36 + platforms/android-35 + JDK 17+
```
```powershell
# Windows
cd android; .\build.ps1
adb uninstall com.nbahub.app          # after any keystore change
adb install --no-incremental android\NBAHub.apk
```

Deep link straight into a channel: `adb shell am start -n com.nbahub.app/.MainActivity --es chan 1`.


## Games play in the app window (2.5)

The old behaviour -- a game opened its stream in its own player window, and when that window
stayed black you pressed the browser button -- came from four gates. Every one of them was
ours, not the site's:

| Gate | What actually happened | What the hub does now |
|---|---|---|
| `POST /ext` | `BaseHTTPRequestHandler` answers **501** to any method it has no `do_*` for, so the Widevine licence challenge died at the hub and the decoder never got a key. The page loaded, the poster showed, the picture did not. | `do_POST` / `do_PUT` / `do_PATCH` / `do_DELETE`, binary body (chunked too), the real upstream status, `Content-Range`, `Accept-Ranges`, and `Set-Cookie` rewritten for the hub host |
| upstream identity | every proxied request left as `127.0.0.1` with no `Origin` -- exactly the string player hosts refuse | `Referer` and `Origin` are rewritten to the site's own page, never to the hub; `document.referrer` inside the page answers with it as well |
| nested player frame | the player's own `<iframe>` loaded straight from the site, so its `X-Frame-Options` and embedder check applied in full | every `<iframe>` / `<frame>` / `srcdoc` is rehosted through `/play` (absolute URLs, because the page's own `<base>` would otherwise resolve a relative one against the *site*) |
| `window.open()` | `#pf` is sandboxed, so the popup is refused; on the phone the popup was dropped outright and the screen stayed black | the frame's `window.open` posts the URL to the page, which loads it in the same frame -- no second window; on Android `onCreateWindow` hands the popup's first URL to the main WebView |
| which popup | 2.4 honoured **every** `window.open()`: an ad calls it far more often than a player does, so the player frame / main WebView became an ad landing page and the video disappeared | a popup is only taken when it names a player -- same site, a host this session already loaded, or a path with `/main/`, `/embed`, `/player`, `/live`, `/secure/`, `.m3u8`, `.mpd`. Everything else is dropped and logged |
| a tap | 2.4 let any tapped navigation through, and the overlay an ad paints over the picture is a full-screen link | same host / same site / known host / player-shaped URL, or refused -- `nav blocked (not the player)` |

Two smaller things ride along: the picture is no longer clicked 20 times by the autostart
script (on these players a click on the video is a pause toggle, which is how an "autostart"
used to end up on a black frame), and `#pf` now watches its own frame -- if a page navigates
itself to an ad wall, the last proxy URL is put back (three times, then it gives up rather
than loop).

### Proving it without a live game

```text
python tools/fixture_stream.py       # a mirror that refuses foreign referers, sets
                                     # X-Frame-Options, posts a binary licence, frames a
                                     # nested player, runs a frame-buster, calls window.open
python tools/verify_inapp_game.py    # 26 checks against the real --app window (CDP)
python tools/build_portable.py --zip # releases/NBAHub-portable.zip (own Python 3.13)
```

`verify_inapp_game.py` starts the hub, starts the fixture, launches Chrome with the
launcher's own command line and asserts, in the order the old code failed: the fixture
refuses a foreign referer (403 "This stream is not available here") and serves its own; the
app window has `com.widevine.alpha`; `POST /ext` reaches upstream as a POST with its 8-byte
body and the site's `Origin`/`Referer`; the nested player frame is served by `/play` and
plays (1920x1080, `readyState 4`, currentTime advancing); the frame-buster's top-navigation
is refused by the sandbox; `document.referrer` is the site; `window.open()` becomes the
picture in the same frame with **no second window**; and the app window's own URL never
changes. Last measured: **26/26** on the PC, and **14/14** on the phone (Pixel 7 / Android 15
emulator, the APK from the release) with the ad shapes included -- an ad's `window.open()`
dropped, the picture still running, a tap on an ad overlay refused.

## The picture without the page, and the sound with it (2.6)

2.5 made the site's own player page behave *inside* the app. 2.6 stops opening that page at all: the
hub resolves the feed itself and hands the media playlist to the app's own player.

The chain a "Main 1" link hides is walked one plain GET at a time (`event page -> /main/N -> wrapper
-> wrapper`) until a page names its manifest -- `globalurl`, `DIRECT_STREAM_URL`, or a hard-coded
`.m3u8`. Iframes are **ranked** before being followed: the first frame on those event pages is the
site's *chat widget*, and chasing it is why earlier attempts reported "no stream" while the player
sat right there. An `HTTP 403` from Cloudflare on a JVM fetch is answered by rendering that single
page in a full-size, black-scrimmed, ad-blocked invisible WebView and handing back only its text.

Two details decide whether the picture actually appears:

| Detail | What it costs if missed |
|---|---|
| `&sessionId=<guid>` on the League Pass manifest | the Akamai edge answers **400** to the URL it just handed out; it reads as a dead stream |
| the leaked `masterinf = {"<kid>":"<key>"}` pair | the feed is CENC; hls.js asks **Widevine** for a license that does not exist (`keySystemNoKeys`), so the app bundles **shaka-player** and plays it with `drm.clearKeys`, exactly like the provider's own page |

**The PC walks the same chain.** The Java walker in the app was ported to `hub.py` as
`harvest_http()`: plain `GET`s, iframe/rotator hops, `globalurl` / `DIRECT_STREAM_URL` /
hard-coded `.m3u8` / `.mpd`, `&sessionId=` added when the family needs it, and the
`masterinf` pair kept for `/api/clearkey`. `harvest.py` (Playwright) is now only the fallback
for a player that builds its URL on a click, so the portable bundle needs no browser at all.
The relay learned `--referer` for the same reason (`/api/play?u=...&r=<player page>`): a
harvested feed's CDN answers its own page and 403s anything else. `tools/fixture_stream.py`
grew a `/chain -> /main/7 -> /wrap2 -> /live.m3u8` fixture whose CDN refuses a request with no
referer, plus a leaked `masterinf` pair; the four checks that cover it are
`harvest: the chain walk finds the manifest...`, `...published clear-key pair...`,
`clearkey: the JWK set hls.js asks for...` and `relay: it pulls the harvested feed with that
referer`.

Sound is two separate things: the element is no longer muted by our own play path (a `sound on` /
`muted` button sits in the bar, and tapping the picture unmutes), and -- the part that was really
silent -- League Pass keeps audio in a **separate rendition**, so the relay now runs a second relay
for that playlist and serves a synthesised `/relay/master.m3u8` naming both. Measured on a live
game: `webkitAudioDecodedByteCount` 0 -> 935 061 B over ~30 s, video 1920x1080 advancing in real time.

**2.10: the sound lines up with the picture.** Two relays join the live edge independently, and
shaka puts the first segment of each playlist at time 0 -- so whenever the two joined at different
segments, the sound ran a whole segment (6 s on League Pass) ahead of or behind the picture. Both
playlists now start at the same moment of the game: matched by the feed's own
`PROGRAM-DATE-TIME` when it has one, else by media sequence, and neither is served until both have
joined (`Relay.playlist(Relay)`; `/api/relaystats` -> `av_align` says how). Checked without a game:
`python tools/av_sync/av_sync_check.py` -- the 2.9 relay fails 4/6 at 2018 ms, this one passes 6/6 at
13-18 ms.

A feed that dies no longer launders into the NBA TV channel list (the reported "it walks down the
list until it hits NBA TV"): game failover hops the game's own links, and when nothing resolves the
app shows a card with **retry** / **player page** instead of dropping the user into the ad-filled site.

Full procedure, probes and numbers: [`SOP_NBAHub_InApp_Stream_2.6.md`](SOP_NBAHub_InApp_Stream_2.6.md).

## Screenshots

| Desktop | Phone, before | Phone, playing | Phone, fullscreen |
|---|---|---|---|
| ![hub](docs/desktop_fixed.png) | ![before](docs/mobile_before.png) | ![playing](docs/mobile_channel.png) | ![fs](docs/mobile_fullscreen.png) |

## Read this before changing anything

**[docs/PROCESS.md](docs/PROCESS.md)** — the handover map: what the app is, every fix
with its measured before/after, the mirror-chain crack, the League Pass boundary, the full
verification harness and a catalogue of 21 traps. Read this one first.

**[SOP_NBAHub_Capture_Ads_And_LeaguePass.md](SOP_NBAHub_Capture_Ads_And_LeaguePass.md)** — the same
procedure in the SOP-collection house style: timeline, toolkit, the mirror chain and the
harvest algorithm, what League Pass really blocks, the four ad layers, 25 traps, and how to
publish and verify a release.

**[SOP_NBA_Stream_Hub.md](SOP_NBA_Stream_Hub.md)** â€” the full procedure, including the bugs
that cost the most time:

- the stream sites stamp games with the **UTC** date, so a 7 PM Eastern tip-off is numbered
  with tomorrow's date; the schedule must come from ESPN, not from the URL
- `d8` from build-tools 34 crashes on anonymous inner classes; use 36
- `adb install` without `--no-incremental` produces phantom app deaths
- Android's WebView drops the user gesture across `await`, so autoplay must start muted
- **a relay URL of `127.0.0.1` means "this phone", not "that PC"** â€” the hub returns the host
  the client used, the relay binds `0.0.0.0`, and that one line was the whole "the stream does
  not play" bug (SOP Â§13)

## Limits, stated plainly

- **Game streams are Widevine/PlayReady DRM**, and there are two paths to a picture.
  *Harvest first:* the phone app (and `harvest.py` on the PC) walks the `Main N` wrapper chain
  itself -- plain HTTPS GETs, no page JS, so no ad layer ever loads -- until it finds the
  manifest. When the provider also leaves the clear-key pair in the open next to it
  (`masterinf = {"kid": "key"}`), the feed is played in our own `<video>` through
  `org.w3.clearkey` and `/api/clearkey`, which answers the EME challenge with that pair: no
  license server, no player page, no ads. *Otherwise:* the site's own player page is proxied
  through `/play` with the site's `Referer`/`Origin`, its licence POST forwarded, its nested
  frames rehosted -- the picture is then decrypted by the browser's CDM, and the hub never
  touches a key. On a phone with no Widevine key system nothing can decrypt a real
  Widevine feed; the app says so and the browser chip is the way out. Channels are open
  HLS and run in our own player.
- The aggregator HTML is the fragile dependency: if their `ItemList` block changes shape, the
  game list empties â€” visibly, which is the failure mode worth having.
- A pre-game page shows a countdown, not a picture. `has a feed (opens at tip-off)` in the
  list means exactly that; the probe cannot make a stream exist before tip-off.
- Software-rendered emulators die on 1080p60 decode after a few minutes (`qemu ... 0xc0000005`
  in the Application log). That is the emulator, not the app; a real device is fine.
- Browsing the PC hub from another device (not the app) needs the PC reachable: ports `8790` and `8900-8959`. If a network is "guest /
  client-isolated", no rule helps.

## Deploying to the live install

The repo folder and the folder that actually serves the phone are different, and fixing a bug
in one while the phone talks to the other has cost real time. After any change:

```powershell
powershell -ExecutionPolicy Bypass -File tools\deploy.ps1
#   -Live "C:\AI STUFF\NBAHub"   -Port 8790   -BindHost 0.0.0.0
```

The live install lives in `C:\AI STUFF\NBAHub\` (hub files in `hub\`, `relay.py` and `feeds.txt`
one level up — exactly the layout `hub.py` expects). **Do not deploy under `C:\AI STUFF\Local\`**:
on this machine the launcher exe there exits before running a single line — no log, no window,
no AppLocker or Defender event — while the identical bytes run from `C:\AI STUFF\NBAHub\` or
`%TEMP%`. `whoami.exe` runs fine in the same folder, so it is specific to managed image
activation, and it is why the desktop shortcut now points at `C:\AI STUFF\NBAHub\hub\NBAHub.exe`.

It copies the **runtime** files only (never `browserprofile/`, `appprofile/`, logs), backs up
what it overwrites as `*.bak-<timestamp>`, stops the old hub and its relays, starts the new one
**detached from the calling shell**, and does not return until `/api/ping` answers â€” then prints
every LAN URL to type on the phone, with its adapter name.

## Layout

```text
nba/            hub, relay, probes, CLI launchers, feeds.txt
  hub/          server, UI, hls.min.js, probes, NBAHUB.bat, FIREWALL.bat,
                NBAHub.exe (the PC app, built by pc/build-pc.ps1)
pc/             NBAPC.cs (the launcher), build-pc.ps1, app.ico
tools/          deploy.ps1 (repo -> live install, restart, verify)
android/        manifest, src/ (LocalHub, Relay, Games, MainActivity), res/, build.sh, build.ps1
releases/       NBAHub.apk (standalone, signed, 180 KB); the portable bundle is a release asset
docs/           screenshots + the scripts that produced them: cdp.py (drive the
                phone's WebView over CDP), desktop_check.py, stats_check.py
                (relay -> stat pill), lag_check.py / phone_lag_check.py
                (click-to-frame + seconds behind live), fs_hub_check.py
                (fullscreen aspect), page_dump.py (what a stream page is doing)
```
