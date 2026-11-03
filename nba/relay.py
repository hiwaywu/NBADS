#!/usr/bin/env python3
"""
relay.py -- local HLS relay that (a) always picks the highest-quality rendition,
(b) buffers ahead so the player never stutters, and (c) pulls segments over a
pooled, parallel connection set so a single slow connection can't throttle you.

    python relay.py --url <MASTER_OR_MEDIA_PLAYLIST> --port 8823

Then point any player at:  http://127.0.0.1:8823/live.m3u8

Extra endpoints:
    /stats     JSON: segments cached, MB buffered, measured upstream throughput
    /health    200 once the first segments are ready
    /source    the resolved highest-bitrate rendition URL

Why this removes lag: the upstream feed hands you segments on someone else's
schedule, and a single dropped/slow segment stalls the player (it has nothing
else to show). This relay keeps a rolling window of fully-downloaded segments
and only advertises a segment in its playlist once the whole thing is in RAM, so
the player always reads from localhost at LAN speed. Slow segments are retried
and, if still bad, skipped instead of freezing playback.
"""
import argparse
import json
import math
import re
import sys
import threading
import time
import urllib.parse
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# --------------------------------------------------------------------------- #
# HTTP with connection reuse + generous pooling (this is the "more bandwidth"
# part: N parallel keep-alive sockets instead of one serial download stream)
# --------------------------------------------------------------------------- #
def make_session(pool=24):
    s = requests.Session()
    retry = Retry(total=3, backoff_factor=0.4,
                  status_forcelist=(429, 500, 502, 503, 504),
                  allowed_methods=frozenset(["GET"]))
    ad = HTTPAdapter(pool_connections=pool, pool_maxsize=pool, max_retries=retry)
    s.mount("http://", ad)
    s.mount("https://", ad)
    s.headers.update({
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Connection": "keep-alive",
    })
    return s


def fetch_text(sess, url, timeout=12):
    r = sess.get(url, timeout=timeout)
    r.raise_for_status()
    return r.text


def fetch_bytes(sess, url, timeout=20):
    r = sess.get(url, timeout=timeout)
    r.raise_for_status()
    return r.content


# --------------------------------------------------------------------------- #
# playlist parsing
# --------------------------------------------------------------------------- #
ATTR_RE = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')


def attrs(line):
    out = {}
    for k, v in ATTR_RE.findall(line.split(":", 1)[1]):
        out[k] = v.strip('"')
    return out


def parse_master(text, base):
    """Return [{url, bw, w, h, fps, codecs}] sorted best-first."""
    lines = [l.strip() for l in text.splitlines()]
    variants = []
    for i, line in enumerate(lines):
        if not line.startswith("#EXT-X-STREAM-INF"):
            continue
        a = attrs(line)
        j = i + 1
        while j < len(lines) and (not lines[j] or lines[j].startswith("#")):
            j += 1
        if j >= len(lines):
            continue
        w = h = 0
        if "RESOLUTION" in a:
            try:
                w, h = (int(x) for x in a["RESOLUTION"].split("x"))
            except ValueError:
                pass
        variants.append({
            "url": urllib.parse.urljoin(base, lines[j]),
            "bw": int(a.get("BANDWIDTH", 0) or 0),
            "abw": int(a.get("AVERAGE-BANDWIDTH", 0) or 0),
            "w": w, "h": h,
            "fps": a.get("FRAME-RATE", ""),
            "codecs": a.get("CODECS", ""),
        })
    variants.sort(key=lambda v: (v["h"], v["bw"]), reverse=True)
    return variants


