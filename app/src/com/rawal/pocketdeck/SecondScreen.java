package com.rawal.pocketdeck;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The selected game on a second display (the lower screen of a dual-screen handheld, or any
 * presentation display): its art, title and stats, rendered by second.html. It appears while
 * Nomad is in front and follows the selection on the main screen.
 */
final class SecondScreen implements DisplayManager.DisplayListener {
    private final Activity activity;
    private final WebViewClient client;
    private final DisplayManager displays;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Screen screen;
    private String latest = "{}";
    private boolean active;

    SecondScreen(Activity activity, WebViewClient client) {
        this.activity = activity;
        this.client = client;
        this.displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** A display other than the one Nomad itself is on, or null. */
    private Display target() {
        Display[] list = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        int own = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display d : list) if (d.getDisplayId() != own && d.isValid()) return d;
        return null;
    }

    void resume(boolean enabled) {
        active = enabled;
        displays.registerDisplayListener(this, main);
        update();
    }

    void pause() {
        active = false;
        displays.unregisterDisplayListener(this);
        update();
    }

    boolean present() { return screen != null; }

    /** The page on the main screen reports the selection as JSON; it is replayed when a screen appears. */
    void show(String json) {
        latest = json;
        main.post(() -> { if (screen != null) screen.send(latest); });
    }

    private void update() {
        Display d = active ? target() : null;
        if (screen != null && (d == null || screen.getDisplay().getDisplayId() != d.getDisplayId())) {
            screen.dismiss();
            screen = null;
        }
        if (d != null && screen == null) {
            try {
                screen = new Screen(activity, d);
                screen.show();
            } catch (Exception e) {
                android.util.Log.w("PocketDeck", "Second screen", e);
                screen = null;
            }
        }
    }

    @Override public void onDisplayAdded(int id) { update(); }
    @Override public void onDisplayRemoved(int id) { update(); }
    @Override public void onDisplayChanged(int id) { }

    private final class Screen extends Presentation {
        private WebView web;
        private boolean loaded;

        Screen(Context context, Display display) { super(context, display); }

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            // Display only: controller keys and focus must stay with the launcher on the main screen.
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            web = new WebView(getContext());
            web.setBackgroundColor(Color.BLACK);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    return client.shouldOverrideUrlLoading(view, request);
                }
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                    return client.shouldInterceptRequest(view, request);
                }
                @Override public void onPageFinished(WebView view, String url) { loaded = true; send(latest); }
                /** A lost renderer only takes this screen away; it comes back on the next resume. */
                @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    web = null;
                    main.post(() -> { if (screen == Screen.this) { screen = null; } dismiss(); view.destroy(); });
                    return true;
                }
            });
            setContentView(web);
            web.loadUrl("https://appassets.androidplatform.net/second.html");
        }

        void send(String json) {
            if (loaded && web != null) web.evaluateJavascript("window.showGame && window.showGame(" + json + ")", null);
        }

        @Override public void onStop() {
            if (web != null) { web.destroy(); web = null; }
            super.onStop();
        }
    }
}
