#!/usr/bin/env python3
"""
nba hub -- a local page that lists the games by date and plays the best stream.

    python hub.py [--port 8790] [--open]

Sections in the UI:
  * GAMES  -- scraped from the stream sites' NBA pages, filtered by the date you
              pick (defaults to today; rolls forward automatically when a date
              has no games). Click one -> it opens that game's best player feed
              in a dedicated app window.
  * NBA TV -- the verified always-on channels. Click one -> the local relay
              picks the highest rendition, buffers ~40 s, and hls.js plays it
              right here at full quality.
"""
import argparse
import collections
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
NBA_DIR = os.path.dirname(ROOT)
RELAY = os.path.join(NBA_DIR, "relay.py")
FEEDS = os.path.join(NBA_DIR, "feeds.txt")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

GAME_SOURCES = [
    "https://methstreams.st/nba",
    "https://crackstreams.st/nba",
]

# ------------------------------------------------------------------ helpers
def fetch(url, timeout=20, headers=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "*/*")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    return urllib.request.urlopen(req, timeout=timeout)


def find_browser():
    cands = [
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                     "Google", "Chrome", "Application", "chrome.exe"),
        os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
                     "Microsoft", "Edge", "Application", "msedge.exe"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                     "Microsoft", "Edge", "Application", "msedge.exe"),
    ]
    for c in cands:
        if os.path.isfile(c):
            return c
    return None


def find_python():
    exe = sys.executable
    return exe if exe and os.path.isfile(exe) else (shutil.which("python") or "python")


def free_port(start=8900, end=8960):
    for p in range(start, end):
        s = socket.socket()
        try:
            s.bind(("127.0.0.1", p))
            return p
        except OSError:
            continue
        finally:
            s.close()
    return 0


# ------------------------------------------------------------------ games
DATE_CACHE = {}
CACHE_TTL = 240
SOURCE_HEALTH = {}

# The stream sites stamp each event with the UTC date, which is one day ahead
# of the local game date for every evening tip-off. ESPN's schedule is the
# source of truth for "which night is this game on", so we join the streams to
# it by team name instead of trusting the URL.
ESPN_URL = ("https://site.api.espn.com/apis/site/v2/sports/basketball/nba/"
            "scoreboard?dates={}")


def espn_day(yyyymmdd):
    rows = []
    try:
        raw = fetch(ESPN_URL.format(yyyymmdd), timeout=15).read().decode("utf-8", "replace")
        data = json.loads(raw)
    except Exception:
        return rows
    for ev in data.get("events", []):
        try:
            comp = ev["competitions"][0]
        except (KeyError, IndexError):
            continue
        away = home = None
        for c in comp.get("competitors", []):
            t = c.get("team", {})
            if c.get("homeAway") == "away":
                away = t
            else:
                home = t
        if not away or not home:
            continue
        try:
            status = (comp.get("status") or {}).get("type", {}).get("state", "")
            detail = (comp.get("status") or {}).get("type", {}).get("detail", "")
        except Exception:
            status, detail = "", ""
        rows.append({
            "date": f"{yyyymmdd[:4]}-{yyyymmdd[4:6]}-{yyyymmdd[6:]}",
            "away": away.get("displayName", ""),
            "home": home.get("displayName", ""),
            "start_utc": ev.get("date", ""),
            "state": status,
            "detail": detail,
        })
    return rows


SCHED = {}
SCHED_TTL = 300


def schedule_window():
    now = time.time()
    hit = SCHED.get("w")
    if hit and now - hit[0] < SCHED_TTL:
        return hit[1]
    import datetime as _dt
    base = _dt.date.today()
    rows = []
    for off in (-2, -1, 0, 1, 2, 3):
        d = base + _dt.timedelta(days=off)
        rows += espn_day(d.strftime("%Y%m%d"))
    SCHED["w"] = (now, rows)
    return rows


def local_time(iso):
    if not iso:
        return ""
    import datetime as _dt
    try:
        s = iso.replace("Z", "+00:00")
        d = _dt.datetime.fromisoformat(s)
        if d.tzinfo is None:
            d = d.replace(tzinfo=_dt.timezone.utc)
        return d.astimezone().strftime("%-I:%M %p")
    except Exception:
        try:
            d = _dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone()
            return d.strftime("%I:%M %p").lstrip("0")
        except Exception:
            return ""