def parse_media(text):
    """Return dict(seq, target, entries=[(url_rel, dur)], map_uri, endlist)."""
    lines = [l.strip() for l in text.splitlines()]
    seq = 0
    target = 6.0
    map_uri = None
    entries = []
    pending = None
    for line in lines:
        if line.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            seq = int(line.split(":", 1)[1])
        elif line.startswith("#EXT-X-TARGETDURATION:"):
            target = float(line.split(":", 1)[1])
        elif line.startswith("#EXT-X-MAP:"):
            a = attrs(line)
            map_uri = a.get("URI")
        elif line.startswith("#EXTINF:"):
            try:
                pending = float(line.split(":", 1)[1].split(",")[0])
            except ValueError:
                pending = target
        elif line and not line.startswith("#"):
            entries.append((line, pending if pending is not None else target))
            pending = None
    return {"seq": seq, "target": target, "entries": entries,
            "map": map_uri, "endlist": "#EXT-X-ENDLIST" in text}


# --------------------------------------------------------------------------- #
# relay core
# --------------------------------------------------------------------------- #
class Relay:
    def __init__(self, url, window=12, prefetch=4, max_seconds=None, idle_timeout=180):
        self.last_hit = time.time()
        self.idle_timeout = idle_timeout
        self.sess = make_session()
        self.pool = ThreadPoolExecutor(max_workers=prefetch + 2)
        self.lock = threading.Lock()
        self.url = url                     # resolved MEDIA playlist URL
        self.source_url = url
        self.window = window               # how many segments to hold
        self.prefetch = prefetch
        self.max_seconds = max_seconds
        self.segs = OrderedDict()          # seq -> bytes
        self.durs = {}                     # seq -> duration
        self.uris = {}                     # seq -> absolute url
        self.map_uri = None
        self.map_data = None
        self.target = 6.0
        self.first_seq = None
        self.ready = threading.Event()
        self.stop = threading.Event()
        self.bytes_total = 0
        self.segs_total = 0
        self.throughput = 0.0              # bits/sec estimate
        self.last_error = ""
        self.hits = 0
        self.pl_hits = 0
        self.seg_hits = 0
        self.miss = 0
        self.variant_info = {}

    # ---- resolve master -> best rendition ----
    def resolve(self):
        txt = fetch_text(self.sess, self.url)
        if "#EXT-X-STREAM-INF" in txt:
            vs = parse_master(txt, self.url)
            if not vs:
                raise RuntimeError("master playlist had no variants")
            best = vs[0]
            self.variant_info = best
            self.url = best["url"]
            print(f"[relay] {len(vs)} rendition(s); picked "
                  f"{best['w']}x{best['h']} @ {best['bw']/1e6:.2f} Mbps")
        else:
            print("[relay] direct media playlist (single rendition)")

    # ---- background: keep a rolling window fully downloaded ----
    def pump(self):
        backoff = 1.0
        while not self.stop.is_set():
            if self.idle_timeout and (time.time() - self.last_hit) > self.idle_timeout:
                print("[relay] player idle too long, shutting down")
                self.stop.set()
                break
            try:
                txt = fetch_text(self.sess, self.url)
                m = parse_media(txt)
                self.target = max(1.0, m["target"])
                base = self.url
                if m["map"] and not self.map_data:
                    self.map_uri = urllib.parse.urljoin(base, m["map"])
                    self.map_data = fetch_bytes(self.sess, self.map_uri)
                new = {}
                for i, (rel, dur) in enumerate(m["entries"]):
                    seq = m["seq"] + i
                    new[seq] = (urllib.parse.urljoin(base, rel), dur)
                with self.lock:
                    for seq, (u, d) in new.items():
                        self.uris[seq] = u
                        self.durs[seq] = d
                # fetch everything missing, in parallel
                with self.lock:
                    missing = [s for s in new if s not in self.segs]
                if missing:
                    t0 = time.time()
                    total = 0
                    futs = {self.pool.submit(self.grab, s): s for s in missing}
                    for f in as_completed(futs):
                        seq = futs[f]
                        try:
                            data = f.result()
                        except Exception as e:
                            self.last_error = f"seg {seq}: {e}"
                            continue
                        with self.lock:
                            self.segs[seq] = data
                            self.bytes_total += len(data)
                            self.segs_total += 1
                            while len(self.segs) > self.window:
                                old, _ = self.segs.popitem(last=False)
                                self.durs.pop(old, None)
                                self.uris.pop(old, None)
                        total += len(data)
                    dt = max(0.001, time.time() - t0)
                    self.throughput = (total * 8) / dt
                    print(f"[relay] +{len(missing)} seg  "
                          f"{total/1e6:.2f} MB in {dt:.2f}s  "
                          f"=> {self.throughput/1e6:.1f} Mbps upstream")
                    if len(self.segs) >= 3:
                        self.ready.set()
                if self.max_seconds and self.segs_total * self.target > self.max_seconds:
                    print("[relay] duration cap reached")
                    self.stop.set()
                # pace: wake up roughly every half segment
                wait = min(max(self.target / 2, 0.5), 3.0)
                if m["endlist"]:
                    wait = 0.5
                if missing:
                    wait = 0.05
                time.sleep(wait)
                backoff = 1.0
            except Exception as e:
                self.last_error = str(e)
                print(f"[relay] playlist error: {e} (retry in {backoff:.1f}s)")
                time.sleep(backoff)
                backoff = min(backoff * 2, 8.0)

    def grab(self, seq, tries=3):
        url = self.uris[seq]
        last = None
        for i in range(tries):
            try:
                return fetch_bytes(self.sess, url)
            except Exception as e:
                last = e
                time.sleep(0.3 * (2 ** i))
        raise last

    # ---- what the player sees ----
    def playlist_text(self):
        with self.lock:
            seqs = sorted(self.segs.keys())
        if len(seqs) > self.window:
            seqs = seqs[-self.window:]
        if not seqs:
            return None
        first = seqs[0]
        tgt = int(math.ceil(max(self.durs.get(s, self.target) for s in seqs)))
        out = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{tgt}",
               f"#EXT-X-MEDIA-SEQUENCE:{first}"]
        if self.map_data:
            out.append('#EXT-X-MAP:URI="/init.mp4"')
        for s in seqs:
            out.append(f"#EXTINF:{self.durs.get(s, self.target):.3f},")
            out.append(f"/seg/{s}.ts")
        return "\n".join(out) + "\n"

    def segment(self, seq):
        with self.lock:
            d = self.segs.get(seq)
        if d is not None:
            return d
        return self.grab(seq)

    def stats(self):
        with self.lock:
            n = len(self.segs)
            b = sum(len(v) for v in self.segs.values())
        return {
            "segments_cached": n,
            "buffered_MB": round(b / 1e6, 2),
            "buffered_seconds": round(n * self.target, 1),
            "upstream_Mbps": round(self.throughput / 1e6, 2),
            "variant": self.variant_info,
            "source": self.source_url,
            "resolved": self.url,
            "last_error": self.last_error,
            "requests": self.hits, "playlist_req": self.pl_hits,
            "segment_req": self.seg_hits, "segment_404": self.miss,
        }


