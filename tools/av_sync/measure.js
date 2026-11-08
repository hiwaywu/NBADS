// node measure.js <harness port> <media dir> <seconds> <audio seq offset>
//
// A/V error of media sequence N =
//     (where shaka put video N  -  where it put the audio for the same piece)
//   - (true media start of video N  -  true media start of that audio)
// 0 ms: the sound lines up with the picture. Prints one JSON line.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const [port, media, secs, aoff] = [process.argv[2], process.argv[3], Number(process.argv[4]), Number(process.argv[5] || 0)];
const durs = (f) => [...fs.readFileSync(f, 'utf8').matchAll(/#EXTINF:([\d.]+)/g)].map(m => +m[1]);
const starts = (d) => d.map((_, i) => d.slice(0, i).reduce((a, b) => a + b, 0));
const TV = starts(durs(path.join(media, 'out_0', 'index.m3u8')));
const TA = starts(durs(path.join(media, 'out_1', 'index.m3u8')));

(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  try {
    const p = await b.newPage();
    await p.goto(`http://127.0.0.1:${port}/page.html`);
    await p.waitForFunction(() => window.loaded, null, { timeout: 60000 });
    const loaded = await p.evaluate(() => window.loaded);
    const t0 = await p.evaluate(() => document.getElementById('v').currentTime);
    await p.waitForTimeout(secs * 1000);
    const r = await p.evaluate(() => window.placement());
    const stats = await p.evaluate(() => fetch('/relay/stats').then(x => x.json()).catch(() => ({})));
    const errs = [];
    for (const n of Object.keys(r.video)) {
      const an = String(+n + aoff);            // the audio playlist's number for the same piece
      if (!(an in r.audio)) continue;
      errs.push(Math.round(((r.video[n] - r.audio[an]) - (TV[n] - TA[n])) * 1000));
    }
    console.log(JSON.stringify({
      load: loaded,
      advanced: +(r.t - t0).toFixed(1),
      pairs: errs.length,
      worst_ms: errs.reduce((m, e) => Math.max(m, Math.abs(e)), 0),
      errors_ms: errs,
      av_align: stats.av_align,
    }));
  } finally {
    await b.close();
  }
})().catch(e => { console.log(JSON.stringify({ load: 'harness error: ' + e.message })); process.exit(1); });
