package com.nbahub.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hub.py, inside the APK. Listens on 127.0.0.1 only -- nothing on the wifi can
 * reach it, and no PC is involved.
 *
 * It serves the very same index.html the PC uses (bundled as an asset) and the
 * /api/* calls that page makes, so the UI, mirror failover, DVR, latency pill
 * and diagnostics all behave exactly as on the desktop. The relay lives under
 * /relay/ on the same port; the page derives it from the URL /api/play hands
 * back, so it needs no changes for that either.
 *
 * Two things only a PC could do are replaced:
 *   - game probing (headless Chromium): the event page's first "Main N" link is
 *     opened in the phone's browser, which is where a Widevine feed has to play
 *     anyway
 *   - feeds.txt on disk: bundled, then refreshed from the repo on GitHub, so a
 *     dead mirror is fixed by editing one file -- no APK rebuild
 */
final class LocalHub {

    private static final String TAG = "nbahub.hub";
    static final String VERSION = "2.0";
    private static final String FEEDS_REMOTE =
            "https://raw.githubusercontent.com/hiwaywu/NBADS/main/nba/feeds.txt";

    /** What the hub needs from Android -- small enough to fake on a desktop JVM for tests. */
    interface Env {
        InputStream asset(String name) throws IOException;
        File filesDir();
        File cacheDir();
    }

    private final Env env;
    private final Games games = new Games();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile Relay relay;
    private volatile boolean relayLow;
    private final Object relayLock = new Object();
    private volatile String feedsText;
    private volatile String feedsFrom = "bundled";
    private final Map<String, Object[]> resolved = new HashMap<String, Object[]>();
    private final Map<String, Integer> freezes = new HashMap<String, Integer>();

    LocalHub(Context ctx) {
        final Context app = ctx.getApplicationContext();
        this.env = new Env() {
            public InputStream asset(String name) throws IOException { return app.getAssets().open(name); }
            public File filesDir() { return app.getFilesDir(); }
            public File cacheDir() { return app.getCacheDir(); }
        };
    }

    LocalHub(Env env) {
        this.env = env;
    }

    // ------------------------------------------------------------ lifecycle

