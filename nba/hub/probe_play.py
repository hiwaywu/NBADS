#!/usr/bin/env python3
"""
probe_play.py -- does this stream page actually play, right now?

    python probe_play.py --url <page> [--timeout 10]

The player usually lives inside an iframe, so every frame is inspected, not
just the top document. Reports the first frame whose <video> element is really
running, or the site's "not available here" refusal.

    {"ok":true,"reason":"playing","w":1920,"h":1080,"currentTime":3.4,"frame":"..."}
"""
import argparse
import json
import sys
import time

try:
    from playwright.sync_api import sync_playwright
except Exception as e:
    print(json.dumps({"ok": False, "reason": f"playwright missing: {e}"}))
    sys.exit(0)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

JS_STATE = """() => {
  const vs = Array.from(document.querySelectorAll('video'));
  if (!vs.length) return null;
  const v = vs.map(x => ({rs: x.readyState, t: x.currentTime,
                          w: x.videoWidth, h: x.videoHeight}))
              .sort((a, b) => (b.rs - a.rs) || (b.t - a.t))[0];
  return v;
}"""

REFUSAL = ("not available here", "plays only on the sites")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--timeout", type=int, default=10)
    args = ap.parse_args()

    out = {"ok": False, "reason": "not started", "url": args.url}
    try:
        with sync_playwright() as pw:
            br = pw.chromium.launch(headless=True, args=[
                "--autoplay-policy=no-user-gesture-required", "--mute-audio"])
            ctx = br.new_context(user_agent=UA, ignore_https_errors=True,
                                 viewport={"width": 1280, "height": 720})
            page = ctx.new_page()
            try:
                page.goto(args.url, wait_until="domcontentloaded", timeout=25000)
            except Exception as e:
                out["reason"] = f"goto: {e}"
                br.close()
                print(json.dumps(out))
                return 0

            deadline = time.time() + args.timeout
            while time.time() < deadline:
                page.wait_for_timeout(1200)
                for f in page.frames:
                    try:
                        st = f.evaluate(JS_STATE)
                    except Exception:
                        continue
                    if not st:
                        continue
                    if st.get("rs", 0) >= 2 and st.get("t", 0) > 0.3:
                        out.update({"ok": True, "reason": "playing",
                                    "currentTime": round(st.get("t", 0), 2),
                                    "w": st.get("w", 0), "h": st.get("h", 0),
                                    "frame": f.url[:120]})
                        br.close()
                        print(json.dumps(out))
                        return 0
                try:
                    body = (page.inner_text("body") or "").lower()
                    if any(k in body for k in REFUSAL):
                        out["reason"] = "site refused: not available here"
                        br.close()
                        print(json.dumps(out))
                        return 0
                except Exception:
                    pass

            out["reason"] = "no video started in time"
            br.close()
    except Exception as e:
        out["reason"] = f"error: {e}"

    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