def slug_match(url):
    """'m-okc-thunder-vs-cleveland-cavaliers-1103' -> ('okc thunder', 'cleveland cavaliers')"""
    slug = url.rstrip("/").split("/event/")[-1]
    slug = re.sub(r"-\d{4}$", "", slug)
    slug = re.sub(r"^m-", "", slug)
    if "-vs-" not in slug:
        return None, None
    a, h = slug.split("-vs-", 1)
    return a.replace("-", " "), h.replace("-", " ")


def match_espn(away_txt, home_txt, rows):
    for r in rows:
        try:
            al = r["away"].split()[-1].lower()
            hl = r["home"].split()[-1].lower()
        except IndexError:
            continue
        if al and hl and al in away_txt and hl in home_txt:
            return r
    return None



def date_of(url):
    m = re.search(r"-(\d{2})(\d{2})$", url.rstrip("/"))
    if not m:
        return ""
    mon, day = int(m.group(1)), int(m.group(2))
    if not (1 <= mon <= 12 and 1 <= day <= 31):
        return ""
    year = time.localtime().tm_year
    try:
        return time.strftime("%Y-%m-%d", time.strptime(f"{year}-{mon:02d}-{day:02d}", "%Y-%m-%d"))
    except ValueError:
        return ""


def pretty(name):
    return re.sub(r"\s+", " ", name).strip()


def scrape_source(url):
    html = fetch(url).read().decode("utf-8", "replace")
    out = []
    for m in re.finditer(r'(?s)<script type="application/ld\+json">(.*?)</script>', html):
        blob = m.group(1)
        if "ItemList" not in blob:
            continue
        try:
            obj = json.loads(blob)
        except Exception:
            continue
        for it in obj.get("itemListElement", []):
            u = it.get("url", "")
            if not u:
                continue
            out.append({
                "name": pretty(it.get("name", "")),
                "url": u,
                "date": date_of(u),
                "src": urllib.parse.urlparse(url).netloc,
            })
    return out


def all_games():
    now = time.time()
    cached = DATE_CACHE.get("__all__")
    if cached and now - cached[0] < CACHE_TTL:
        return cached[1]
    rows = schedule_window()
    games, seen = [], set()
    matched = set()
    for src in GAME_SOURCES:
        try:
            scraped = scrape_source(src)
            SOURCE_HEALTH[src] = {"ok": True, "found": len(scraped), "at": time.strftime("%H:%M:%S")}
        except Exception as exc:
            SOURCE_HEALTH[src] = {"ok": False, "found": 0, "error": str(exc)[:80],
                                  "at": time.strftime("%H:%M:%S")}
            continue
        for g in scraped:
            away_txt, home_txt = slug_match(g["url"])
            hit = match_espn(away_txt or "", home_txt or "", rows) if away_txt else None
            if hit:
                g["date"] = hit["date"]
                g["time"] = local_time(hit["start_utc"])
                g["name"] = f"{hit['away']} vs {hit['home']}"
                g["verified"] = True
                g["utc"] = hit.get("start_utc", "")
                g["state"] = hit.get("state", "")
                g["detail"] = hit.get("detail", "")
                g["stream"] = True
                matched.add((hit["date"], hit["away"], hit["home"]))
            else:
                # no schedule match: fall back to the URL stamp minus one day
                d = g.get("date") or ""
                if d:
                    try:
                        import datetime as _dt
                        g["date"] = (_dt.date.fromisoformat(d) - _dt.timedelta(days=1)).isoformat()
                    except Exception:
                        pass
                g["time"] = ""
                g["verified"] = False
            key = (g["name"], g["date"])
            if key in seen:
                continue
            seen.add(key)
            games.append(g)

    # keep the list honest: every scheduled game shows up, stream or not
    for r in rows:
        key = (r["date"], r["away"], r["home"])
        if key in matched:
            continue
        if any(g["name"] == f"{r['away']} vs {r['home']}" and g["date"] == r["date"] for g in games):
            continue
        games.append({
            "name": f"{r['away']} vs {r['home']}",
            "url": "",
            "date": r["date"],
            "time": local_time(r["start_utc"]),
            "utc": r.get("start_utc", ""),
            "state": r.get("state", ""),
            "detail": r.get("detail", ""),
            "src": "espn",
            "verified": True,
            "stream": False,
        })
    DATE_CACHE["__all__"] = (now, games)
    return games


