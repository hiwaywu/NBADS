"""Follow a game page down to the real HLS manifest.

    python harvest.py <event-or-player-url> [--timeout 30]

The chain on these mirrors is: event page -> "/main/1" player page -> an embed that
ad-wraps the player -> the embed finally XHRs the media playlist:

    https://tims-001.sytes.net/main/secure/<token>/<epoch>/nba-401909923.m3u8

That manifest answers 200 with plain (unencrypted) HLS; the segments are signed CDN
URLs. So "cracking" it is: drive a real browser until the player asks for its
playlist, then hand *that* URL to our own relay and play it in our own <video>.

stdout: one JSON object {"ok": bool, "m3u8": url, "referer": url, "source": page}
"""
import argparse
import json
import re
import sys
import time
import urllib.request

M3U8 = re.compile(r"\.m3u8(\?|$)", re.I)
KEY = re.compile(r"\.key(\?|$)|/key/", re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--timeout", type=int, default=30)
    ap.add_argument("--shot", default=None)
    args = ap.parse_args()

    from playwright.sync_api import sync_playwright

    found = []          # (url, referer), newest last
    keys = []
    with sync_playwright() as pw:
        br = pw.chromium.launch(channel="chrome", headless=False, args=[
            "--window-position=-2400,-2400",
            "--autoplay-policy=no-user-gesture-required",
            "--mute-audio",
        ])
        ctx = br.new_context(viewport={"width": 1280, "height": 800})
        page = ctx.new_page()

        def on_req(r):
            u = r.url
            ref = r.headers.get("referer", "")
            if M3U8.search(u):
                found.append((u, ref))
            elif KEY.search(u):
                keys.append(u)

        page.on("request", on_req)
        try:
            page.goto(args.url, wait_until="domcontentloaded", timeout=args.timeout * 1000)
        except Exception:
            pass
        page.wait_for_timeout(3500)

        # event pages link the actual player; follow it if we are not there yet
        if "/main/" not in page.url:
            try:
                nxt = page.eval_on_selector_all(
                    "a[href*='/main/']", "els => els.map(e => e.href)")
                if nxt:
                    page.goto(nxt[0], wait_until="domcontentloaded", timeout=args.timeout * 1000)
                    page.wait_for_timeout(3000)
            except Exception:
                pass

        # poke every player we can see, repeatedly: the ad layer has to finish
        deadline = time.time() + args.timeout
        while time.time() < deadline and not found:
            for f in page.frames:
                try:
                    f.evaluate("""() => {
                        document.querySelectorAll('video').forEach(v => {
                            v.muted = true; try { v.play(); } catch(e){}
                            v.click();
                        });
                        document.querySelectorAll('button,[class*=play],#play,[onclick]').forEach(b => {
                            try { b.click(); } catch(e){}
                        });
                    }""")
                except Exception:
                    pass
            page.wait_for_timeout(1200)

        if args.shot:
            try:
                page.screenshot(path=args.shot)
            except Exception:
                pass
        src = page.url
        br.close()

    # the token rotates; keep the last few, newest first
    uniq = []
    for u, ref in reversed(found):
        if not any(u == seen for seen, _ in uniq):
            uniq.append((u, ref))

    # Not every mirror is playable by us. NBA's own CDN hands out
    # ".../hls-wvpr/..." manifests whose segments are Widevine (SAMPLE-AES): the
    # playlist downloads fine, the picture does not decode without a CDM. Check
    # each candidate and prefer one that is plain.
    def looks_drm(url, body):
        blob = (url + " " + (body or "")).lower()
        return ("hls-wvpr" in url.lower() or "widevine" in blob
                or "sample-aes" in blob or "com.widevine" in blob)

    best = None
    checked = []
    for url, ref in uniq[:5]:
        body = ""
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=8) as r:
                body = r.read(200000).decode("utf-8", "replace")
        except Exception as e:
            checked.append({"url": url[:90], "error": str(e)[:80]})
            continue
        drm = looks_drm(url, body)
        checked.append({"url": url[:90], "drm": drm, "bytes": len(body)})
        if not drm:
            best = (url, ref)
            break
        if best is None:
            best = (url, ref)          # all DRM so far: keep the first as fallback
    all_drm = bool(checked) and all(c.get("drm") for c in checked if "drm" in c)
    print(json.dumps({
        "ok": bool(best and best[0]),
        "m3u8": best[0] if best else None,
        "referer": (best[1] if best else "") or "",
        "source": src,
        "drm": all_drm,
        "checked": checked,
        "alternates": [u for u, _ in uniq[1:5]],
        "keys": keys[:4],
        "seen": len(found),
    }, indent=2))


if __name__ == "__main__":
    sys.exit(main())
