package com.nbahub.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LocalHub's /api/play and /relay/* on a desktop JVM, serving the real nba/hub
 * page, so the page's own playChannel() -> attachShaka() / attach() can switch
 * games the way a tap does. Like LocalHub, a new game retires the old relay pair,
 * and segments go out with LocalHub's own Cache-Control: max-age=60 -- the
 * browser cache is part of what is under test.
 *
 *   java com.nbahub.app.SwitchHarness <port> <nba/hub dir>
 */
public final class SwitchHarness {
    static volatile Relay video, audio;
    static final Object lock = new Object();
    static final Pattern SEG = Pattern.compile("^seg/(\\d+)\\.(ts|m4s|mp4)$");
    static int port;

    static String playlistOf(Relay r, Relay partner) throws Exception {
        try {
            Method m = Relay.class.getDeclaredMethod("playlist", Relay.class);
            m.setAccessible(true);
            return (String) m.invoke(r, partner);
        } catch (NoSuchMethodException e) {
            return r.playlist();
        }
    }

    /** LocalHub.startRelay, minus the low-delay and referer bits. */
    static void startRelay(String url) throws Exception {
        synchronized (lock) {
            Relay cur = video;
            if (cur != null && !cur.isStopped() && cur.source.equals(url)) return;
            Relay n = new Relay(url, null, false, null);
            n.resolve();
            if (cur != null) cur.stop();
            video = null;
            n.start();
            video = n;
            Relay oldAudio = audio;
            audio = null;
            if (n.audioUrl() != null) {
                Relay an = new Relay(n.audioUrl(), null, false, null);
                an.setPrefix("a/");
                an.resolve();
                an.start();
                audio = an;
            }
            if (oldAudio != null) oldAudio.stop();
        }
    }

    public static void main(String[] a) throws Exception {
        port = Integer.parseInt(a[0]);
        final File hub = new File(a[1]);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        srv.setExecutor(Executors.newFixedThreadPool(16));
        srv.createContext("/", (HttpExchange x) -> {
            try {
                String p = x.getRequestURI().getPath();
                String q = x.getRequestURI().getRawQuery();
                if (p.startsWith("/relay/")) { relay(x, p.substring(7)); return; }
                if (p.equals("/api/play")) {
                    String u = "";
                    for (String kv : (q == null ? "" : q).split("&"))
                        if (kv.startsWith("u=")) u = URLDecoder.decode(kv.substring(2), "UTF-8");
                    startRelay(u);
                    String base = "http://127.0.0.1:" + port + "/relay";
                    String j = "{\"ok\":true,\"url\":\"" + base + "/live.m3u8\",\"master\":\"" + base
                            + "/master.m3u8\",\"audio\":" + (audio != null) + ",\"port\":" + port
                            + ",\"stats\":" + video.stats() + "}";
                    send(x, 200, j.getBytes(), "application/json", null);
                    return;
                }
                File f = new File(hub, p.equals("/") ? "index.html" : p.substring(1));
                if (f.isFile() && !p.contains("..")) {
                    send(x, 200, Files.readAllBytes(f.toPath()),
                            p.endsWith(".js") ? "text/javascript" : "text/html", null);
                    return;
                }
                send(x, 404, "{\"ok\":false}".getBytes(), "application/json", null);
            } catch (Exception e) {
                try { send(x, 500, String.valueOf(e).getBytes(), "text/plain", null); } catch (Exception ignored) { }
            }
        });
        srv.start();
        System.out.println("harness on " + port);
    }

    static void relay(HttpExchange x, String sub) throws Exception {
        Relay rl = video, au = audio;
        if (rl == null || rl.isStopped()) { send(x, 404, "no relay running".getBytes(), "text/plain", null); return; }
        if (sub.equals("master.m3u8")) {
            StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n");
            if (au != null) b.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"main\",DEFAULT=YES,AUTOSELECT=YES,URI=\"a.m3u8\"\n");
            org.json.JSONObject v = rl.stats().optJSONObject("variant");
            b.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(v.optLong("bw"), 1000000))
             .append(",RESOLUTION=").append(v.optInt("w")).append('x').append(v.optInt("h"))
             .append(",CODECS=\"").append(v.optString("codecs")).append('"');
            if (au != null) b.append(",AUDIO=\"aud\"");
            b.append("\nlive.m3u8\n");
            send(x, 200, b.toString().getBytes(), "application/vnd.apple.mpegurl", "no-cache");
            return;
        }
        if (sub.equals("stats")) { send(x, 200, rl.stats().toString().getBytes(), "application/json", "no-cache"); return; }
        boolean isA = sub.equals("a.m3u8") || sub.startsWith("a/");
        Relay r = isA ? au : rl;
        Relay partner = isA ? rl : au;
        if (r == null) { send(x, 404, "no audio relay".getBytes(), "text/plain", null); return; }
        String inner = isA && sub.startsWith("a/") ? sub.substring(2) : sub;
        if (sub.equals("live.m3u8") || sub.equals("a.m3u8")) {
            String pl = playlistOf(r, partner);
            if (pl == null) { send(x, 503, "#EXTM3U\n#EXT-X-TARGETDURATION:2\n".getBytes(), "application/vnd.apple.mpegurl", null); return; }
            send(x, 200, pl.getBytes(), "application/vnd.apple.mpegurl", "no-cache");
            return;
        }
        if (inner.equals("init.mp4") && r.initData() != null) { send(x, 200, r.initData(), "video/mp4", null); return; }
        Matcher m = SEG.matcher(inner);
        if (m.matches()) { send(x, 200, r.segment(Long.parseLong(m.group(1))), "video/mp4", "max-age=60"); return; }
        send(x, 404, "not found".getBytes(), "text/plain", null);
    }

    static void send(HttpExchange x, int code, byte[] body, String type, String cache) throws Exception {
        x.getResponseHeaders().set("Content-Type", type);
        if (cache != null) x.getResponseHeaders().set("Cache-Control", cache);
        x.sendResponseHeaders(code, body.length);
        OutputStream o = x.getResponseBody();
        o.write(body);
        o.close();
    }
}
