"""A live League-Pass-shaped upstream: picture and sound in separate playlists.

    python upstream.py MEDIA_DIR PORT [--pdt all|first|none] [--audio-lag S] [--audio-seq-offset N]

Segments come from an ffmpeg VOD (out_0 = video, out_1 = audio, made by
av_sync_check.py) and are published as a sliding 6-segment live window that moves
with the wall clock. The PROGRAM-DATE-TIME of a segment is its true media start,
so a player that lines the two playlists up correctly has zero A/V error.

  --pdt first           only the window's first segment is stamped (many packagers)
  --audio-lag S         the audio playlist advances S seconds after the video one
                        (negative: before) -- packagers publish them independently
  --audio-seq-offset N  the audio playlist numbers its segments N higher than the
                        video one; RFC 8216 6.3.2 lets them differ
"""
import argparse
import datetime as dt
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument("media")
ap.add_argument("port", type=int)
ap.add_argument("--pdt", default="all", choices=["all", "first", "none"])
ap.add_argument("--audio-lag", type=float, default=0.0)
ap.add_argument("--audio-seq-offset", type=int, default=0)
ap.add_argument("--start", type=int, default=10)
ap.add_argument("--window", type=int, default=6)
args = ap.parse_args()

MEDIA = os.path.abspath(args.media)
EPOCH = dt.datetime(2026, 11, 8, 0, 0, 0, tzinfo=dt.timezone.utc)
SEG = 2.0


def durations(path):
    with open(path) as fh:
        return [float(x) for x in re.findall(r"#EXTINF:([\d.]+)", fh.read())]


DUR = {"v": durations(os.path.join(MEDIA, "out_0", "index.m3u8")),
       "a": durations(os.path.join(MEDIA, "out_1", "index.m3u8"))}
DIR = {"v": "out_0", "a": "out_1"}
START = {k: [sum(d[:i]) for i in range(len(d))] for k, d in DUR.items()}
T0 = time.time()


def edge(kind):
    lag = args.audio_lag if kind == "a" else 0.0
    n = args.start + int(max(0.0, time.time() - T0 - lag) // SEG)
    return min(n, len(DUR[kind]) - 1)


def media_playlist(kind):
    e = edge(kind)
    first = max(0, e - args.window + 1)
    off = args.audio_seq_offset if kind == "a" else 0
    out = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-TARGETDURATION:2",
           f"#EXT-X-MEDIA-SEQUENCE:{first + off}",
           f'#EXT-X-MAP:URI="{DIR[kind]}/init_{DIR[kind][-1]}.mp4"']
    for n in range(first, e + 1):
        if args.pdt == "all" or (args.pdt == "first" and n == first):
            t = EPOCH + dt.timedelta(seconds=START[kind][n])
            out.append("#EXT-X-PROGRAM-DATE-TIME:" +
                       t.isoformat(timespec="milliseconds").replace("+00:00", "Z"))
        out.append(f"#EXTINF:{DUR[kind][n]:.6f},")
        out.append(f"{DIR[kind]}/seg{n}.m4s")
    return "\n".join(out) + "\n"


MASTER = """#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="a.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="aud"
v.m3u8
"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/master.m3u8":
            return self.send(200, MASTER.encode(), "application/vnd.apple.mpegurl")
        if p in ("/v.m3u8", "/a.m3u8"):
            return self.send(200, media_playlist(p[1]).encode(), "application/vnd.apple.mpegurl")
        f = os.path.normpath(os.path.join(MEDIA, p.lstrip("/")))
        if f.startswith(MEDIA + os.sep) and os.path.isfile(f):
            with open(f, "rb") as fh:
                return self.send(200, fh.read(), "video/mp4")
        self.send(404, b"not found", "text/plain")


print(f"upstream on {args.port}", flush=True)
ThreadingHTTPServer(("127.0.0.1", args.port), H).serve_forever()