    /** Bind and return the port (8790 if free, else any). */
    int start() throws IOException {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try {
            server = new ServerSocket(8790, 64, lo);
        } catch (IOException busy) {
            server = new ServerSocket(0, 64, lo);
        }
        sweepDvr();
        loadFeeds();
        Thread t = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "hub-accept");
        t.setDaemon(true);
        t.start();
        // warm the game list while the WebView is still starting
        workers.execute(new Runnable() {
            public void run() {
                try { games.all(); } catch (Exception ignored) { }
                refreshFeeds();
            }
        });
        return server.getLocalPort();
    }

    void stop() {
        stopRelay();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }

    /** App went to the background for good: stop pulling video. */
    void stopRelay() {
        synchronized (relayLock) {
            if (relay != null) relay.stop();
            relay = null;
        }
    }

    private File dvrRoot() {
        return new File(env.cacheDir(), "dvr");
    }

    private void sweepDvr() {
        File r = dvrRoot();
        if (r.isDirectory()) Relay.deleteTree(r);
    }

    // --------------------------------------------------------------- feeds

    private void loadFeeds() {
        File cached = new File(env.filesDir(), "feeds.txt");
        try {
            if (cached.isFile() && cached.length() > 0) {
                FileInputStream in = new FileInputStream(cached);
                try { feedsText = new String(Http.readAll(in), "UTF-8"); } finally { in.close(); }
                feedsFrom = "github (cached)";
                return;
            }
        } catch (Exception ignored) { }
        try {
            InputStream in = env.asset("feeds.txt");
            try { feedsText = new String(Http.readAll(in), "UTF-8"); } finally { in.close(); }
            feedsFrom = "bundled";
        } catch (Exception e) {
            feedsText = "";
        }
    }

    private void refreshFeeds() {
        try {
            String t = Http.text(FEEDS_REMOTE + "?_=" + System.currentTimeMillis(), 10000);
            if (parseChannels(t).length() == 0) return;        // never replace a list with nothing
            feedsText = t;
            feedsFrom = "github";
            FileOutputStream o = new FileOutputStream(new File(env.filesDir(), "feeds.txt"));
            try { o.write(t.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            Log.i(TAG, "feeds refresh skipped: " + e.getMessage());
        }
    }

    /** NAME | URL [| referer] */
    private static JSONArray parseChannels(String text) {
        JSONArray out = new JSONArray();
        if (text == null) return out;
        for (String line : text.split("\r?\n")) {
            String s = line.trim();
            if (s.length() == 0 || s.startsWith("#") || !s.contains("|")) continue;
            String[] p = s.split("\\|");
            if (p.length < 2) continue;
            String name = p[0].trim(), url = p[1].trim();
            if (!url.startsWith("http")) continue;
            try {
                JSONObject c = new JSONObject().put("name", name).put("url", url);
                if (p.length > 2 && p[2].trim().startsWith("http")) c.put("referer", p[2].trim());
                out.put(c);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private String refererFor(String url) {
        JSONArray cs = parseChannels(feedsText);
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c != null && url.equals(c.optString("url"))) return c.optString("referer", null);
        }
        return null;
    }

    // ------------------------------------------------------------- server

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                break;
            }
            workers.execute(new Runnable() {
                public void run() { serve(s); }
            });
        }
    }

    private static final class Req {
        String method, path, query;
        Map<String, String> qs = new HashMap<String, String>();
        boolean keepAlive = true;

        String q(String k) {
            String v = qs.get(k);
            return v == null ? "" : v;
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            // keep-alive: hls.js asks for a segment every few seconds, and the
            // WebView reuses sockets -- one connection per request is wasted work
            while (true) {
                Req r = readRequest(in);
                if (r == null) break;
                try {
                    route(r, out);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    Log.w(TAG, "handler " + r.path + ": " + e);
                    send(out, r, 500, "application/json", bytes("{\"ok\":false,\"error\":\"hub handler error\"}"), null);
                }
                out.flush();
                if (!r.keepAlive) break;
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static Req readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.length() == 0) return null;
        String[] p = line.split(" ");
        if (p.length < 2) return null;
        Req r = new Req();
        r.method = p[0];
        String target = p[1];
        r.keepAlive = p.length < 3 || !"HTTP/1.0".equals(p[2]);
        String h;
        while ((h = readLine(in)) != null && h.length() > 0) {
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("connection")) {
                r.keepAlive = !h.substring(c + 1).trim().equalsIgnoreCase("close");
            }
        }
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        r.query = q >= 0 ? target.substring(q + 1) : "";
        for (String kv : r.query.split("&")) {
            if (kv.length() == 0) continue;
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e >= 0 ? kv.substring(0, e) : kv, "UTF-8");
                String v = e >= 0 ? URLDecoder.decode(kv.substring(e + 1), "UTF-8") : "";
                r.qs.put(k, v);
            } catch (Exception ignored) { }
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
            if (b.length() > 16384) throw new IOException("header too long");
        }
        if (c < 0 && b.length() == 0) return null;
        return b.toString();
    }

    private static byte[] bytes(String s) {
        try { return s.getBytes("UTF-8"); } catch (Exception e) { return s.getBytes(); }
    }

    private static void send(OutputStream out, Req r, int code, String type, byte[] body,
                             Map<String, String> extra) throws IOException {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(code).append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        if (extra == null || !extra.containsKey("Cache-Control")) h.append("Cache-Control: no-store\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet())
            h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append(r.keepAlive ? "Connection: keep-alive\r\n" : "Connection: close\r\n");
        h.append("\r\n");
        out.write(bytes(h.toString()));
        if (!"HEAD".equals(r.method)) out.write(body);
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return " OK";
            case 204: return " No Content";
            case 400: return " Bad Request";
            case 404: return " Not Found";
            case 500: return " Internal Server Error";
            case 502: return " Bad Gateway";
            case 503: return " Service Unavailable";
            default: return " Status";
        }
    }

    private static void json(OutputStream out, Req r, JSONObject j) throws IOException {
        send(out, r, 200, "application/json", bytes(j.toString()), null);
    }

    private void asset(OutputStream out, Req r, String name, String type) throws IOException {
        InputStream in;
        try {
            in = env.asset(name);
        } catch (IOException e) {
            send(out, r, 404, "text/plain", bytes("not found"), null);
            return;
        }
        byte[] b;
        try { b = Http.readAll(in); } finally { in.close(); }
        Map<String, String> x = new HashMap<String, String>();
        x.put("Cache-Control", "no-cache");
        send(out, r, 200, type, b, x);
    }

    // -------------------------------------------------------------- routes

    private void route(Req r, OutputStream out) throws Exception {
        if ("OPTIONS".equals(r.method)) {
            send(out, r, 204, "text/plain", new byte[0], null);
            return;
        }
        String p = r.path;
        if (p.equals("/") || p.equals("/index.html")) { asset(out, r, "index.html", "text/html; charset=utf-8"); return; }
        if (p.equals("/hls.min.js")) { asset(out, r, "hls.min.js", "application/javascript"); return; }
        if (p.startsWith("/relay/")) { relayRoute(r, out, p.substring(7)); return; }

        if (p.equals("/api/ping")) {
            json(out, r, new JSONObject()
                    .put("ok", true).put("app", "nbahub").put("version", VERSION)
                    .put("mode", "standalone (on this phone)")
                    .put("hub", "http://127.0.0.1:" + server.getLocalPort())
                    .put("feeds", feedsFrom)
                    .put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())));
            return;
        }
        if (p.equals("/api/games")) {
            String date = r.q("date");
            if (date.length() == 0) date = Games.todayIso();
            JSONObject j = new JSONObject().put("date", date);
            try {
                j.put("games", new JSONArray(games.forDate(date)));
            } catch (Exception e) {
                j.put("games", new JSONArray()).put("error", String.valueOf(e.getMessage()));
            }
            JSONObject src = new JSONObject();
            synchronized (games.sourceHealth) {
                for (Map.Entry<String, JSONObject> e : games.sourceHealth.entrySet()) src.put(e.getKey(), e.getValue());
            }
            j.put("sources", src);
            j.put("checked_at", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            json(out, r, j);
            return;
        }
        if (p.equals("/api/dates")) {
            TreeSet<String> ds = new TreeSet<String>();
            JSONObject streams = new JSONObject();
            for (JSONObject g : games.all()) {
                String d = g.optString("date");
                if (d.length() == 0) continue;
                ds.add(d);
                if (g.optBoolean("stream")) streams.put(d, streams.optInt(d, 0) + 1);
            }
            Calendar t = Calendar.getInstance();
            int sy = t.get(Calendar.MONTH) >= Calendar.SEPTEMBER ? t.get(Calendar.YEAR) : t.get(Calendar.YEAR) - 1;
            json(out, r, new JSONObject()
                    .put("dates", new JSONArray(new ArrayList<String>(ds)))
                    .put("streams", streams)
                    .put("today", Games.todayIso())
                    .put("season", new JSONObject().put("start", sy + "-10-01").put("end", (sy + 1) + "-04-30")));
            return;
        }
        if (p.equals("/api/next")) {
            String date = r.q("date");
            if (date.length() == 0) date = Games.todayIso();
            int step = "prev".equals(r.q("dir")) ? -1 : 1;
            JSONArray tried = new JSONArray();
            String d = date;
            for (int i = 0; i < 20; i++) {
                d = Games.shift(d, step);
                tried.put(d);
                int n = games.forDate(d).size();
                if (n > 0) {
                    json(out, r, new JSONObject().put("date", d).put("games", n).put("tried", tried));
                    return;
                }
            }
            json(out, r, new JSONObject().put("date", JSONObject.NULL).put("tried", tried));
            return;
        }
        if (p.equals("/api/channels")) {
            json(out, r, new JSONObject().put("channels", parseChannels(feedsText)).put("from", feedsFrom));
            return;
        }
        if (p.equals("/api/play")) {
            String u = r.q("u");
            if (u.length() == 0) {
                send(out, r, 400, "application/json", bytes("{\"ok\":false,\"error\":\"missing u\"}"), null);
                return;
            }
            boolean low = "1".equals(r.q("low"));
            Relay rl;
            try {
                rl = startRelay(u, low);
            } catch (Exception e) {
                json(out, r, new JSONObject().put("ok", false).put("error", "relay failed: " + e.getMessage()));
                return;
            }
            String base = "http://127.0.0.1:" + server.getLocalPort() + "/relay";
            json(out, r, new JSONObject()
                    .put("ok", true)
                    .put("url", base + "/live.m3u8")
                    .put("local", base + "/live.m3u8")
                    .put("port", server.getLocalPort())
                    .put("stats", rl.stats()));
            return;
        }
        if (p.equals("/api/relaystats")) {
            Relay rl = relay;
            json(out, r, rl == null ? new JSONObject().put("ok", false) : rl.stats());
            return;
        }
        if (p.equals("/api/resolve") || p.equals("/api/candidates")) {
            String page = r.q("u");
            List<String> links = linksFor(page);
            if (p.equals("/api/candidates")) {
                JSONArray cs = new JSONArray();
                for (String l : links) {
                    String f = playerFrame(l);
                    cs.put(new JSONObject().put("url", l).put("ok", f != null).put("h", 0).put("frame", f == null ? "" : f));
                }
                json(out, r, new JSONObject().put("ok", true).put("candidates", cs));
                return;
            }
            // no headless browser on a phone: take the first "Main N" link whose
            // page names a player frame in plain HTML. The app opens that page
            // itself -- the player host refuses to play framed by anyone else.
            String main = links.isEmpty() ? page : links.get(0);
            String frame = null;
            for (String l : links) {
                frame = playerFrame(l);
                if (frame != null) { main = l; break; }
            }
            json(out, r, new JSONObject()
                    .put("ok", frame != null)
                    .put("frame", frame != null ? frame : main)
                    .put("open", frame != null ? frame : main)
                    .put("browser", main)
                    .put("source", main).put("event", page)
                    .put("links", new JSONArray(links))
                    .put("probe", new JSONObject().put("ok", frame != null).put("note", "frame read from the page, not probed")));
            return;
        }
        if (p.equals("/api/health")) {
            json(out, r, new JSONObject().put("ok", false).put("reason", "not measured on the phone"));
            return;
        }
        if (p.equals("/api/freeze")) {
            String page = r.q("u");
            int n;
            synchronized (freezes) {
                n = (freezes.containsKey(page) ? freezes.get(page) : 0) + 1;
                freezes.put(page, n);
            }
            json(out, r, new JSONObject().put("ok", true).put("freezes", n));
            return;
        }
        if (p.equals("/api/refresh")) {
            games.invalidate();
            refreshFeeds();
            json(out, r, new JSONObject().put("ok", true).put("feeds", feedsFrom));
            return;
        }
        send(out, r, 404, "text/plain", bytes("not found"), null);
    }

    private final Map<String, Object[]> frames = new HashMap<String, Object[]>();
    private static final Pattern STREAM_IFRAME = Pattern.compile(
            "<iframe[^>]*\\bid=[\"']streamIframe[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * The player frame a "Main N" page embeds (iframe#streamIframe), read from
     * its plain HTML -- the same request a browser makes for that page, nothing
     * spoofed. Null when the page does not name one.
     */
    private String playerFrame(String mainPage) {
        synchronized (frames) {
            Object[] hit = frames.get(mainPage);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 600000) return (String) hit[1];
        }
        String found = null;
        try {
            Http.Resp resp = Http.get(mainPage, 15000, null);
            Matcher m = STREAM_IFRAME.matcher(resp.text());
            if (m.find()) {
                Matcher sm = SRC.matcher(m.group(0));
                if (sm.find()) {
                    String u = Http.join(resp.finalUrl, sm.group(1).replace("&amp;", "&"));
                    if (u.startsWith("https://")) found = u;     // EME needs a secure frame
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "no player frame on " + mainPage + ": " + e.getMessage());
        }
        synchronized (frames) { frames.put(mainPage, new Object[]{System.currentTimeMillis(), found}); }
        return found;
    }

    private List<String> linksFor(String page) {
        if (page.length() == 0) return new ArrayList<String>();
        synchronized (resolved) {
            Object[] hit = resolved.get(page);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 600000) {
                @SuppressWarnings("unchecked") List<String> l = (List<String>) hit[1];
                return l;
            }
        }
        List<String> l = Games.feedLinks(page);
        if (!l.isEmpty()) synchronized (resolved) { resolved.put(page, new Object[]{System.currentTimeMillis(), l}); }
        return l;
    }

    // --------------------------------------------------------------- relay

    /** One player at a time: a new channel retires the previous relay. */
    private Relay startRelay(String url, boolean low) throws Exception {
        synchronized (relayLock) {
            Relay cur = relay;
            if (cur != null && !cur.isStopped() && cur.source.equals(url) && relayLow == low) {
                cur.lastHit = System.currentTimeMillis();
                return cur;
            }
            Relay n = new Relay(url, refererFor(url), low, dvrRoot());
            // resolve first: a dead mirror throws here and the page fails over,
            // while whatever was playing keeps playing
            n.resolve();
            if (cur != null) cur.stop();
            relay = null;
            n.start();
            relay = n;
            relayLow = low;
            return n;
        }
    }

    private static final Pattern SEG = Pattern.compile("^seg/(\\d+)\\.(ts|m4s|mp4)$");

    private void relayRoute(Req r, OutputStream out, String sub) throws Exception {
        Relay rl = relay;
        if (rl == null || rl.isStopped()) {
            send(out, r, 404, "text/plain", bytes("no relay running"), null);
            return;
        }
        rl.lastHit = System.currentTimeMillis();
        rl.hits++;
        if (sub.equals("live.m3u8") || sub.equals("index.m3u8")) {
            rl.plHits++;
            String pl = rl.playlist();
            if (pl == null) {
                send(out, r, 503, "application/vnd.apple.mpegurl", bytes("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"), null);
                return;
            }
            Map<String, String> x = new HashMap<String, String>();
            x.put("Cache-Control", "no-cache");
            send(out, r, 200, "application/vnd.apple.mpegurl", bytes(pl), x);
            return;
        }
        if (sub.equals("health")) {
            send(out, r, rl.ready() ? 200 : 503, "text/plain", bytes(rl.ready() ? "ready" : "warming"), null);
            return;
        }
        if (sub.equals("stats")) { json(out, r, rl.stats()); return; }
        if (sub.equals("window")) {
            String n = r.q("n");
            if (n.matches("\\d+")) rl.setWindow(Integer.parseInt(n));
            json(out, r, rl.windowInfo());
            return;
        }
        if (sub.equals("source")) { send(out, r, 200, "text/plain", bytes(rl.source + "\n"), null); return; }
        if (sub.equals("init.mp4")) {
            byte[] d = rl.initData();
            if (d == null) send(out, r, 404, "text/plain", bytes("no init segment"), null);
            else send(out, r, 200, "video/mp4", d, null);
            return;
        }
        if (sub.equals("key")) {
            try {
                send(out, r, 200, "application/octet-stream", rl.key(r.q("u")), null);
            } catch (Exception e) {
                send(out, r, 502, "text/plain", bytes("key: " + e.getMessage()), null);
            }
            return;
        }
        Matcher m = SEG.matcher(sub);
        if (m.matches()) {
            rl.segHits++;
            byte[] d;
            try {
                d = rl.segment(Long.parseLong(m.group(1)));
            } catch (Exception e) {
                rl.miss++;
                send(out, r, 404, "text/plain", bytes("segment unavailable: " + e.getMessage()), null);
                return;
            }
            Map<String, String> x = new HashMap<String, String>();
            x.put("Cache-Control", "max-age=60");
            send(out, r, 200, "ts".equals(m.group(2)) ? "video/mp2t" : "video/mp4", d, x);
            return;
        }
        send(out, r, 404, "text/plain", bytes("not found"), null);
    }
}