def make_handler(relay):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        # VLC hangs up on keep-alive sockets all the time; that is not an error
        def handle_one_request(self):
            try:
                super().handle_one_request()
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
                self.close_connection = True

        def handle(self):
            try:
                super().handle()
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
                pass

        def log_message(self, *a):
            pass

        def _send(self, code, body, ctype="text/plain; charset=utf-8", extra=None):
            if isinstance(body, str):
                body = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            relay.last_hit = time.time()
            relay.hits += 1
            if path in ("/live.m3u8", "/", "/index.m3u8"):
                relay.pl_hits += 1
                txt = relay.playlist_text()
                if not txt:
                    return self._send(503, "#EXTM3U\n#EXT-X-TARGETDURATION:2\n")
                return self._send(200, txt, "application/vnd.apple.mpegurl",
                                  {"Cache-Control": "no-cache"})
            if path == "/health":
                return self._send(200 if relay.ready.is_set() else 503,
                                  "ready" if relay.ready.is_set() else "warming")
            if path == "/stats":
                return self._send(200, json.dumps(relay.stats(), indent=2),
                                  "application/json")
            if path == "/source":
                return self._send(200, relay.url + "\n")
            if path == "/init.mp4" and relay.map_data:
                return self._send(200, relay.map_data, "video/mp4")
            m = re.match(r"^/seg/(\d+)\.(ts|m4s|mp4)$", path)
            if m:
                relay.seg_hits += 1
                seq = int(m.group(1))
                try:
                    data = relay.segment(seq)
                except Exception as e:
                    relay.miss += 1
                    return self._send(404, f"segment unavailable: {e}")
                ctype = "video/mp2t" if m.group(2) == "ts" else "video/mp4"
                return self._send(200, data, ctype, {"Cache-Control": "max-age=60"})
            return self._send(404, "not found")
    return H


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--port", type=int, default=8823)
    ap.add_argument("--window", type=int, default=12, help="segments kept buffered")
    ap.add_argument("--prefetch", type=int, default=4, help="parallel downloads")
    ap.add_argument("--max-seconds", type=int, default=None)
    ap.add_argument("--probe-only", action="store_true",
                    help="resolve best rendition, measure throughput, exit")
    ap.add_argument("--idle-timeout", type=int, default=180,
                    help="exit after N seconds with no player requests")
    args = ap.parse_args()

    relay = Relay(args.url, window=args.window, prefetch=args.prefetch,
                  max_seconds=args.max_seconds, idle_timeout=args.idle_timeout)
    try:
        relay.resolve()
    except Exception as e:
        print(f"[relay] cannot open playlist: {e}", file=sys.stderr)
        return 2

    if args.probe_only:
        txt = fetch_text(relay.sess, relay.url)
        m = parse_media(txt)
        if not m["entries"]:
            print("[relay] empty playlist", file=sys.stderr)
            return 3
        # grab the last 3 segments in parallel: average size gives an honest
        # bitrate, and the wall clock gives the aggregate bandwidth we can pull
        sample = m["entries"][-3:]
        urls = [(urllib.parse.urljoin(relay.url, rel), dur) for rel, dur in sample]
        t0 = time.time()
        with ThreadPoolExecutor(max_workers=len(urls)) as ex:
            futs = [ex.submit(fetch_bytes, relay.sess, u) for u, _ in urls]
            blobs = []
            for f in futs:
                try:
                    blobs.append(f.result())
                except Exception:
                    blobs.append(b"")
        dt = max(0.001, time.time() - t0)
        total = sum(len(b) for b in blobs)
        secs = sum(d for _, d in urls)
        v = relay.variant_info
        print(json.dumps({
            "url": relay.url, "w": v.get("w", 0), "h": v.get("h", 0), "fps": v.get("fps", ""),
            "bw_mbps": round(v.get("bw", 0) / 1e6, 2),
            "seg_MB": round(total / len(urls) / 1e6, 2),
            "seg_seconds": round(secs / len(urls), 2),
            "measured_bitrate_mbps": round((total * 8) / max(0.001, secs) / 1e6, 2),
            "fetch_Mbps": round((total * 8) / dt / 1e6, 2),
            "ok": total > 0,
        }))
        return 0 if total > 0 else 4

    th = threading.Thread(target=relay.pump, daemon=True)
    th.start()

    srv = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(relay))
    srv.daemon_threads = True
    print(f"[relay] serving http://127.0.0.1:{args.port}/live.m3u8")
    if relay.ready.wait(timeout=45):
        print("[relay] buffered and ready")
    else:
        print("[relay] warning: buffer not filled yet, player may wait")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        relay.stop.set()
    return 0


if __name__ == "__main__":
    sys.exit(main())



