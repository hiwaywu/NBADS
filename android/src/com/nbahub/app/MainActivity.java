package com.nbahub.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * NBA Hub for Android -- standalone.
 *
 * Earlier builds were a remote screen for the hub on a PC. This one carries the
 * hub itself (LocalHub: schedule, stream-site scraping, the buffering relay) and
 * serves the same page on 127.0.0.1, so the phone needs nothing but internet.
 *
 * What the shell adds on top of the page:
 *   - video fullscreen through onShowCustomView, so both fullscreen buttons work
 *   - games handed to Chrome/Firefox (Widevine needs a real browser), with a
 *     way back after a game page was opened in-app
 *   - the page's JS console mirrored into logcat
 */
public class MainActivity extends Activity {

    private static final String TAG = "nbahub";

    private LocalHub hub;
    private String home;                 // http://127.0.0.1:PORT/
    private WebView web;
    private FrameLayout root;
    private TextView chip;

    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        hub = new LocalHub(this);
        try {
            home = "http://127.0.0.1:" + hub.start() + "/";
        } catch (Exception e) {
            Log.e(TAG, "hub failed to start", e);
            Toast.makeText(this, "could not start the player: " + e.getMessage(), Toast.LENGTH_LONG).show();
            home = "http://127.0.0.1:8790/";
        }
        Log.i(TAG, "local hub at " + home);

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
        s.setUserAgentString(s.getUserAgentString() + " NBAHubApp/" + LocalHub.VERSION);

        WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect from a PC, if ever needed

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (fullView != null) { cb.onCustomViewHidden(); return; }
                fullView = view;
                fullCallback = cb;
                root.addView(fullView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                chip.setVisibility(View.GONE);
                goFullscreen();
            }

            @Override
            public void onHideCustomView() {
                leaveCustomView();
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                // a game page opened in-app may ask for protected media (Widevine)
                runOnUiThread(new Runnable() {
                    public void run() {
                        for (String r : req.getResources()) {
                            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                                req.grant(new String[]{r});
                                return;
                            }
                        }
                        req.deny();
                    }
                });
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
                updateChip();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame() && isHome(String.valueOf(req.getUrl()))) {
                    Log.w(TAG, "home failed: " + err.getErrorCode() + " " + err.getDescription());
                    view.postDelayed(new Runnable() {
                        public void run() { goHome(); }
                    }, 1200);
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
                String scheme = u == null ? null : u.getScheme();
                if (scheme == null) return false;
                if (scheme.startsWith("http")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Hub");

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        chip = new TextView(this);
        chip.setText("⌂");
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
            public void onClick(View v) { goHome(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        clp.setMargins(0, 0, 4, 0);
        root.addView(chip, clp);

        setContentView(root);
        goFullscreen();
        openFromIntent(getIntent());
    }

    /** JS bridge, same names the page already uses (Hub.openExternal, ...). */
    public class Bridge {
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
        public void retry() {
            runOnUiThread(new Runnable() {
                public void run() { goHome(); }
            });
        }

        @JavascriptInterface
        public String version() { return LocalHub.VERSION; }

        @JavascriptInterface
        public boolean standalone() { return true; }

        @JavascriptInterface
        public void hubReady() { Log.i(TAG, "page ready"); }
    }

    // ---------------------------------------------------------------- nav

    /** Deep link: adb shell am start -n com.nbahub.app/.MainActivity --es chan 0 */
    private void openFromIntent(Intent intent) {
        String chan = intent == null ? null : intent.getStringExtra("chan");
        if (chan != null && chan.matches("\\d+")) web.loadUrl(home + "?chan=" + chan);
        else goHome();
    }

    private void goHome() {
        if (web != null) web.loadUrl(home);
    }

    private boolean isHome(String url) {
        return url != null && url.startsWith(home.substring(0, home.length() - 1));
    }

    private void updateChip() {
        if (chip == null || web == null) return;
        String cur = web.getUrl();
        chip.setVisibility(cur != null && cur.startsWith("http") && !isHome(cur) ? View.VISIBLE : View.GONE);
    }

    private void leaveCustomView() {
        if (fullView == null) return;
        root.removeView(fullView);
        fullView = null;
        web.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback cb = fullCallback;
        fullCallback = null;
        if (cb != null) cb.onCustomViewHidden();
        updateChip();
        goFullscreen();
    }

    // ------------------------------------------------------------- browser

    /**
     * Games are Widevine: they decrypt in a real browser, not in this WebView.
     * The system default is whatever was installed last (Edge on many phones),
     * so ask for Chrome, then Firefox, then let the user choose.
     */
    private static final String[] BROWSER_PACKAGES = {
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
    };

    private void openInBrowser(String url) {
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        for (String pkg : BROWSER_PACKAGES) {
            try {
                if (getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
                intent.setPackage(pkg);
                startActivity(intent);
                return;
            } catch (Exception e) {
                intent.setPackage(null);
            }
        }
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "open stream with"));
        } catch (Exception e) {
            Toast.makeText(this, "no browser accepted that link", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartWebView() {
        try {
            root.removeView(web);
            web.destroy();
        } catch (Exception ignored) { }
        web = null;
        finish();
        startActivity(new Intent(this, MainActivity.class));
    }

    // -------------------------------------------------------------- window

    /** Real fullscreen: on a Fold the taskbar otherwise eats the bottom strip. */
    @SuppressWarnings("deprecation")
    private void goFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (root != null) {
            root.setFitsSystemWindows(false);
            root.setPadding(0, 0, 0, 0);
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
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web != null) {
            if (fullView != null) {
                web.evaluateJavascript("document.fullscreenElement && document.exitFullscreen()", null);
                leaveCustomView();
                return true;
            }
            String cur = String.valueOf(web.getUrl());
            if (!isHome(cur)) {
                // a game page opened in-app: back means "back to the list"
                if (web.canGoBack()) web.goBack(); else goHome();
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("chan")) openFromIntent(intent);
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
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        if (hub != null) hub.stop();      // relay stopped, DVR folder deleted
        super.onDestroy();
    }
}
