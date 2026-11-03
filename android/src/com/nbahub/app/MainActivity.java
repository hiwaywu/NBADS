package com.nbahub.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * NBA Hub for Android -- a fullscreen shell around the hub running on the PC.
 *
 * The game list, the feed probing and the switching policy all live on the PC,
 * so there is one place to fix a broken source and the phone needs no update.
 *
 * Long-press anywhere, or the button on the error page, to change the address.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "nbahub";
    private static final String KEY_URL = "server";
    private static final String DEFAULT_URL = "http://192.168.1.6:8790/";

    private WebView web;
    private FrameLayout root;
    private TextView notice;

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
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " NBAHubApp/1.1");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (notice != null) notice.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) showErrorPage();
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
                    public void run() { attempt = 0; tryUrl(candidates()[0]); }
                });
            }
        }, "Hub");

        // long-press also opens the address dialog (pages own it only sometimes)
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
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

        setContentView(root);
        goFullscreen();
        load();
    }

    /** Real fullscreen: on a Fold the taskbar otherwise eats the bottom strip. */
    @SuppressWarnings("deprecation")
    private void goFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
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

    private String server() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String u = p.getString(KEY_URL, DEFAULT_URL).trim();
        if (!u.startsWith("http")) u = "http://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }

    private String[] candidates() {
        String saved = server();
        String port = "8790";
        try {
            java.net.URL u = new java.net.URL(saved);
            if (u.getPort() > 0) port = String.valueOf(u.getPort());
        } catch (Exception e) { }
        return new String[]{ saved, "http://10.0.2.2:" + port + "/", "http://127.0.0.1:" + port + "/" };
    }

    private int attempt = 0;

    private void load() {
        attempt = 0;
        tryUrl(candidates()[0]);
        if (notice != null) {
            notice.setText("connecting to " + server() + "\n\nif this stays, long-press the screen");
            notice.setVisibility(View.VISIBLE);
        }
    }

    private void tryUrl(String u) {
        if (notice != null) {
            notice.setText("connecting to " + u);
            notice.setVisibility(View.VISIBLE);
        }
        web.loadUrl(u);
    }

    private void showErrorPage() {
        String[] c = candidates();
        if (attempt + 1 < c.length) {          // emulator / loopback fallbacks
            attempt++;
            tryUrl(c[attempt]);
            return;
        }
        String url = server();
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#0b0c0f;color:#e9eaee;font:15px/1.6 -apple-system,Segoe UI,sans-serif;"
                + "padding:32px;text-align:center}h2{color:#3ddc84;font-weight:600;margin:0 0 10px}"
                + "p{color:#8b8f9a;margin:0 0 22px;max-width:420px}code{color:#c9cdd6;background:#17181c;"
                + "padding:3px 7px;border-radius:5px}button{background:#16281f;color:#3ddc84;border:1px solid #2c4a39;"
                + "border-radius:9px;padding:12px 20px;font-size:15px;margin:6px}</style></head><body>"
                + "<h2>can't reach the hub</h2>"
                + "<p>Both devices must be on the same wifi, and <b>NBAHUB.bat</b> must be running on the PC.<br><br>"
                + "Trying <code>" + url + "</code></p>"
                + "<div><button onclick='Hub.retry()'>try again</button>"
                + "<button onclick='Hub.changeServer()'>change address</button></div>"
                + "</body></html>";
        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", null);
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
                        String u = input.getText().toString().trim();
                        if (!u.startsWith("http")) u = "http://" + u;
                        if (!u.endsWith("/")) u = u + "/";
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putString(KEY_URL, u).apply();
                        load();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(code, e);
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
}
