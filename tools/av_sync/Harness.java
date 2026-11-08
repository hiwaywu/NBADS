package com.nbahub.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the app's own Relay.java the way LocalHub does -- video relay first, then
 * a second relay for the variant's separate audio rendition, both served under
 * /relay/ next to the same synthesised master.m3u8 -- on a desktop JVM. Only the
 * HTTP plumbing is a stand-in (LocalHub.startRelay / relayRoute).
 *
 *   java com.nbahub.app.Harness <upstream master> <port> <audio start delay ms> <page dir>
 */
public final class Harness {
    static Relay video, audio;
    static final Pattern SEG = Pattern.compile("^seg/(\\d+)\\.(ts|m4s|mp4)$");

    /** relay.playlist(partner) as LocalHub calls it; plain playlist() on a Relay.java that predates it. */
    static String playlistOf(Relay r, Relay partner) throws Exception {
        Method m;
        try {
            m = Relay.class.getDeclaredMethod("playlist", Relay.class);
        } catch (NoSuchMethodException e) {
            return r.playlist();
        }
        m.setAccessible(true);
        return (String) m.invoke(r, partner);
    }

    public static void main(String[] a) throws Exception {
        String master = a[0];
        int port = Integer.parseInt(a[1]);
        long delay = Long.parseLong(a[2]);
        final File pageDir = new File(a[3]);

        // ---- LocalHub.startRelay, step for step
        video = new Relay(master, null, false, null);
        video.resolve();
        video.start();
        // the gap a phone has anyway (the audio resolve is its own round trip),
        // widened so the two relays join the live edge at different segments
        if (delay > 0) Thread.sleep(delay);
        if (video.audioUrl() != null) {
            Relay an = new Relay(video.audioUrl(), null, false, null);
            an.setPrefix("a/");
            an.resolve();
            an.start();
            audio = an;
        }

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        srv.setExecutor(Executors.newFixedThreadPool(8));
        srv.createContext("/", (HttpExchange x) -> {
            try {
                String p = x.getRequestURI().getPath();
                if (p.startsWith("/relay/")) { relay(x, p.substring(7)); return; }
                File f = new File(pageDir, p.substring(1));
                if (f.isFile()) {
                    send(x, 200, Files.readAllBytes(f.toPath()), p.endsWith(".js") ? "text/javascript" : "text/html");
                    return;
                }
                send(x, 404, "not found".getBytes(), "text/plain");
            } catch (Exception e) {
                try { send(x, 500, String.valueOf(e).getBytes(), "text/plain"); } catch (Exception ignored) { }
            }
        });
        srv.start();
        System.out.println("harness on " + port);
    }

    static void relay(HttpExchange x, String sub) throws Exception {
        Relay rl = video, au = audio;
        if (sub.equals("master.m3u8")) {
            StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n");
            if (au != null) b.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"main\",DEFAULT=YES,AUTOSELECT=YES,URI=\"a.m3u8\"\n");
            org.json.JSONObject v = rl.stats().optJSONObject("variant");
            b.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(v.optLong("bw"), 1000000))
             .append(",RESOLUTION=").append(v.optInt("w")).append('x').append(v.optInt("h"))
             .append(",CODECS=\"").append(v.optString("codecs")).append('"');
            if (au != null) b.append(",AUDIO=\"aud\"");
            b.append("\nlive.m3u8\n");
            send(x, 200, b.toString().getBytes(), "application/vnd.apple.mpegurl");
            return;
        }
        if (sub.equals("stats")) { send(x, 200, rl.stats().toString().getBytes(), "application/json"); return; }
        boolean isA = sub.equals("a.m3u8") || sub.startsWith("a/");
        Relay r = isA ? au : rl;
        Relay partner = isA ? rl : au;
        String inner = isA && sub.startsWith("a/") ? sub.substring(2) : sub;
        if (sub.equals("live.m3u8") || sub.equals("a.m3u8")) {
            String pl = playlistOf(r, partner);
            if (pl == null) {
                send(x, 503, "#EXTM3U\n#EXT-X-TARGETDURATION:2\n".getBytes(), "application/vnd.apple.mpegurl");
                return;
            }
            send(x, 200, pl.getBytes(), "application/vnd.apple.mpegurl");
            return;
        }
        if (inner.equals("init.mp4") && r.initData() != null) { send(x, 200, r.initData(), "video/mp4"); return; }
        Matcher m = SEG.matcher(inner);
        if (m.matches()) { send(x, 200, r.segment(Long.parseLong(m.group(1))), "video/mp4"); return; }
        send(x, 404, "not found".getBytes(), "text/plain");
    }

    static void send(HttpExchange x, int code, byte[] body, String type) throws Exception {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-cache");
        x.sendResponseHeaders(code, body.length);
        OutputStream o = x.getResponseBody();
        o.write(body);
        o.close();
    }
}
