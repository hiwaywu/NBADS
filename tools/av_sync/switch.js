// node switch.js <harness port> <game A master> <game B master> <scenario>
//
// Opens the real nba/hub/index.html (served by SwitchHarness) and switches games
// with the page's own playChannel(), the way a tap does. Game A is 640 px wide,
// game B 480 px, so a picture that moves but belongs to the other game is caught.
//   shaka     A, B, A, B as games (shaka + the relay's master, like League Pass)
//   hls       A, B, A, B as channels (hls.js)
//   mixed     game A, channel B, game A, channel B
//   doubletap game A, then tap B and A 0.3 s apart: A must win, no failure card
// Prints one JSON line: {"scenario", "ok", "steps": [...]}.
const { chromium } = require('playwright');

const [port, A, B, scenario] = process.argv.slice(2);
const WIDTH = { A: 640, B: 480 };

(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const steps = [];
  try {
    const p = await b.newPage();
    await p.goto(`http://127.0.0.1:${port}/`);
    await p.waitForFunction(() => typeof playChannel === 'function' && window.shaka, null, { timeout: 30000 });
    const tap = (g, asGame) => p.evaluate(([g, url, asGame]) => {
      if (asGame) playChannel({ name: 'game ' + g, url }, false, null, true, true);
      else playChannel({ name: 'chan ' + g, url });
    }, [g, g === 'A' ? A : B, asGame]);
    const check = async (g, label) => {
      await p.waitForTimeout(9000);
      const s1 = await p.evaluate(() => ({ t: v.currentTime }));
      await p.waitForTimeout(4000);
      const s2 = await p.evaluate(() => ({ t: v.currentTime, w: v.videoWidth,
        card: getComputedStyle(document.getElementById('idle')).display !== 'none' }));
      const adv = +(s2.t - s1.t).toFixed(1);
      const ok = adv > 2.5 && s2.w === WIDTH[g] && !s2.card;
      steps.push({ step: label, ok, advanced: adv, width: s2.w, want: WIDTH[g], card: s2.card });
    };
    const plan = {
      shaka: [['A', 1], ['B', 1], ['A', 1], ['B', 1]],
      hls: [['A', 0], ['B', 0], ['A', 0], ['B', 0]],
      mixed: [['A', 1], ['B', 0], ['A', 1], ['B', 0]],
    }[scenario];
    if (plan) {
      for (const [g, asGame] of plan) {
        await tap(g, asGame);
        await check(g, (asGame ? 'game ' : 'channel ') + g);
      }
    } else if (scenario === 'doubletap') {
      await tap('A', 1);
      await check('A', 'game A');
      await tap('B', 1);
      await p.waitForTimeout(300);
      await tap('A', 1);
      await check('A', 'B then A 0.3 s later');
    }
    console.log(JSON.stringify({ scenario, ok: steps.length > 0 && steps.every(s => s.ok), steps }));
  } finally {
    await b.close();
  }
})().catch(e => { console.log(JSON.stringify({ scenario, ok: false, error: e.message, steps })); process.exit(1); });
