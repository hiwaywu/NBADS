package com.nbahub.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NBA Hub for Android -- a fullscreen shell around the hub running on the PC.
 *
 * The game list, the feed probing and the switching policy all live on the PC,
 * so there is one place to fix a broken source and the phone needs no update.
 *
 * Three things this shell does that a plain browser tab does not:
 *   - finds the hub on the LAN by itself (a DHCP change used to mean "the app
 *     is broken" with nothing on screen to say why)
 *   - routes video fullscreen through onShowCustomView, so the player's own
 *     fullscreen button and ours both work
 *   - keeps a way back to the hub after a game hands us to a third-party site,
 *     and mirrors the page's JS console into logcat
 */
public class MainActivity extends Activity {

    private static final String TAG = "nbahub";
    private static final String PREFS = "nbahub";
    private static final String KEY_URL = "server";
    private static final String DEFAULT_URL = "http://192.168.1.6:8790/";
    private static final int HUB_PORT = 8790;
    private static final String APP_VER = "1.3";

    private WebView web;
    private FrameLayout root;
    private TextView notice;
    private TextView chip;

    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    private int attempt = 0;
    private boolean discovered = false;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "lan-scan");
            t.setDaemon(true);
            return t;
        }
    });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " NBAHubApp/" + APP_VER);

        WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect on the PC

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (fullView != null) { cb.onCustomViewHidden(); return; }
                fullView = view;
                fullCallback = cb;
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                root.addView(fullView, lp);
                web.setVisibility(View.GONE);
                chip.setVisibility(View.GONE);
                goFullscreen();
            }

            @Override
            public void onHideCustomView() {
                if (fullView == null) return;
                root.removeView(fullView);
                fullView = null;
                web.setVisibility(View.VISIBLE);
                if (fullCallback != null) fullCallback.onCustomViewHidden();
                fullCallback = null;
                updateChip();
                goFullscreen();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "console " + m.messageLevel() + ": " + m.message()
                        + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (notice != null && url != null && !url.startsWith("data:")) {
                    notice.setVisibility(View.GONE);
                }
                updateChip();
                Log.i(TAG, "loaded " + url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    Log.w(TAG, "main frame error: " + err.getErrorCode() + " " + err.getDescription());
                    showErrorPage();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "renderer gone, didCrash=" + detail.didCrash());
                restartWebView();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u == null ? "" : u.getScheme();
                if (scheme == null) return false;
                if (scheme.startsWith("http")) return false;         // stay in the webview
                // tel:, intent:, market: -- hand to the system instead of failing silently
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void changeServer() {
                runOnUiThread(new Runnable() {
                    public void run() { askForServer(); }
                });
            }

            @JavascriptInterface
            public void retry() {
                runOnUiThread(new Runnable() {
                    public void run() { attempt = 0; load(); }
                });
            }

            @JavascriptInterface
            public void openInternal(final String url) {
                if (url == null || url.length() == 0) return;
                runOnUiThread(new Runnable() {
                    public void run() { web.loadUrl(url); }
                });
            }

            @JavascriptInterface
            public void openExternal(final String url) {
                if (url == null || url.length() == 0) return;
                runOnUiThread(new Runnable() {
                    public void run() { openInBrowser(url); }
                });
            }

            @JavascriptInterface
            public void showDiag() {
                runOnUiThread(new Runnable() {
                    public void run() { web.evaluateJavascript("diag && diag()", null); }
                });
            }

            @JavascriptInterface
            public String version() { return APP_VER; }

            @JavascriptInterface
            public void hubReady() {
                Log.i(TAG, "hub ready");
            }
        }, "Hub");

        web.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                askForServer();
                return true;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        notice = new TextView(this);
        notice.setTextColor(Color.parseColor("#8b8f9a"));
        notice.setTextSize(13);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(48, 48, 48, 48);
        notice.setText("connecting to " + server() + "\n\nif this stays, long-press the screen");
        root.addView(notice, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        chip = new TextView(this);
        chip.setText("\u2302");
        chip.setTextSize(15);
        chip.setTextColor(Color.parseColor("#d9ffe8"));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(16, 12, 16, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#66162a1f"));
        bg.setCornerRadius(30f);
        bg.setStroke(1, Color.parseColor("#552c4a39"));
        chip.setBackground(bg);
        chip.setAlpha(0.30f);
        chip.setVisibility(View.GONE);
        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { load(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        clp.setMargins(0, 0, 4, 0);
        root.addView(chip, clp);

        setContentView(root);
        goFullscreen();
        loadFromIntent(getIntent());
        loadOrDefault(getIntent());
    }

    private void loadOrDefault(Intent intent) {
        if (intent != null && intent.getData() != null) return;   // already loaded by loadFromIntent
        load();
    }

    // ---------------------------------------------------------------- server

    private String server() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String u = p.getString(KEY_URL, DEFAULT_URL).trim();
        if (!u.startsWith("http")) u = "http://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }

    private void saveServer(String u) {
        if (!u.startsWith("http")) u = "http://" + u;
        if (!u.endsWith("/")) u = u + "/";
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, u).apply();
    }

    private String[] candidates() {
        String saved = server();
        String port = String.valueOf(HUB_PORT);
        try {
            java.net.URL u = new java.net.URL(saved);
            if (u.getPort() > 0) port = String.valueOf(u.getPort());
        } catch (Exception ignored) {
        }
        return new String[]{saved, "http://10.0.2.2:" + port + "/", "http://127.0.0.1:" + port + "/"};
    }

    private void load() {
        attempt = 0;
        tryUrl(candidates()[0]);
        if (notice != null) {
            notice.setText("connecting to " + server() + "\n\nif this stays, long-press the screen");
            notice.setVisibility(View.VISIBLE);
        }
    }

    /** Deep link: adb ... am start -d "http://pc:8790/?chan=0" -- or a saved link. */
    private void loadFromIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String u = data.toString();
        if (!u.startsWith("http")) return;
        attempt = 0;
        discovered = true;
        tryUrl(u);
    }

    private void tryUrl(String u) {
        if (notice != null) {
            notice.setText("connecting to " + u);
            notice.setVisibility(View.VISIBLE);
        }
        web.loadUrl(u);
    }

    private void updateChip() {
        if (chip == null) return;
        String cur = web == null ? null : web.getUrl();
        boolean away = cur != null && cur.startsWith("http") && !isHubUrl(cur);
        chip.setVisibility(away ? View.VISIBLE : View.GONE);
    }

    private boolean isHubUrl(String url) {
        try {
            java.net.URL a = new java.net.URL(server());
            java.net.URL b = new java.net.URL(url);
            return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------- LAN find

    /**
     * Find the hub without the user typing an IP. Scans the phone's own /24 for
     * port 8790 answering /api/ping with "nbahub". Runs off the UI thread; the
     * first hit wins and is loaded automatically.
     */
    private void discoverAsync() {
        if (discovered) return;
        discovered = true;
        pool.execute(new Runnable() {
            public void run() {
                String found = scan();
                if (found == null) {
                    Log.i(TAG, "lan scan: no hub found");
                    return;
                }
                Log.i(TAG, "lan scan found " + found);
                saveServer(found);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (notice != null) notice.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this, "hub found at " + server(), Toast.LENGTH_SHORT).show();
                        load();
                    }
                });
            }
        });
    }

    private String scan() {
        List<String> prefixes = prefixes();
        if (prefixes.isEmpty()) {
            Log.w(TAG, "lan scan: no candidate subnets");
            return null;
        }
        Log.i(TAG, "lan scan prefixes " + prefixes);
        for (final String prefix : prefixes) {
            ExecutorService scanPool = Executors.newFixedThreadPool(48);
            final AtomicBoolean hit = new AtomicBoolean(false);
            final List<String> wins = Collections.synchronizedList(new ArrayList<String>());
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                scanPool.execute(new Runnable() {
                    public void run() {
                        if (hit.get()) return;
                        if (probe(ip)) {
                            hit.set(true);
                            wins.add("http://" + ip + ":" + HUB_PORT + "/");
                        }
                    }
                });
            }
            scanPool.shutdown();
            try {
                scanPool.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (!wins.isEmpty()) return wins.get(0);
            Log.i(TAG, "lan scan " + prefix + "0/24 empty");
        }
        return null;
    }

    /**
     * Subnets worth probing: every /24 this phone owns, plus the /24 of whatever
     * address we already have (an emulator sits on 10.0.2.x while the PC it
     * wants is on 192.168.1.x, and a phone whose wifi was re-subnetted still
     * remembers the old one).
     */
    private List<String> prefixes() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr == null || addr.getAddress() == null || addr.getAddress().length != 4) continue;
                    addPrefix(out, addr.getHostAddress());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read local addresses: " + e);
        }
        addPrefix(out, hostOf(server()));
        addPrefix(out, hostOf(DEFAULT_URL));
        return out;
    }

    private static void addPrefix(List<String> out, String ipv4) {
        if (ipv4 == null) return;
        int dot = ipv4.lastIndexOf('.');
        if (dot <= 0) return;
        String prefix = ipv4.substring(0, dot + 1);
        if (prefix.startsWith("0.") || prefix.startsWith("127.") || prefix.startsWith("169.254.")) return;
        if (!out.contains(prefix)) out.add(prefix);
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Does this address answer as a hub? Deliberately uses HttpURLConnection:
     * it honours the system proxy and does not depend on hand-rolled HTTP over a
     * raw socket. 450 ms connect is generous enough for an emulator's NAT hop
     * (150 ms RTT here) while still letting a /24 finish in a few seconds.
     */
    private boolean probe(String ip) {
        HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL("http://" + ip + ":" + HUB_PORT + "/api/ping");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(450);
            c.setReadTimeout(900);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) > 0 && total < 4096) {
                buf.write(chunk, 0, n);
                total += n;
            }
            in.close();
            String body = buf.toString("UTF-8");
            return body.contains("\"app\"") && body.contains("nbahub");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------- browser

    /**
     * Games are Widevine and can only decrypt in a real browser, so handing the
     * URL off is the whole feature. Which browser matters: the system default is
     * whatever the user installed last (Edge on many phones). Prefer Chrome, then
     * Firefox, then anything the user wants to choose.
     */
    private static final String[] BROWSER_PACKAGES = {
            "com.android.chrome",          // Chrome
            "com.chrome.beta",             // Chrome beta
            "org.mozilla.firefox",         // Firefox
            "org.mozilla.firefox_beta",
            "com.brave.browser",           // Brave
    };

    private void openInBrowser(String url) {
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        for (String pkg : BROWSER_PACKAGES) {
            try {
                if (getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
                intent.setPackage(pkg);
                Log.i(TAG, "opening in " + pkg);
                startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "could not hand off to " + pkg + ": " + e);
                intent.setPackage(null);
            }
        }
        try {
            Log.i(TAG, "no preferred browser, using the chooser");
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "open stream with"));
        } catch (Exception e) {
            Toast.makeText(this, "no browser accepted that link", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------- error page

    private void showErrorPage() {
        updateChip();
        discoverAsync();
        String[] c = candidates();
        if (attempt + 1 < c.length) {
            attempt++;
            tryUrl(c[attempt]);
            return;
        }
        String url = server();
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#0b0c0f;color:#e9eaee;font:15px/1.6 -apple-system,Segoe UI,sans-serif;"
                + "padding:32px;text-align:center}h2{color:#3ddc84;font-weight:600;margin:0 0 10px}"
                + "p{color:#8b8f9a;margin:0 0 22px;max-width:430px}code{color:#c9cdd6;background:#17181c;"
                + "padding:3px 7px;border-radius:5px;word-break:break-all}button{background:#16281f;color:#3ddc84;"
                + "border:1px solid #2c4a39;border-radius:9px;padding:12px 20px;font-size:15px;margin:6px}</style>"
                + "</head><body><h2>can't reach the hub</h2>"
                + "<p>Both devices must be on the same wifi, and <b>NBAHUB.bat</b> must be running on the PC."
                + "<br><br>Trying <code>" + url + "</code><br><br>"
                + "The app is scanning the wifi for the PC now - if it finds it, it reloads by itself.</p>"
                + "<div><button onclick='Hub.retry()'>try again</button>"
                + "<button onclick='Hub.changeServer()'>change address</button>"
                + "<button onclick='Hub.showDiag()'>diagnostics</button></div>"
                + "</body></html>";
        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", null);
    }

    private void restartWebView() {
        try {
            root.removeView(web);
            web.destroy();
        } catch (Exception ignored) { }
        finish();
        startActivity(new Intent(this, MainActivity.class));
    }

    private void askForServer() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(server());
        new AlertDialog.Builder(this)
                .setTitle("Hub address")
                .setMessage("The PC running NBAHUB.bat, same wifi. e.g. 192.168.1.6:8790")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        saveServer(input.getText().toString().trim());
                        discovered = true;              // do not overwrite a manual choice
                        load();
                    }
                })
                .setNeutralButton("scan wifi", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        discovered = false;
                        Toast.makeText(MainActivity.this, "scanning wifi for the hub...", Toast.LENGTH_SHORT).show();
                        discoverAsync();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ window

    /** Real fullscreen: on a Fold the taskbar otherwise eats the bottom strip. */
    @SuppressWarnings("deprecation")
    private void goFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        // On API 34 the window is still inset by the system bars unless it asks
        // for fullscreen the old way; without this the WebView is ~50dp shorter
        // than the display, and a fullscreen video lands off-centre.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (root != null) {
            root.setFitsSystemWindows(false);
            root.setPadding(0, 0, 0, 0);      // insets sometimes arrive as padding
        }
        if (web != null) {
            web.setFitsSystemWindows(false);
            web.setPadding(0, 0, 0, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            // the modern controller hides the bars but keeps their insets in the
            // window metrics; the legacy flag is what actually reclaims the area
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (fullView != null) {
                // leave video fullscreen before leaving the page
                web.evaluateJavascript("document.fullscreenElement && document.exitFullscreen()", null);
                WebChromeClient.CustomViewCallback cb = fullCallback;
                fullCallback = null;
                if (fullView != null) {
                    root.removeView(fullView);
                    fullView = null;
                    web.setVisibility(View.VISIBLE);
                    if (cb != null) cb.onCustomViewHidden();
                    updateChip();
                }
                return true;
            }
            if (web != null) {
                String cur = String.valueOf(web.getUrl());
                // on the hub itself there is nothing useful behind us: going
                // back would land on about:blank and look like a crash
                if (!isHubUrl(cur) && web.canGoBack()) {
                    web.goBack();
                    return true;
                }
                if (!isHubUrl(cur)) {
                    load();
                    return true;
                }
            }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadFromIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        try { pool.shutdownNow(); } catch (Exception ignored) { }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
