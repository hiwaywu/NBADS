"""Does the phone's sound line up with its picture?  (no live game, no phone needed)

    python tools/av_sync/av_sync_check.py                       # the Relay.java in the tree
    python tools/av_sync/av_sync_check.py --relay old/Relay.java   # any other version

League Pass feeds keep the sound in its own playlist, so the app runs two relays
(LocalHub.startRelay) and shaka plays /relay/master.m3u8 naming both. shaka 4.7
places each playlist on the timeline by itself; when the two relays had joined
the live edge at different segments, the sound ran a whole segment (6 s on
League Pass) off the picture.

This runs the app's own Relay.java on a desktop JVM (Harness.java stands in for
LocalHub's HTTP plumbing) against a live fixture (upstream.py) built from real
ffmpeg fMP4 with separate audio, plays it with the bundled nba/hub/shaka.js in
Chromium, and reports, for every segment both halves carry, how far shaka put the
sound from the picture compared with where it truly belongs.

Needs on PATH: ffmpeg, javac/java (11+), node with the playwright package.
PASS = playback advances and every pair is within 50 ms (AAC frames are 21 ms).
Measured 2026-11-08: the 2.9 relay failed 4/6 at 2018 ms -- one 2 s fixture
segment; which cases fail moves from run to run, because it depends on where
upstream is when each relay joins -- and this tree passed 6/6 at 13-18 ms.
"""
import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
APP = os.path.join(REPO, "android", "src", "com", "nbahub", "app")

# name, --pdt, audio lag (s), audio relay start delay (ms), audio seq offset
CASES = [
    ("audio relay joins 2.5 s late, every segment stamped", "all", 0.0, 2500, 0),
    ("audio playlist published 1 s before the video one", "all", -1.0, 0, 0),
    ("only the window's first segment stamped", "first", 0.0, 2500, 0),
    ("no PROGRAM-DATE-TIME at all", "none", 0.0, 2500, 0),
    ("no PROGRAM-DATE-TIME, audio published 1 s early", "none", -1.0, 0, 0),
    ("audio numbered 1000 apart from video, stamped", "all", 0.0, 2500, 1000),
]


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def make_media(d):
    if os.path.isfile(os.path.join(d, "out_1", "index.m3u8")):
        return
    os.makedirs(d, exist_ok=True)
    print("making the fixture media (240 s, 2 s fMP4 segments, separate audio)...", flush=True)
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "240",
        "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "ultrafast",
        "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "96k",
        "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
        "-var_stream_map", "v:0,agroup:aud a:0,agroup:aud,default:yes",
        "-master_pl_name", "master.m3u8",
        "-hls_segment_filename", os.path.join(d, "out_%v", "seg%d.m4s"),
        os.path.join(d, "out_%v", "index.m3u8"),
    ], check=True)


def wait_line(proc, needle, timeout):
    end = time.time() + timeout
    while time.time() < end:
        line = proc.stdout.readline()
        if not line:
            if proc.poll() is not None:
                return False
            continue
        if needle in line:
            return True
    return False


def run_case(classes, media, page, case, secs):
    name, pdt, lag, delay, aoff = case
    up_port, h_port = free_port(), free_port()
    procs = []
    try:
        up = subprocess.Popen([sys.executable, os.path.join(HERE, "upstream.py"), media, str(up_port),
                               "--pdt", pdt, "--audio-lag", str(lag), "--audio-seq-offset", str(aoff)],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        procs.append(up)
        if not wait_line(up, "upstream on", 15):
            return {"load": "upstream did not start"}
        h = subprocess.Popen(["java", "-cp", classes, "com.nbahub.app.Harness",
                              f"http://127.0.0.1:{up_port}/master.m3u8", str(h_port), str(delay), page],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        procs.append(h)
        if not wait_line(h, "harness on", 30):
            return {"load": "harness did not start"}
        out = subprocess.run(["node", os.path.join(HERE, "measure.js"), str(h_port), media, str(secs), str(aoff)],
                             capture_output=True, text=True, timeout=secs + 120)
        lines = [l for l in out.stdout.splitlines() if l.startswith("{")]
        return json.loads(lines[-1]) if lines else {"load": "measure failed: " + (out.stderr or "")[-300:]}
    finally:
        for p in procs:
            p.kill()
            p.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--relay", default=os.path.join(APP, "Relay.java"))
    ap.add_argument("--secs", type=int, default=10, help="how long each case plays")
    ap.add_argument("--media", default=os.path.join(tempfile.gettempdir(), "nbahub-avsync-media"))
    args = ap.parse_args()

    make_media(args.media)
    work = tempfile.mkdtemp(prefix="nbahub-avsync-")
    try:
        classes = os.path.join(work, "classes")
        page = os.path.join(work, "page")
        os.makedirs(classes)
        os.makedirs(page)
        shutil.copy(os.path.join(HERE, "page.html"), page)
        shutil.copy(os.path.join(REPO, "nba", "hub", "shaka.js"), page)
        src = os.path.join(work, "src")
        os.makedirs(src)
        shutil.copy(args.relay, os.path.join(src, "Relay.java"))
        c = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-d", classes,
                            os.path.join(src, "Relay.java"), os.path.join(APP, "Http.java"),
                            os.path.join(HERE, "Log.java"), os.path.join(HERE, "JSONObject.java"),
                            os.path.join(HERE, "Harness.java")], capture_output=True, text=True)
        if c.returncode != 0:
            print(c.stdout + c.stderr)
            return 2
        print(f"relay under test: {os.path.relpath(args.relay, REPO) if args.relay.startswith(REPO) else args.relay}\n")
        failed = 0
        for case in CASES:
            r = run_case(classes, args.media, page, case, args.secs)
            ok = (r.get("load") == "ok" and r.get("pairs", 0) >= 2
                  and r.get("advanced", 0) >= args.secs * 0.6 and r.get("worst_ms", 1e9) <= 50)
            failed += not ok
            detail = (f"worst {r['worst_ms']:>5} ms over {r['pairs']} pairs, played {r['advanced']} s"
                      if "worst_ms" in r else r.get("load"))
            print(f"{'PASS' if ok else 'FAIL'}  {case[0]:<52} {detail}")
            if r.get("av_align"):
                print(f"      relay: {r['av_align']}")
        print(f"\n{len(CASES) - failed}/{len(CASES)} passed")
        return 1 if failed else 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