def games_for(date):
    gs = [g for g in all_games() if g["date"] == date]
    gs.sort(key=lambda g: (g.get("utc") or "9999", g["name"]))
    return gs


# ------------------------------------------------------------------ channels
def channels():
    out = []
    if not os.path.isfile(FEEDS):
        return out
    for line in open(FEEDS, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#") or "|" not in line:
            continue
        name, url = [p.strip() for p in line.rsplit("|", 1)]
        if url.startswith("http"):
            out.append({"name": name, "url": url})
    return out


# ------------------------------------------------------------------ health
# What we can actually measure from outside the player frame: whether the
# upstream playlist is still advancing and how fast segments arrive. A frozen
# picture caused by the site's own ad splice cannot be sensed by any code we
# run, so the player also exposes a one-key "frozen" report.
HEALTH = {}
HEALTH_LOCK = threading.Lock()
MASTERS = {}


def _find_master(url):
    if not os.path.isfile(FINDER):
        return None
    try:
        r = subprocess.run([find_python(), FINDER, "--url", url, "--timeout", "18"],
                           capture_output=True, timeout=60, creationflags=0x08000000)
        for line in reversed((r.stdout or b"").decode("utf-8", "replace").splitlines()):
            if line.startswith("{"):
                data = json.loads(line)
                if data.get("ok") and data.get("master"):
                    return data["master"]
    except Exception:
        pass
    return None


def master_for(page):
    """Resolve and remember the raw playlist behind a game page (cached)."""
    if page in MASTERS:
        return MASTERS[page]
    m = _find_master(page)
    if not m:
        for link in (feed_links(page) or [])[:2]:
            m = _find_master(link)
            if m:
                break
    if m:
        MASTERS[page] = m
    return m


def playlist_state(url):
    """(media sequence, segment count) of a live HLS playlist, or None."""
    try:
        txt = fetch(url, timeout=10).read().decode("utf-8", "replace")
    except Exception:
        return None
    seq = None
    n = 0
    for line in txt.splitlines():
        s = line.strip()
        if s.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            try:
                seq = int(s.split(":", 1)[1])
            except ValueError:
                pass
        elif s and not s.startswith("#"):
            n += 1
    return (seq, n) if seq is not None else None


def health_of(page):
    url = master_for(page)
    if not url:
        return {"ok": False, "reason": "no playlist found"}
    now = time.time()
    st = playlist_state(url)
    with HEALTH_LOCK:
        h = HEALTH.setdefault(page, {"seq": None, "since": now, "stalls": 0, "freezes": 0})
        if st:
            seq, n = st
            if h["seq"] is None or seq != h["seq"]:
                h["seq"] = seq
                h["since"] = now
            stalled = now - h["since"]
        else:
            stalled = now - h["since"]
        h["stalled"] = round(stalled, 1)
        h["lagging"] = stalled > 15
        if h["lagging"]:
            h["stalls"] += 1
        return {"ok": True, "lagging": h["lagging"], "stalled_seconds": h["stalled"],
                "freezes": h["freezes"], "playlist": url[:80]}

PROFILE_DIR = os.path.join(ROOT, "browserprofile")
CLEAN_DIR = os.path.join(ROOT, "clean")
PROBE = os.path.join(ROOT, "probe_play.py")
FINDER = os.path.join(ROOT, "find_stream.py")
GAME_CACHE = {}
GAME_TTL = 900


def feed_links(page):
    """All 'Main N' player links on an event page, in order."""
    out = []
    try:
        html = fetch(page).read().decode("utf-8", "replace")
    except Exception:
        return out
    for m in re.finditer(r'https?://[^"\'<>\s]+?/main/(\d+)', html):
        u = m.group(0)
        if u not in out:
            out.append(u)
    out.sort(key=lambda u: int(re.search(r"/main/(\d+)", u).group(1)))
    return out


def probe(url):
    if not os.path.isfile(PROBE):
        return {"ok": False, "reason": "probe missing"}
    try:
        r = subprocess.run([find_python(), PROBE, "--url", url, "--timeout", "10"],
                           capture_output=True, timeout=60,
                           creationflags=0x08000000)
        line = (r.stdout or b"").decode("utf-8", "replace").strip().splitlines()
        for s in reversed(line):
            if s.startswith("{"):
                return json.loads(s)
    except Exception as e:
        return {"ok": False, "reason": str(e)}
    return {"ok": False, "reason": "no output"}


def resolve_game(page):
    """Try each feed until one actually starts playing."""
    now = time.time()
    hit = GAME_CACHE.get(page)
    if hit and now - hit[0] < GAME_TTL:
        return hit[1]
    links = feed_links(page) or [page]
    tried = []
    winner = None
    for link in links[:4]:
        st = probe(link)
        tried.append({"url": link, "ok": st.get("ok"), "reason": st.get("reason")})
        if st.get("ok"):
            winner = {"url": link, "probe": st, "tried": tried}
            break
    if not winner:
        winner = {"url": links[0], "probe": {"ok": False}, "tried": tried}
    GAME_CACHE[page] = (now, winner)
    return winner


SHELL_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<title>nba</title>
<style>
  html,body{margin:0;padding:0;background:#000;overflow:hidden;height:100%}
  #wrap{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:#000}
  iframe{width:min(100%,calc(100vh * 16 / 9));height:min(100%,calc(100vw * 9 / 16));border:0;background:#000;display:block}
  #hint{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);color:#3a3a3a;
        font:13px -apple-system,"Segoe UI",sans-serif;z-index:0}
  #bar{position:fixed;top:0;left:0;right:0;height:0;z-index:9;overflow:visible}
  #reload{position:fixed;top:10px;right:12px;z-index:10;
          background:rgba(20,22,26,.86);border:1px solid #2c3038;color:#c9cdd6;
          border-radius:7px;padding:6px 11px;font:12px -apple-system,"Segoe UI",sans-serif;
          cursor:pointer;opacity:.25;transition:opacity .2s}
  #reload:hover{opacity:1;color:#fff;border-color:#3ddc84}
</style></head>
<body><div id="hint">loading stream...</div>
<div id="wrap"><iframe id="f" src="__SRC__" allow="autoplay; fullscreen; encrypted-media"
        sandbox="allow-scripts allow-same-origin allow-forms allow-presentation allow-orientation-lock"
        allowfullscreen referrerpolicy="no-referrer-when-downgrade"></iframe></div>
<button id="reload" title="stream froze? reload it">&#8635; reload stream</button>
<script>
  var f = document.getElementById('f');
  var base = f.src;
  document.getElementById('reload').addEventListener('click', function () {
    document.getElementById('hint').style.display = 'block';
    f.src = base + (base.indexOf('?') >= 0 ? '&' : '?') + '_r=' + Date.now();
  });
  f.addEventListener('load', function () {
    document.getElementById('hint').style.display = 'none';
  });
  setTimeout(function(){ document.getElementById('hint').style.display='none'; }, 8000);
</script>
</body></html>"""


def shell_page(src):
    return SHELL_HTML.replace("__SRC__", src.replace('"', "&quot;"))


# ------------------------------------------------------------------ page wrapper
# The game pages cannot be embedded (X-Frame-Options) and their chrome is
# noise: nav bars, a chat panel, "clear your cache" banners. So we serve the
# page ourselves, inject CSS that hides their furniture, and patch fetch/XHR so
# the player keeps talking to their servers through us. Same origin, no CORS,
# no ads on the parts we control.
HIDE_CSS = """
<style id="nbaclean">
  header, nav, .navbar, .site-header, .topbar, .site-nav, .menu, .nav-links,
  aside, #chat, .chat, .chat-box, .chat-panel, .live-chat, [id*="chat"],
  [class*="chat-panel"], [class*="chatbox"],
  footer, .site-footer,
  .event-mirror-banner, .notice, .warning, .alert, [class*="banner"] {
    display: none !important;
  }
  html, body { background: #000 !important; margin: 0 !important; }
  body > * { max-width: 100% !important; }
  iframe[src*="chat"], iframe[title*="chat"], iframe[title*="Chat"] { display:none !important; }
</style>
"""

PATCH_JS = """
<script id="nbacleanjs">
(function () {
  var P = location.origin + '/ext?u=';
  function wrap(u) {
    try {
      if (!u || typeof u !== 'string') return u;
      if (/^(data|blob|about|javascript):/i.test(u)) return u;
      if (u.indexOf(location.origin) === 0) return u;
      return P + encodeURIComponent(new URL(u, document.baseURI).href);
    } catch (e) { return u; }
  }
  window.__nbaWrap = wrap;
  var _fetch = window.fetch;
  window.fetch = function (input, init) {
    try {
      if (typeof input === 'string') input = wrap(input);
      else if (input && input.url) input = new Request(wrap(input.url), input);
    } catch (e) {}
    return _fetch.call(this, input, init);
  };
  var _open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    var a = Array.prototype.slice.call(arguments, 2);
    return _open.apply(this, [m, wrap(u)].concat(a));
  };
  if (navigator.serviceWorker) {
    try { navigator.serviceWorker.register = function () { return Promise.reject('blocked'); }; } catch (e) {}
  }
})();
</script>
"""


def proxy_page(url):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "text/html,application/xhtml+xml")
    req.add_header("Accept-Encoding", "identity")
    req.add_header("Referer", url)
    with urllib.request.urlopen(req, timeout=25) as r:
        raw = r.read().decode("utf-8", "replace")

    p = urllib.parse.urlparse(url)
    base = f"{p.scheme}://{p.netloc}/"
    inject = f'<base href="{base}">' + HIDE_CSS + PATCH_JS

    # never let their CSP or frame rules apply to our copy
    raw = re.sub(r'(?is)<meta[^>]+http-equiv=["\']?(content-security-policy)["\']?[^>]*>', "", raw)

    m = re.search(r"(?is)<head[^>]*>", raw)
    if m:
        raw = raw[:m.end()] + inject + raw[m.end():]
    else:
        raw = inject + raw
    return raw


# ------------------------------------------------------------------ relay pool
RELAYS = {}
RELAY_LOCK = threading.Lock()


def stop_relay(url):
    with RELAY_LOCK:
        item = RELAYS.pop(url, None)
    if not item:
        return
    proc, _port = item
    try:
        if proc.poll() is None:
            proc.terminate()
    except Exception:
        pass


def start_relay(url, low=False):
    key = url + ("#low" if low else "")
    with RELAY_LOCK:
        item = RELAYS.get(key)
        if item:
            proc, port = item
            if proc.poll() is None:
                try:
                    r = fetch(f"http://127.0.0.1:{port}/health", timeout=3)
                    if r.status == 200:
                        return port
                except Exception:
                    pass
    stop_relay(key)
    port = free_port()
    if not port:
        return 0
    # one player at a time: retire the previous feed so we do not pile up
    for other in list(RELAYS.keys()):
        if other != key:
            stop_relay(other)
    window = "5" if low else "10"
    prefetch = "3" if low else "6"
    cmd = [find_python(), RELAY, "--url", url, "--port", str(port),
           "--window", window, "--prefetch", prefetch, "--idle-timeout", "900"]
    creation = 0x08000000  # CREATE_NO_WINDOW
    proc = subprocess.Popen(cmd, cwd=NBA_DIR, creationflags=creation,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with RELAY_LOCK:
        RELAYS[key] = (proc, port)
    deadline = time.time() + 30
    while time.time() < deadline:
        if proc.poll() is not None:
            return 0
        try:
            r = fetch(f"http://127.0.0.1:{port}/health", timeout=2)
            if r.status == 200:
                return port
        except Exception:
            pass
        time.sleep(0.6)
    return 0


# ------------------------------------------------------------------ server
class Handler(BaseHTTPRequestHandler):
    server_version = "nbahub/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _static(self, name, ctype):
        for base in (ROOT, NBA_DIR, os.path.dirname(NBA_DIR)):
            path = os.path.join(base, name)
            if os.path.isfile(path):
                with open(path, "rb") as fh:
                    data = fh.read()
                return self._send(200, data, ctype, {"Cache-Control": "no-cache"})
        return self._send(404, "not found", "text/plain")

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(u.query)

        if u.path in ("/", "/index.html"):
            return self._static("index.html", "text/html; charset=utf-8")
        if u.path == "/hls.min.js":
            return self._static("hls.min.js", "application/javascript")

        if u.path == "/api/games":
            date = (qs.get("date") or [""])[0]
            if not date:
                date = time.strftime("%Y-%m-%d")
            try:
                gs = games_for(date)
                return self._send(200, json.dumps({"date": date, "games": gs,
                                                   "sources": SOURCE_HEALTH,
                                                   "checked_at": time.strftime("%H:%M:%S")}))
            except Exception as exc:
                return self._send(200, json.dumps({"date": date, "games": [], "error": str(exc)}))

        if u.path == "/api/dates":
            try:
                ds = {g["date"] for g in all_games() if g.get("date")}
                for r in schedule_window():
                    if r.get("date"):
                        ds.add(r["date"])
                return self._send(200, json.dumps({"dates": sorted(ds)}))
            except Exception as exc:
                return self._send(200, json.dumps({"dates": [], "error": str(exc)}))

        if u.path == "/api/channels":
            return self._send(200, json.dumps({"channels": channels()}))

        if u.path == "/api/play":
            target = (qs.get("u") or [""])[0]
            low = (qs.get("low") or ["0"])[0] == "1"
            if not target:
                return self._send(400, '{"ok":false,"error":"missing u"}')
            port = start_relay(target, low=low)
            if not port:
                return self._send(200, json.dumps({"ok": False, "error": "relay failed"}))
            info = {}
            try:
                info = json.loads(fetch(f"http://127.0.0.1:{port}/stats", timeout=4).read())
            except Exception:
                pass
            return self._send(200, json.dumps({
                "ok": True,
                "url": f"http://127.0.0.1:{port}/live.m3u8",
                "stats": info,
            }))

        if u.path == "/api/health":
            page = (qs.get("u") or [""])[0]
            if not page:
                return self._send(400, '{"ok":false}')
            return self._send(200, json.dumps(health_of(page)))

        if u.path == "/api/freeze":
            page = (qs.get("u") or [""])[0]
            with HEALTH_LOCK:
                h = HEALTH.setdefault(page, {"seq": None, "since": time.time(),
                                             "stalls": 0, "freezes": 0})
                h["freezes"] += 1
                n = h["freezes"]
            return self._send(200, json.dumps({"ok": True, "freezes": n}))

        if u.path == "/api/candidates":
            page = (qs.get("u") or [""])[0]
            if not page:
                return self._send(400, '{"ok":false}')
            out = []
            for link in (feed_links(page) or [page])[:3]:
                st = probe(link)
                out.append({"url": link, "ok": bool(st.get("ok")),
                            "h": st.get("h", 0), "w": st.get("w", 0),
                            "frame": st.get("frame", "")})
            out.sort(key=lambda c: (not c["ok"], -c["h"]))
            return self._send(200, json.dumps({"ok": True, "candidates": out}))
        if u.path == "/api/resolve":
            page = (qs.get("u") or [""])[0]
            if not page:
                return self._send(400, '{"ok":false,"error":"missing u"}')
            res = resolve_game(page)
            frame = (res.get("probe") or {}).get("frame") or ""
            return self._send(200, json.dumps({
                "ok": bool(res.get("probe", {}).get("ok")),
                "frame": frame,
                "source": res["url"],
                "probe": res.get("probe", {}),
            }))
        if u.path == "/watch":
            target = (qs.get("u") or [""])[0]
            if not target:
                return self._send(400, "missing u", "text/plain")
            return self._send(200, shell_page(target), "text/html; charset=utf-8")

        if u.path == "/play":
            target = (qs.get("u") or [""])[0]
            if not target:
                return self._send(400, "missing u", "text/plain")
            try:
                html = proxy_page(target)
            except Exception as exc:
                return self._send(502, f"could not load that page: {exc}", "text/plain")
            return self._send(200, html, "text/html; charset=utf-8")

        if u.path == "/ext":
            target = (qs.get("u") or [""])[0]
            if not target:
                return self._send(400, "missing u", "text/plain")
            return self._passthrough(target)

        if u.path == "/api/watch":
            page = (qs.get("u") or [""])[0]
            if not page:
                return self._send(400, '{"ok":false,"error":"missing u"}')
            res = resolve_game(page)
            target = res["url"]
            frame = (res.get("probe") or {}).get("frame") or ""
            br = find_browser()
            if not br:
                return self._send(200, json.dumps({"ok": False, "url": target,
                                                   "error": "no chrome/edge"}))
            # if we found the player host, show ONLY that, inside our own shell:
            # no site nav, no chat column, no ad overlays
            open_url = target
            if frame and "iframe" in frame:
                open_url = (f"http://127.0.0.1:{self.server.server_port}/watch?u=" +
                            urllib.parse.quote(frame, safe=""))
            args = [br, f"--app={open_url}", "--window-size=1280,820",
                    "--autoplay-policy=no-user-gesture-required",
                    "--disable-features=Translate,DisableLoadExtensionCommandLineSwitch",
                    "--no-first-run", "--no-default-browser-check",
                    f"--user-data-dir={PROFILE_DIR}"]
            try:
                subprocess.Popen(args, close_fds=True)
            except Exception as exc:
                return self._send(200, json.dumps({"ok": False, "url": target, "error": str(exc)}))
            return self._send(200, json.dumps({"ok": True, "url": open_url,
                                               "source": target, "frame": frame,
                                               "probe": res.get("probe", {}),
                                               "tried": res.get("tried", [])}))

        if u.path == "/api/open":
            target = (qs.get("u") or [""])[0]
            if target:
                webbrowser.open(target)
            return self._send(200, '{"ok":true}')

        return self._send(404, "not found", "text/plain")

    def _passthrough(self, target):
        """Fetch an upstream resource for the wrapped page (same-origin for it)."""
        req = urllib.request.Request(target)
        req.add_header("User-Agent", UA)
        req.add_header("Accept", "*/*")
        req.add_header("Accept-Encoding", "identity")
        rng = self.headers.get("Range")
        if rng:
            req.add_header("Range", rng)
        ref = self.headers.get("Referer")
        if ref:
            req.add_header("Referer", ref)
        try:
            r = urllib.request.urlopen(req, timeout=25)
            body = r.read()
        except Exception as exc:
            return self._send(502, f"upstream: {exc}", "text/plain")

        ctype = r.headers.get("Content-Type", "application/octet-stream")
        final = r.geturl()

        if "mpegurl" in ctype.lower() or body[:7] == b"#EXTM3U":
            text = body.decode("utf-8", "replace")
            out = []
            for line in text.splitlines():
                s = line.strip()
                if not s:
                    out.append(line)
                    continue
                if s.startswith("#"):
                    if 'URI="' in s:
                        head, _, rest = s.partition('URI="')
                        uri, _, tail = rest.partition('"')
                        absu = urllib.parse.urljoin(final, uri)
                        out.append(head + 'URI="/ext?u=' +
                                   urllib.parse.quote(absu, safe="") + '"' + tail)
                    else:
                        out.append(s)
                    continue
                absu = urllib.parse.urljoin(final, s)
                out.append("/ext?u=" + urllib.parse.quote(absu, safe=""))
            return self._send(200, "\n".join(out) + "\n",
                              "application/vnd.apple.mpegurl",
                              {"Cache-Control": "no-store"})

        extra = {"Cache-Control": "no-store"}
        if rng and r.headers.get("Content-Range"):
            extra["Content-Range"] = r.headers["Content-Range"]
        return self._send(200, body, ctype, extra)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8790)
    ap.add_argument("--open", action="store_true")
    ap.add_argument("--host", default="0.0.0.0", help="0.0.0.0 = reachable from your phone")
    args = ap.parse_args()

    try:
        srv = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as exc:
        print(f"port {args.port} busy: {exc}", file=sys.stderr)
        sys.exit(1)
    url = f"http://127.0.0.1:{args.port}/"
    lan = ""
    try:
        import socket as _s
        probe = _s.socket(_s.AF_INET, _s.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        lan = probe.getsockname()[0]
        probe.close()
    except Exception:
        pass
    print(f"nba hub at {url}")
    if lan and args.host == "0.0.0.0":
        print(f"phone url  http://{lan}:{args.port}/")
    if args.open:
        webbrowser.open(url)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for k in list(RELAYS.keys()):
            stop_relay(k)


if __name__ == "__main__":
    main()






