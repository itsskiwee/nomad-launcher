package com.rawal.pocketdeck;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements MediaHub.Listener {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    /** PPSSPP keeps its config in its own memory-stick folder; either location is possible on this phone. */
    private static final String[] PPSSPP_INIS = {
        "/storage/emulated/0/PPSSPP/PSP/SYSTEM/ppsspp.ini",
        "/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/PSP/SYSTEM/ppsspp.ini"
    };
    /** A page that has not answered a state update for this long while resumed is reloaded. */
    private static final long PAGE_STALL_MS = 45000L;
    private static final int PICK_FOLDER = 41, PICK_COVER = 42, PICK_WALLPAPER = 43, PICK_MEDIA = 44, PICK_VIDEO = 45, PICK_SYNC = 46, PICK_SAVES = 47;

    private File artDir;
    private SharedPreferences prefs;
    private WebView web;
    private PlayTracker playTracker;
    private final BatteryTracker batteryTracker = new BatteryTracker();
    private MediaHub media;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler timer = new Handler(Looper.getMainLooper());
    private volatile boolean scanning = false;
    private volatile String scanMessage = "";
    private JSONArray apps = new JSONArray();
    private boolean appsLoaded;
    private String pendingCoverId = "";
    private volatile boolean webGone;
    private long pageAskedAt, pageAnsweredAt;
    private volatile boolean defaultHome;
    private volatile boolean cleanupVisible;
    private volatile boolean cleanupRunning;
    private final Runnable idleCleanup = new Runnable() {
        @Override public void run() {
            if (!cleanupVisible) return;
            if (!cleanupRunning) {
                cleanupRunning = true;
                worker.execute(() -> {
                    try { if (cleanupVisible && Root.enabled(MainActivity.this)) IdleCleanup.run(MainActivity.this); }
                    finally { cleanupRunning = false; }
                });
            }
            timer.postDelayed(this, 60000L);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            // The page acknowledges every state update; silence means the renderer is wedged.
            if (pageAskedAt > pageAnsweredAt && System.currentTimeMillis() - pageAskedAt > PAGE_STALL_MS && !webGone) {
                Log.w("PocketDeck", "Page stopped answering for " + (System.currentTimeMillis() - pageAskedAt) + " ms; reloading");
                pageAskedAt = pageAnsweredAt = 0;
                web.reload();
            }
            sendState();
            timer.postDelayed(this, 15000L);
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { batteryTracker.sample(intent); sendState(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("deck", 0);
        playTracker = new PlayTracker(this);
        // Preserve opt-in for existing dedicated-device installs; new users start without root.
        if (!prefs.contains("rootFeatures")) prefs.edit().putBoolean("rootFeatures", prefs.getBoolean("wallpaperSet", false)).apply();
        IdleCleanup.reset(this);
        artDir = new File(getFilesDir(), "art");
        artDir.mkdirs();
        migrateFolders();

        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        immersive();
        // A gaming device never dims while the launcher is up.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }

        // Controller keys are taken before the IME stage: after a touch, Android would otherwise spend
        // the first D-pad press leaving touch mode and moving the WebView's own focus.
        web = new WebView(this) {
            @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
                return handlePadKey(event) || super.dispatchKeyEventPreIme(event);
            }
        };
        // Stays black until the page reveals itself; the page paints its own background after that.
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d("PocketDeck", message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        // Local content only; the video backdrop has to start without a tap.
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // Always inspectable over adb (chrome://inspect): this is a private device and it is how a stuck page gets diagnosed.
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Deck");
        PerformanceBridge.attach(this, web);
        ThemeBridge.attach(this, web, artDir);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith(ORIGIN + "/");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"https".equals(url.getScheme()) || !"appassets.androidplatform.net".equals(url.getHost())) return empty();
                String path = url.getPath();
                try {
                    if (path.startsWith("/art/")) {
                        String name = path.substring(5);
                        if (name.matches("themes/[a-z0-9-]+\\.mp4") || name.matches("[a-f0-9]{24}-video\\.mp4")) return ThemeBridge.video(new File(artDir, name), request);
                        if (!name.matches("[a-zA-Z0-9_-]+\\.(jpg|png)")) throw new IOException();
                        String type = name.endsWith("png") ? "image/png" : "image/jpeg";
                        return response(type, new FileInputStream(new File(artDir, name)));
                    }
                    if (path.equals("/")) path = "/index.html";
                    if (!Arrays.asList("/index.html", "/app.css", "/app.js").contains(path)) throw new IOException();
                    String type = path.endsWith("css") ? "text/css" : path.endsWith("js") ? "application/javascript" : "text/html";
                    return response(type, getAssets().open(path.substring(1)));
                } catch (Exception e) {
                    return empty();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) { pageAskedAt = pageAnsweredAt = 0; sendState(); sendMedia(); }

            /** The renderer was killed (memory pressure) or crashed; rebuild the page instead of letting the app die. */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w("PocketDeck", "WebView renderer gone (crashed=" + detail.didCrash() + ", priority=" + detail.rendererPriorityAtExit() + "); recreating");
                webGone = true;
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                view.destroy();
                recreate();
                return true;
            }
        });
        web.loadUrl(ORIGIN + "/index.html");
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        media = new MediaHub(this, artDir, this);
        loadApps();
        worker.execute(this::deviceSetup);
    }

    /** Optional device integration; opening Nomad never changes system display or wallpaper settings. */
    private void deviceSetup() {
        patchEmulatorConfig(null);
        runOnUiThread(() -> { media.start(); sendMedia(); });
    }

    /**
     * Prepares PPSSPP for a launch through the root path, since PPSSPP re-reads ppsspp.ini only at startup:
     * "Exit" in the pause menu quits the emulator (PPSSPP drops that key on every save, hence per launch),
     * cheats are switched on only when this game has its 60 FPS patch enabled, the patch itself is written
     * to PSP/Cheats/<DISC_ID>.ini, and Gran Turismo-style patches get their emulated CPU clock. A PPSSPP
     * that is still alive from the last game is stopped so it boots with this configuration.
     */
    private void patchEmulatorConfig(JSONObject game) {
        if (!Root.enabled(this)) return;
        FpsPatches.Patch patch = game == null ? null : FpsPatches.find(this, game.optString("discId"));
        boolean on = patch != null && "on".equals(fpsPatchState(game));
        StringBuilder script = new StringBuilder();
        // setkey <section> <key> <value>: replaces the key inside its section, or adds it after the header (BOM-safe).
        script.append("setkey() { awk -v s=\"[$1]\" -v k=\"$2\" -v v=\"$3\" '")
            .append("BEGIN{d=0;insec=0} !d && !insec && index($0,s)>0 && index($0,\"[\")<=4 {print; insec=1; next} ")
            .append("/^\\[/{ if(insec && !d){print k \" = \" v; d=1} insec=0 } ")
            .append("insec && index($0, k \" = \")==1 { if(!d){print k \" = \" v; d=1}; next } {print} ")
            .append("END{ if(insec && !d) print k \" = \" v }' \"$f\" > \"$f.tmp\" && cat \"$f.tmp\" > \"$f\"; rm -f \"$f.tmp\"; }; ");
        for (String ini : PPSSPP_INIS) {
            script.append("f=").append(ini).append("; if [ -f \"$f\" ]; then ")
                .append("setkey General PauseMenuExitsEmulator True; ")
                .append("setkey General EnableCheats ").append(on ? "True" : "False").append("; ")
                .append("setkey CPU CPUSpeed ").append(on ? patch.clock : 0).append("; ")
                .append("c=\"${f%/SYSTEM/ppsspp.ini}/Cheats\"; mkdir -p \"$c\"; ");
            if (patch != null) {
                String file = "\"$c/" + patch.discId + ".ini\"";
                if (on) script.append("printf '%s' '").append(patch.cheatFile().replace("'", "'\\''")).append("' > ").append(file).append("; ");
                else script.append("grep -q Nomad ").append(file).append(" 2>/dev/null && rm -f ").append(file).append("; ");
            }
            script.append("fi; ");
        }
        if (game != null) script.append("pidof org.ppsspp.ppsspp >/dev/null 2>&1 && am force-stop org.ppsspp.ppsspp; ");
        script.append("true");
        Root.run(script.toString(), 8);
    }

    /** Lets the phone's own wallpaper — static or live — render behind the launcher window. */
    private void showWallpaper(boolean show) {
        if (show) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            web.setBackgroundColor(Color.TRANSPARENT);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
            web.setBackgroundColor(Color.rgb(16, 19, 17));
        }
    }

    private boolean setBlackWallpaper() {
        try {
            Bitmap black = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            black.eraseColor(Color.BLACK);
            WallpaperManager wm = WallpaperManager.getInstance(this);
            wm.setBitmap(black, null, true, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            return true;
        } catch (Exception e) {
            Log.w("PocketDeck", "Wallpaper", e);
            return false;
        }
    }

    private WebResourceResponse empty() { return response("text/plain", new ByteArrayInputStream(new byte[0])); }

    private WebResourceResponse response(String type, InputStream data) {
        return new WebResourceResponse(type, "UTF-8", data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cleanupVisible = true;
        timer.removeCallbacks(idleCleanup);
        timer.postDelayed(idleCleanup, 30000L);
        web.onResume();
        web.resumeTimers();
        immersive();
        timer.removeCallbacks(ticker);
        if (!worker.isShutdown()) worker.execute(() -> { playTracker.settle(); refreshDeviceFacts(); runOnUiThread(ticker); });
        // Back from a game (or just started): send new saves and pick up another device's.
        if (prefs.getBoolean("sync.auto", true) && (prefs.getLong("sync.launched", 0) > prefs.getLong("sync.last", 0) || !syncedThisRun)) {
            syncedThisRun = true;
            syncSaves(true);
        }
        else timer.post(ticker);
        media.start();
        sendMedia();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cleanupVisible = false;
        timer.removeCallbacks(idleCleanup);
        timer.removeCallbacks(ticker);
        web.onPause();
        web.pauseTimers();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(5894);
    }

    // ---- controller ----
    // Gamepad buttons, the D-pad and the sticks become named inputs for the page (window.deckInput).
    // Keyboards are left alone so the search field keeps working; the page maps arrow keys itself.

    private String stickDirection;
    private final Runnable stickRepeat = new Runnable() {
        @Override public void run() {
            if (stickDirection == null) return;
            sendInput(stickDirection, 1);
            timer.postDelayed(this, 120);
        }
    };

    private static boolean fromPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    static String padButton(int keyCode, boolean pad) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "a";
            case KeyEvent.KEYCODE_BUTTON_B: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return "y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "l1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "r1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "l1";
            case KeyEvent.KEYCODE_BUTTON_R2: return "r1";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "select";
            case KeyEvent.KEYCODE_DPAD_UP: return pad ? "up" : null;
            case KeyEvent.KEYCODE_DPAD_DOWN: return pad ? "down" : null;
            case KeyEvent.KEYCODE_DPAD_LEFT: return pad ? "left" : null;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return pad ? "right" : null;
            case KeyEvent.KEYCODE_DPAD_CENTER: return pad ? "a" : null;
            default: return null;
        }
    }

    private void sendInput(String button, int repeat) {
        if (!webGone) web.evaluateJavascript("window.deckInput && window.deckInput('" + button + "', " + repeat + ")", null);
    }

    private boolean handlePadKey(KeyEvent event) {
        String button = padButton(event.getKeyCode(), fromPad(event.getSource()));
        if (button == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) sendInput(button, event.getRepeatCount());
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return handlePadKey(event) || super.dispatchKeyEvent(event);
    }

    /** Left stick and hat switches (many pads report the D-pad as a hat) with key-like repeat. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || event.getAction() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X), y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < .5f && Math.abs(y) < .5f) { x = event.getAxisValue(MotionEvent.AXIS_X); y = event.getAxisValue(MotionEvent.AXIS_Y); }
        String dir = Math.max(Math.abs(x), Math.abs(y)) < .5f ? null
            : Math.abs(x) > Math.abs(y) ? (x > 0 ? "right" : "left") : (y > 0 ? "down" : "up");
        if (dir == null ? stickDirection != null : !dir.equals(stickDirection)) {
            stickDirection = dir;
            timer.removeCallbacks(stickRepeat);
            if (dir != null) { sendInput(dir, 0); timer.postDelayed(stickRepeat, 380); }
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.deckBack && window.deckBack()", null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        web.evaluateJavascript("window.goHome && window.goHome()", null);
        sendState();
    }

    @Override
    protected void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        unregisterReceiver(batteryReceiver);
        media.stop();
        worker.shutdownNow();
        artWorker.shutdownNow();
        saveWorker.shutdownNow();
        if (!webGone) {
            web.removeJavascriptInterface("Deck");
            web.destroy();
        }
        super.onDestroy();
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    // ---- library state ----

    private JSONArray games() {
        try { return new JSONArray(prefs.getString("games", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private JSONObject findGame(String id) {
        JSONArray all = games();
        for (int i = 0; i < all.length(); i++) {
            JSONObject g = all.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) return g;
        }
        return null;
    }

    private List<String> folders() {
        List<String> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("folders", "[]"));
            for (int i = 0; i < array.length(); i++) list.add(array.getString(i));
        } catch (Exception ignored) { }
        return list;
    }

    private void saveFolders(List<String> list) {
        prefs.edit().putString("folders", new JSONArray(list).toString()).apply();
    }

    private void migrateFolders() {
        String single = prefs.getString("folder", "");
        if (!single.isEmpty() && !prefs.contains("folders")) {
            saveFolders(Collections.singletonList(single));
        }
    }

    private static String folderName(String uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(uri));
            String path = id.substring(id.indexOf(':') + 1);
            return path.isEmpty() ? "Internal storage" : path.replace("/", " / ");
        } catch (Exception e) {
            return uri;
        }
    }

    /** "none" when no patch exists for this disc, otherwise the user's per-game choice (on by default). */
    private String fpsPatchState(JSONObject game) {
        if (game == null || FpsPatches.find(this, game.optString("discId")) == null) return "none";
        return prefs.getBoolean("fps60." + game.optString("id"), true) ? "on" : "off";
    }

    /** Art file key for a library id: PSP games use their hash, Android games hash the package. */
    private String artKey(String id) throws Exception {
        return id.startsWith("app:") ? "app-" + hash(id.substring(4)) : id;
    }

    private void sendState() {
        runOnUiThread(() -> {
            try {
                JSONArray games = games();
                for (int i = 0; i < games.length(); i++) {
                    JSONObject g = games.getJSONObject(i);
                    decorate(g, g.getString("id"));
                    g.put("fpsPatch", fpsPatchState(g));
                    g.put("emuChoice", prefs.getString("emu.game." + g.getString("id"), ""));
                    if (prefs.getBoolean("hidden." + g.getString("id"), false)) g.put("hidden", true);
                    String title = prefs.getString("title." + g.getString("id"), null);
                    if (title != null) g.put("title", title);
                    File clip = new File(artDir, g.getString("id") + "-video.mp4");
                    if (clip.exists()) g.put("video", clip.getName() + "?v=" + clip.lastModified());
                }
                foldVersions(games);
                JSONArray appList = new JSONArray();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject a = new JSONObject(apps.getJSONObject(i).toString());
                    String id = "app:" + a.getString("package");
                    a.put("id", id);
                    decorate(a, id);
                    appList.put(a);
                }
                JSONArray folderList = new JSONArray();
                for (String uri : folders()) folderList.put(new JSONObject().put("uri", uri).put("name", folderName(uri)));

                JSONObject state = new JSONObject();
                state.put("games", games);
                state.put("apps", appList);
                state.put("folders", folderList);
                state.put("scanning", scanning);
                state.put("appsLoaded", appsLoaded);
                state.put("scanMessage", scanMessage);
                state.put("hasFolder", !folders().isEmpty());
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = battery == null ? -1 : battery.getIntExtra("level", -1);
                int scale = battery == null ? 100 : Math.max(1, battery.getIntExtra("scale", 100));
                state.put("battery", level >= 0 ? Math.round(level * 100f / scale) : -1);
                state.put("charging", battery != null && battery.getIntExtra("plugged", 0) != 0);
                state.put("batteryStats", batteryTracker.snapshot(this, battery));
                state.put("usageAccess", playTracker.enabled());
                state.put("network", networkName());
                state.put("systems", systemsState(games));
                state.put("emulatorPackages", new JSONArray(Systems.emulatorPackages()));
                state.put("defaultHome", defaultHome);
                File wallpaper = new File(artDir, "wallpaper.jpg");
                state.put("wallpaper", wallpaper.exists() ? "wallpaper.jpg?v=" + wallpaper.lastModified() : "");
                state.put("mediaAccess", media.enabled());
                state.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                state.put("rootFeatures", Root.enabled(this));
                state.put("autoArt", prefs.getBoolean("autoArt", true));
                state.put("saves", savesState());
                if (webGone) return;
                pageAskedAt = System.currentTimeMillis();
                web.evaluateJavascript("window.receiveState && window.receiveState(" + state + ")", value -> pageAnsweredAt = System.currentTimeMillis());
            } catch (Exception e) {
                Log.e("PocketDeck", "State update", e);
            }
        });
    }

    /** The favorite/lastPlayed/playtime/cover fields shared by a game and an app card. */
    private void decorate(JSONObject item, String id) throws Exception {
        item.put("favorite", prefs.getBoolean("favorite." + id, false));
        item.put("lastPlayed", prefs.getLong("played." + id, 0L));
        item.put("playtimeMs", playTracker.total(id));
        item.put("lastSessionMs", playTracker.last(id));
        item.put("plays", prefs.getInt("plays." + id, 0));
        item.put("cover", coverFor(id));
        item.put("customCover", item.optString("cover").contains("-cover.jpg"));
    }

    /** The user's own cover, else downloaded box art. */
    private String coverFor(String id) {
        try {
            for (String suffix : new String[] { "-cover.jpg", "-box.jpg" }) {
                String name = artKey(id) + suffix;
                File f = new File(artDir, name);
                if (f.exists()) return name + "?v=" + f.lastModified();
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String networkName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) return "Offline";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile data";
            return "Connected";
        } catch (Exception e) {
            return "Offline";
        }
    }

    @Override
    public void onMediaChanged() { sendMedia(); }

    private void sendMedia() {
        runOnUiThread(() -> {
            try {
                web.evaluateJavascript("window.receiveMedia && window.receiveMedia(" + media.state() + ")", null);
            } catch (Exception e) {
                Log.w("PocketDeck", "Media", e);
            }
        });
    }

    private boolean isHome() {
        ResolveInfo info = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 65536);
        return info != null && info.activityInfo != null && getPackageName().equals(info.activityInfo.packageName);
    }

    /** Each system, how many games it has, the emulator that will play them and the other options. */
    private JSONArray systemsState(JSONArray games) throws Exception {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < games.length(); i++) {
            String sys = games.getJSONObject(i).optString("system", "psp");
            counts.put(sys, counts.containsKey(sys) ? counts.get(sys) + 1 : 1);
        }
        JSONArray out = new JSONArray();
        for (Systems.System s : Systems.ALL) {
            JSONArray options = new JSONArray();
            for (Systems.Choice c : s.choices) {
                options.put(new JSONObject().put("key", c.key()).put("label", c.label()).put("emulator", c.emulator.id)
                    .put("installed", c.emulator.installed(this) != null).put("site", c.emulator.site != null));
            }
            Systems.Choice chosen = Systems.resolve(this, s, null, prefs.getString("emu.system." + s.id, null));
            out.put(new JSONObject().put("id", s.id).put("name", s.name).put("games", counts.containsKey(s.id) ? counts.get(s.id) : 0)
                .put("choice", chosen == null ? "" : chosen.key()).put("pinned", prefs.getString("emu.system." + s.id, "")).put("options", options));
        }
        return out;
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.isDirectory() ? dirSize(f) : f.length();
        return total;
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(String.format(Locale.US, "%02x", digest[i]));
        return sb.toString();
    }

    // ---- installed apps (and Android games) ----

    /** Package-manager round trips are not free; they are answered from these caches on the UI thread. */
    private void refreshDeviceFacts() {
        Systems.refresh(this);
        defaultHome = isHome();
    }

    private void loadApps() {
        worker.execute(() -> {
            refreshDeviceFacts();
            JSONArray list = new JSONArray();
            List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            Collections.sort(resolved, new ResolveInfo.DisplayNameComparator(getPackageManager()));
            HashSet<String> seen = new HashSet<>();
            for (ResolveInfo info : resolved) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                try {
                    String name = "app-" + hash(pkg) + ".png";
                    File file = new File(artDir, name);
                    if (!file.exists() || file.length() < 20000 && isSmallIcon(file)) {
                        Drawable icon = info.loadIcon(getPackageManager());
                        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bitmap);
                        icon.setBounds(0, 0, 256, 256);
                        icon.draw(canvas);
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        bitmap.recycle();
                    }
                    ApplicationInfo ai = info.activityInfo.applicationInfo;
                    boolean game = ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
                    list.put(new JSONObject().put("package", pkg).put("title", info.loadLabel(getPackageManager()).toString()).put("icon", name).put("game", game));
                } catch (Exception ignored) { }
            }
            runOnUiThread(() -> { apps = list; appsLoaded = true; sendState(); });
        });
    }

    /** Icons cached by older builds were 128px; re-render those at 256px once. */
    private static boolean isSmallIcon(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        return o.outWidth < 256;
    }

    // ---- folders and scanning ----

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3APPSSPP%2FPSP%2FGAME"));
        try { startActivityForResult(intent, PICK_FOLDER); } catch (Exception e) { toast("The Android folder picker is unavailable."); }
    }

    private void pickTree(int request, String initial) {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/" + initial));
            try { startActivityForResult(intent, request); } catch (Exception e) { toast("The Android folder picker is unavailable."); }
        });
    }

    private void pickImage(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try { startActivityForResult(intent, request); } catch (Exception e) { toast("The image picker is unavailable."); }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_FOLDER) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                List<String> list = folders();
                if (!list.contains(uri.toString())) list.add(uri.toString());
                saveFolders(list);
                scan();
            } catch (Exception e) {
                toast("Folder access was not granted. Please choose the folder again.");
            }
        } else if (request == PICK_SYNC || request == PICK_SAVES) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) { toast("Folder access was not granted."); return; }
            if (request == PICK_SYNC) prefs.edit().putString("sync.folder", uri.toString()).apply();
            else addSaveSet(folderName(uri.toString()).replaceAll(".* / ", ""), "saf", uri.toString());
            sendState();
            syncSaves(false);
        } else if (request == PICK_MEDIA) {
            importMedia(uri);
        } else if (request == PICK_VIDEO) {
            String id = pendingCoverId;
            if (findGame(id) == null) return;
            worker.execute(() -> {
                if (copyVideo(uri, new File(artDir, id + "-video.mp4"))) sendState();
                else toast("That video could not be used. Clips up to 80 MB work.");
            });
        } else if (request == PICK_COVER || request == PICK_WALLPAPER) {
            final String target;
            try {
                target = request == PICK_WALLPAPER ? "wallpaper.jpg" : artKey(pendingCoverId) + "-cover.jpg";
            } catch (Exception e) { return; }
            worker.execute(() -> {
                if (saveImage(uri, new File(artDir, target), request == PICK_WALLPAPER ? 1920 : 1200)) sendState();
                else toast("That image could not be used.");
            });
        }
    }

    // ---- save sync ----

    private final ExecutorService saveWorker = Executors.newSingleThreadExecutor();
    private volatile boolean syncing;
    private boolean syncedThisRun;

    private JSONArray saveSets() {
        try { return new JSONArray(prefs.getString("sync.sets", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Save folders are labelled for their mirror ("Nomad Saves/<label>"); a label is kept unique. */
    private void addSaveSet(String label, String kind, String location) {
        try {
            JSONArray sets = saveSets();
            HashSet<String> labels = new HashSet<>();
            String path = savePath(kind, location);
            for (int i = 0; i < sets.length(); i++) {
                JSONObject s = sets.getJSONObject(i);
                if (path.equals(savePath(s.optString("kind"), s.optString("location")))) return;
                labels.add(sets.getJSONObject(i).optString("label"));
            }
            String clean = label.replaceAll("[\\\\/:*?\"<>|]", " ").trim(), unique = clean;
            for (int n = 2; labels.contains(unique); n++) unique = clean + " " + n;
            sets.put(new JSONObject().put("label", unique).put("kind", kind).put("location", location));
            prefs.edit().putString("sync.sets", sets.toString()).apply();
        } catch (Exception e) { Log.w("PocketDeck", "Save set", e); }
    }

    /** The file-system path of a save folder, so one folder added both ways is kept once. */
    private static String savePath(String kind, String location) {
        if ("root".equals(kind)) return location.replaceFirst("/+$", "");
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(location));
            String volume = id.substring(0, id.indexOf(':')), rest = id.substring(id.indexOf(':') + 1);
            return ((volume.equals("primary") ? "/storage/emulated/0/" : "/storage/" + volume + "/") + rest).replaceFirst("/+$", "");
        } catch (Exception e) { return location; }
    }

    /** Emulator save folders that exist on this device, found through root. */
    private void findSaveFolders() {
        saveWorker.execute(() -> {
            String data = "/storage/emulated/0/Android/data/";
            String ra = Root.run("grep '^savefile_directory' " + data + Systems.RETROARCH + "/files/retroarch.cfg", 5);
            String raSaves = ra != null && ra.contains("\"/") ? ra.substring(ra.indexOf('"') + 1, ra.lastIndexOf('"')) : data + Systems.RETROARCH + "/files/saves";
            String[][] known = {
                { "PPSSPP", "/storage/emulated/0/PPSSPP/PSP/SAVEDATA" },
                { "PPSSPP", data + "org.ppsspp.ppsspp/files/PSP/SAVEDATA" },
                { "DuckStation", data + "com.github.stenzek.duckstation/files/memcards" },
                { "NetherSX2", data + "xyz.aethersx2.android/files/memcards" },
                { "RetroArch", raSaves },
                { "M64Plus FZ", data + "org.mupen64plusae.v3.fzurita/files/GameData" },
                { "Dolphin GameCube", data + "org.dolphinemu.dolphinemu/files/GC" },
                { "Dolphin Wii", data + "org.dolphinemu.dolphinemu/files/Wii/title" },
                { "Azahar", data + "org.azahar_emu.azahar/files/sdmc" },
            };
            int added = 0;
            for (String[] k : known) {
                String out = Root.run("[ -d " + SaveSync.quote(k[1]) + " ] && echo yes", 5);
                if (out != null && out.startsWith("yes")) { int before = saveSets().length(); addSaveSet(k[0], "root", k[1]); if (saveSets().length() > before) added++; }
            }
            toast(added == 0 ? "No new emulator save folders found." : "Added " + added + (added == 1 ? " save folder." : " save folders."));
            sendState();
        });
    }

    /** Syncs every save folder; `quiet` runs (after playing, at start) only report problems. */
    private void syncSaves(boolean quiet) {
        String folder = prefs.getString("sync.folder", "");
        if (folder.isEmpty() || syncing) return;
        syncing = true;
        sendState();
        saveWorker.execute(() -> {
            int up = 0, down = 0;
            List<String> problems = new ArrayList<>();
            try {
                SaveSync.SafStore mirror = SaveSync.SafStore.of(getContentResolver(), Uri.parse(folder)).dir(SaveSync.ROOT);
                JSONArray sets = saveSets();
                String device = Build.MANUFACTURER + " " + Build.MODEL;
                for (int i = 0; i < sets.length(); i++) {
                    JSONObject set = sets.getJSONObject(i);
                    String kind = set.optString("kind"), location = set.optString("location"), label = set.optString("label");
                    if ("root".equals(kind) && !Root.enabled(this)) { problems.add(label + ": root features are off"); continue; }
                    SaveSync.Store local = "root".equals(kind) ? new SaveSync.RootStore(location) : SaveSync.SafStore.of(getContentResolver(), Uri.parse(location));
                    SaveSync.Result r = SaveSync.sync(local, mirror.dir(label), device, getSharedPreferences("savecache", 0), label);
                    up += r.up; down += r.down;
                    if (r.error != null) problems.add(label + ": " + r.error);
                }
            } catch (Exception e) {
                problems.add(e.getMessage() == null ? "the sync folder cannot be opened" : e.getMessage());
                Log.w("PocketDeck", "Save sync", e);
            }
            String summary = problems.isEmpty() ? (up + down == 0 ? "Up to date" : up + " sent, " + down + " received") : "Problem with " + problems.get(0);
            prefs.edit().putLong("sync.last", System.currentTimeMillis()).putString("sync.status", summary).apply();
            syncing = false;
            if (!quiet || !problems.isEmpty() || down > 0) toast("Saves: " + summary + ".");
            sendState();
        });
    }

    private JSONObject savesState() throws Exception {
        JSONArray sets = new JSONArray();
        JSONArray raw = saveSets();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject s = raw.getJSONObject(i);
            String where = "root".equals(s.optString("kind")) ? s.optString("location").replace("/storage/emulated/0/", "") : folderName(s.optString("location"));
            sets.put(new JSONObject().put("label", s.optString("label")).put("where", where));
        }
        String folder = prefs.getString("sync.folder", "");
        return new JSONObject().put("folder", folder.isEmpty() ? "" : folderName(folder)).put("sets", sets)
            .put("auto", prefs.getBoolean("sync.auto", true)).put("syncing", syncing)
            .put("last", prefs.getLong("sync.last", 0)).put("status", prefs.getString("sync.status", ""));
    }

    /** Copies another frontend's covers, videos, titles, favorites and hidden flags for the games in the library. */
    private void importMedia(Uri tree) {
        toast("Reading media… this can take a minute for large collections.");
        worker.execute(() -> {
            try {
                JSONArray all = games();
                HashSet<String> stems = new HashSet<>(), keys = new HashSet<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject g = all.getJSONObject(i);
                    String stem = MediaImport.stem(g.optString("filename")).toLowerCase(Locale.ROOT);
                    stems.add(stem);
                    keys.add(MediaImport.key(g.optString("system", "psp"), stem));
                }
                MediaImport.Found found = MediaImport.scan(getContentResolver(), tree,
                    (sys, stem) -> sys == null ? stems.contains(stem.toLowerCase(Locale.ROOT)) : keys.contains(MediaImport.key(sys, stem)));
                int covers = 0, videos = 0, titles = 0;
                SharedPreferences.Editor edit = prefs.edit();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject g = all.getJSONObject(i);
                    String id = g.getString("id"), sys = g.optString("system", "psp"), stem = MediaImport.stem(g.optString("filename"));
                    Uri cover = MediaImport.lookup(found.covers, sys, stem);
                    if (cover != null && saveImage(cover, new File(artDir, id + "-box.jpg"), 1200)) covers++;
                    Uri clip = MediaImport.lookup(found.videos, sys, stem);
                    if (clip != null && copyVideo(clip, new File(artDir, id + "-video.mp4"))) videos++;
                    MediaImport.Meta meta = MediaImport.lookup(found.meta, sys, stem);
                    if (meta != null) {
                        if (meta.name != null && !meta.name.isEmpty()) { edit.putString("title." + id, meta.name); titles++; }
                        if (meta.favorite) edit.putBoolean("favorite." + id, true);
                        if (meta.hidden) edit.putBoolean("hidden." + id, true);
                    }
                }
                edit.apply();
                toast(covers + videos + titles == 0 ? "Nothing in that folder matched your games. Pick the ES-DE folder, or a media folder with covers named like your game files."
                    : "Imported " + covers + " covers, " + videos + " videos and " + titles + " titles.");
                sendState();
            } catch (Exception e) {
                Log.w("PocketDeck", "Media import", e);
                toast("That folder could not be read.");
            }
        });
    }

    /** Game preview clips are copied as they are; anything over 80 MB is skipped. */
    private boolean copyVideo(Uri from, File target) {
        File temp = new File(target.getPath() + ".part");
        try (InputStream in = getContentResolver().openInputStream(from); FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > 80L << 20) throw new IOException("Video too large");
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w("PocketDeck", "Video copy", e);
            temp.delete();
            return false;
        }
        return temp.renameTo(target);
    }

    private boolean saveImage(Uri uri, File target, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, opts); }
            if (bitmap == null) return false;
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            float scale = Math.min(1f, (float) maxSide / Math.max(w, h));
            if (scale < 1f) bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(w * scale), Math.round(h * scale), true);
            try (FileOutputStream out = new FileOutputStream(target)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out); }
            bitmap.recycle();
            return true;
        } catch (Exception e) {
            Log.w("PocketDeck", "Image", e);
            return false;
        }
    }

    private synchronized void scan() {
        if (scanning) return;
        List<String> list = folders();
        if (list.isEmpty()) { runOnUiThread(this::pickFolder); return; }
        scanning = true;
        scanMessage = "Reading your game library…";
        sendState();
        worker.execute(() -> {
            try {
                JSONArray found = new JSONArray();
                List<String> broken = new ArrayList<>();
                Map<String, JSONObject> known = new HashMap<>();
                JSONArray previous = games();
                for (int i = 0; i < previous.length(); i++) {
                    JSONObject g = previous.optJSONObject(i);
                    if (g != null) known.put(g.optString("id"), g);
                }
                for (String folder : list) {
                    try {
                        Uri uri = Uri.parse(folder);
                        String leaf = folderName(folder);
                        Systems.System root = Systems.forFolder(leaf.substring(leaf.lastIndexOf('/') + 1).trim());
                        walk(uri, DocumentsContract.getTreeDocumentId(uri), 0, found, new int[] { 0 }, root == null ? Systems.PSP : root, known);
                    } catch (Exception e) {
                        Log.w("PocketDeck", "Scan " + folder, e);
                        broken.add(folderName(folder));
                    }
                }
                prefs.edit().putString("games", found.toString()).apply();
                scanMessage = broken.isEmpty() ? found.length() + " games ready" : "Cannot read " + broken.get(0) + ". Choose it again to restore access.";
            } catch (Exception e) {
                scanMessage = "Library scan failed.";
                Log.w("PocketDeck", "Scan", e);
            } finally {
                scanning = false;
                sendState();
                afterScan();
            }
        });
    }

    /** Work that follows every scan (artwork downloads). */
    private void afterScan() { if (prefs.getBoolean("autoArt", true)) fetchCovers(false); }

    private final ExecutorService artWorker = Executors.newSingleThreadExecutor();
    private volatile boolean fetchingArt;

    /**
     * Downloads box art for games that have neither their own cover nor a downloaded one. Games
     * with no match are not asked about again for a month unless `retry` (the Settings button).
     */
    private void fetchCovers(boolean retry) {
        if (fetchingArt) return;
        fetchingArt = true;
        artWorker.execute(() -> {
            int found = 0, missing = 0;
            try {
                CoverArt art = new CoverArt(new File(getCacheDir(), "thumbnail-index"));
                JSONArray all = games();
                long now = System.currentTimeMillis();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject g = all.getJSONObject(i);
                    String id = g.getString("id");
                    if (new File(artDir, id + "-box.jpg").exists() || new File(artDir, id + "-cover.jpg").exists()) continue;
                    String dir = CoverArt.dirFor(g.optString("system", "psp"), g.optString("filename"));
                    if (dir == null) continue;
                    if (!retry && now - prefs.getLong("boxart.miss." + id, 0) < 30L * 24 * 60 * 60 * 1000) continue;
                    if (!"Offline".equals(networkName())) {
                        String name = CoverArt.match(art.index(dir), g.optString("filename"), g.optString("title"));
                        if (name != null && CoverArt.download(dir, name, new File(artDir, id + "-box.jpg"))) {
                            found++;
                            prefs.edit().remove("boxart.miss." + id).apply();
                            if (found % 4 == 0) sendState();
                            continue;
                        }
                    }
                    missing++;
                    prefs.edit().putLong("boxart.miss." + id, now).apply();
                }
            } catch (Exception e) {
                Log.w("PocketDeck", "Cover art", e);
            } finally {
                fetchingArt = false;
                if (found > 0) sendState();
                if (retry) toast(found > 0 ? "Found " + found + (found == 1 ? " cover" : " covers") + (missing > 0 ? "; " + missing + " still missing." : ".")
                    : missing > 0 ? "No covers found for " + missing + (missing == 1 ? " game." : " games.") : "Every game already has a cover.");
            }
        });
    }

    /** Folders that hold emulator data or scraped media rather than games. */
    private static final HashSet<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
        "bios", "system", "saves", "save", "savedata", "states", "savestates", "screenshots", "media", "downloaded_media",
        "images", "videos", "manuals", "cheats", "textures", "shaders", "covers", "snap", "snaps", "gamelists", "themes", "firmware"));
    private static final java.util.regex.Pattern DISC = java.util.regex.Pattern.compile("(?i)\\s*\\((?:disc|disk|cd) ?(\\d+)[^)]*\\)");
    private static final int MAX_GAMES = 3000;

    /** Library title from a file name: "0627 - Moto GP (USA) (v1.02).iso" -> "Moto GP". */
    static String cleanTitle(String name) {
        return name.replaceFirst("\\.[^.]+$", "").replaceFirst("^\\d{3,5} - ", "").replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s*\\[[^\\]]*\\]", "").trim();
    }

    /** The bracketed tags that tell versions apart: "(USA) (v1.02)". */
    static String versionLabel(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\([^)]*\\)|\\[[^\\]]*\\]").matcher(name.replaceFirst("\\.[^.]+$", ""));
        StringBuilder sb = new StringBuilder();
        while (m.find()) { if (DISC.matcher(m.group()).matches()) continue; if (sb.length() > 0) sb.append(' '); sb.append(m.group()); }
        return sb.toString();
    }

    private void walk(Uri tree, String documentId, int depth, JSONArray out, int[] visited, Systems.System system, Map<String, JSONObject> known) throws Exception {
        if (depth > 8) throw new IOException("Folder exceeds scan limit");
        if (visited[0] > 20000) throw new IOException("Folder exceeds scan limit");
        List<String[]> files = new ArrayList<>();   // document id, name, size
        try (Cursor cursor = getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId),
                new String[] { "document_id", "_display_name", "mime_type", "_size" }, null, null, null)) {
            if (cursor == null) throw new IOException("No folder access");
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Stopped");
                visited[0]++;
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // A folder named after a console ("N64", "PS2", "snes") tags everything inside it.
                    Systems.System sub = Systems.forFolder(name);
                    if (sub == null && SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) continue;
                    walk(tree, id, depth + 1, out, visited, sub == null ? system : sub, known);
                    continue;
                }
                files.add(new String[] { id, name, cursor.isNull(3) ? "0" : cursor.getString(3) });
            }
        }
        // Files that are part of another entry: discs listed in a playlist, tracks of a cue sheet,
        // and every disc after the first of a set that has no playlist.
        HashSet<String> hidden = new HashSet<>();
        Map<String, Integer> discCount = new HashMap<>();
        Map<String, String[]> firstDisc = new HashMap<>();
        for (String[] f : files) {
            String lower = f[1].toLowerCase(Locale.ROOT);
            if (lower.endsWith(".m3u") && system.accepts(lower)) {
                try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                        getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, f[0])), "UTF-8"))) {
                    for (String line; (line = in.readLine()) != null; ) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        hidden.add(line.substring(Math.max(line.lastIndexOf('/'), line.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT));
                    }
                } catch (Exception e) { Log.w("PocketDeck", "Playlist " + f[1], e); }
            } else if (lower.endsWith(".cue") || lower.endsWith(".gdi")) {
                String base = lower.substring(0, lower.length() - 4);
                for (String[] g : files) {
                    String other = g[1].toLowerCase(Locale.ROOT);
                    if ((other.endsWith(".bin") || other.endsWith(".raw")) && other.startsWith(base)) hidden.add(other);
                }
            }
        }
        for (String[] f : files) {
            String lower = f[1].toLowerCase(Locale.ROOT);
            if (hidden.contains(lower) || !system.accepts(lower)) continue;
            java.util.regex.Matcher m = DISC.matcher(f[1]);
            if (!m.find()) continue;
            String set = DISC.matcher(lower).replaceAll("");
            int disc = Integer.parseInt(m.group(1));
            discCount.put(set, discCount.containsKey(set) ? discCount.get(set) + 1 : 1);
            String[] best = firstDisc.get(set);
            if (best == null) { firstDisc.put(set, f); continue; }
            java.util.regex.Matcher bm = DISC.matcher(best[1]);
            bm.find();
            if (disc < Integer.parseInt(bm.group(1))) { hidden.add(best[1].toLowerCase(Locale.ROOT)); firstDisc.put(set, f); }
            else hidden.add(lower);
        }
        for (String[] f : files) {
            String name = f[1], lower = name.toLowerCase(Locale.ROOT);
            if (!system.accepts(lower) || hidden.contains(lower)) continue;
            if (lower.contains("[bios]") || lower.contains("(bios)") || lower.startsWith("scph")) continue;
            if (out.length() >= MAX_GAMES) throw new IOException("More than " + MAX_GAMES + " games");
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, f[0]);
            String key = hash(doc.toString());
            JSONObject previous = known.get(key);
            GameArt.Result art;
            if (system != Systems.PSP) {
                art = new GameArt.Result();   // only PSP discs carry their own icon and title
            } else if (previous == null || (previous.optString("icon").isEmpty() && previous.optString("background").isEmpty()) || previous.optString("discId").isEmpty()) {
                art = GameArt.extract(getContentResolver(), doc, artDir, key);   // also (re)reads DISC_ID for older entries
            } else {
                art = new GameArt.Result();
                art.title = previous.optString("title");
                art.discId = previous.optString("discId");
                art.icon = previous.optString("icon");
                art.background = previous.optString("background");
            }
            String title = cleanTitle(name);
            if (!art.title.isEmpty()) title = art.title;
            String set = DISC.matcher(lower).replaceAll("");
            JSONObject entry = new JSONObject().put("id", key).put("uri", doc.toString()).put("title", title).put("filename", name)
                .put("discId", art.discId).put("icon", art.icon).put("background", art.background).put("system", system.id)
                .put("size", Long.parseLong(f[2])).put("format", lower.substring(lower.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT))
                .put("group", system.id + "|" + title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ""))
                .put("version", versionLabel(name));
            if (discCount.containsKey(set) && discCount.get(set) > 1) entry.put("discs", discCount.get(set));
            out.put(entry);
        }
    }

    /** Lower is preferred when several versions of a game share a title. */
    private static int regionRank(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("usa") || l.contains("(u)")) return 0;
        if (l.contains("world")) return 1;
        if (l.contains("europe") || l.contains("(e)")) return 2;
        if (l.contains("japan") || l.contains("(j)")) return 4;
        return 3;
    }

    /**
     * Folds versions of the same game into one entry: the version the user picked, else the
     * best region. Others are marked alt (kept for launching and the version chooser).
     */
    private void foldVersions(JSONArray games) throws Exception {
        Map<String, List<JSONObject>> groups = new HashMap<>();
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.getJSONObject(i);
            String group = g.optString("group");
            if (group.isEmpty()) continue;
            if (!groups.containsKey(group)) groups.put(group, new ArrayList<>());
            groups.get(group).add(g);
        }
        for (Map.Entry<String, List<JSONObject>> e : groups.entrySet()) {
            List<JSONObject> members = e.getValue();
            if (members.size() < 2) continue;
            String picked = prefs.getString("version." + e.getKey(), "");
            JSONObject primary = null;
            for (JSONObject g : members) if (g.optString("id").equals(picked)) primary = g;
            if (primary == null) {
                for (JSONObject g : members) {
                    if (primary == null || regionRank(g.optString("version")) < regionRank(primary.optString("version"))) primary = g;
                }
            }
            JSONArray versions = new JSONArray();
            for (JSONObject g : members) {
                versions.put(new JSONObject().put("id", g.optString("id")).put("label", g.optString("version").isEmpty() ? g.optString("filename") : g.optString("version")));
                if (g != primary) g.put("alt", true);
            }
            primary.put("versions", versions);
        }
    }

    // ---- launching ----

    private void recordPlay(String id) {
        prefs.edit().putLong("played." + id, System.currentTimeMillis()).putLong("sync.launched", System.currentTimeMillis()).putInt("plays." + id, prefs.getInt("plays." + id, 0) + 1).apply();
    }

    private void launch(String id) {
        if (id.startsWith("app:")) {
            long started = System.currentTimeMillis();
            if (openApp(id.substring(4))) { playTracker.begin(id, id.substring(4), started); recordPlay(id); sendState(); }
            return;
        }
        JSONObject game = findGame(id);
        if (game == null) { toast("Game is no longer in the library. Refresh your library."); return; }
        Systems.System system = Systems.byId(game.optString("system", "psp"));
        Systems.Choice choice = Systems.resolve(this, system, prefs.getString("emu.game." + id, null), prefs.getString("emu.system." + system.id, null));
        if (choice == null) {
            toast("Install " + system.choices.get(0).emulator.name + " to play " + system.name + " games. Settings › Library lists the options.");
            return;
        }
        Uri uri = Uri.parse(game.optString("uri"));
        // Play gets its own thread: the shared worker may be mid-scan or waiting on root.
        new Thread(() -> {
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd == null) throw new IOException();
            } catch (Exception e) {
                toast("This game cannot be opened. Reconnect its folder in Settings.");
                return;
            }
            if (choice.emulator == Systems.PPSSPP) patchEmulatorConfig(game);
            runOnUiThread(() -> {
                try {
                    long started = System.currentTimeMillis();
                    startActivity(Systems.launch(this, system, choice, uri));
                    playTracker.begin(id, choice.emulator.installed(this), started);
                    recordPlay(id);
                    sendState();
                } catch (Exception e) {
                    Log.w("PocketDeck", "Launch " + system.id + " with " + choice.key(), e);
                    String why = e instanceof IllegalArgumentException ? e.getMessage() : e.getClass().getSimpleName();
                    toast(choice.emulator.name + " could not open this game: " + why);
                }
            });
        }, "pocketdeck-launch").start();
    }

    /** Deletes a PSP game file through the folder grant (Android games go through the system uninstaller). */
    private void deleteGame(String id) {
        if (id.startsWith("app:")) {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + id.substring(4))));
            } catch (Exception e) { toast("This app cannot be uninstalled from here."); }
            return;
        }
        JSONObject game = findGame(id);
        if (game == null) return;
        worker.execute(() -> {
            boolean removed;
            try {
                removed = DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(game.optString("uri")));
            } catch (Exception e) {
                Log.w("PocketDeck", "Delete", e);
                removed = false;
            }
            if (!removed) { toast("Could not delete this game file. Reconnect its folder in Settings."); return; }
            JSONArray kept = new JSONArray();
            JSONArray all = games();
            for (int i = 0; i < all.length(); i++) {
                JSONObject g = all.optJSONObject(i);
                if (g != null && !id.equals(g.optString("id"))) kept.put(g);
            }
            prefs.edit().putString("games", kept.toString())
                .remove("favorite." + id).remove("played." + id).remove("plays." + id).apply();
            for (String suffix : new String[] { "-icon.jpg", "-bg.jpg", "-cover.jpg", "-box.jpg", "-video.mp4" }) new File(artDir, id + suffix).delete();
            toast("Deleted " + game.optString("title") + ".");
            sendState();
        });
    }

    private boolean openApp(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) { toast("This app is no longer installed."); return false; }
        try { startActivity(intent); return true; } catch (Exception e) { toast("This app could not be opened."); return false; }
    }

    private void settings(String which) {
        String action;
        switch (which) {
            case "usage": action = Settings.ACTION_USAGE_ACCESS_SETTINGS; break;
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "home": action = Settings.ACTION_HOME_SETTINGS; break;
            case "notifications": action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS; break;
            case "wallpaper": action = WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER; break;
            default: action = Settings.ACTION_SETTINGS; break;
        }
        try { startActivity(new Intent(action)); }
        catch (Exception e) {
            // Not every ROM ships a live wallpaper chooser; fall back to the general wallpaper picker.
            if (WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER.equals(action)) {
                try { startActivity(new Intent(Intent.ACTION_SET_WALLPAPER)); return; } catch (Exception ignored) { }
            }
            toast("This setting is unavailable.");
        }
    }

    // ---- JS bridge ----

    final class Bridge {
        @JavascriptInterface public void setRootFeatures(boolean enabled) {
            prefs.edit().putBoolean("rootFeatures", enabled).apply();
            sendState();
        }
        @JavascriptInterface public void ready() { sendState(); sendMedia(); }
        /** Internal storage totals plus what the launcher itself keeps (artwork, video themes). */
        @JavascriptInterface public String storage() {
            try {
                android.os.StatFs fs = new android.os.StatFs(getFilesDir().getAbsolutePath());
                JSONObject out = new JSONObject();
                out.put("total", fs.getTotalBytes()).put("free", fs.getAvailableBytes());
                out.put("themes", dirSize(new File(artDir, "themes")));
                out.put("art", dirSize(artDir) - dirSize(new File(artDir, "themes")));
                return out.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void chooseFolder() { runOnUiThread(MainActivity.this::pickFolder); }
        @JavascriptInterface public void removeFolder(String uri) {
            List<String> list = folders();
            if (list.remove(uri)) { saveFolders(list); scan(); }
        }
        @JavascriptInterface public void refresh() { scan(); loadApps(); }
        @JavascriptInterface public void play(String id) { runOnUiThread(() -> launch(id)); }
        @JavascriptInterface public void favorite(String id) {
            if (!id.startsWith("app:") && findGame(id) == null) return;
            prefs.edit().putBoolean("favorite." + id, !prefs.getBoolean("favorite." + id, false)).apply();
            sendState();
        }
        @JavascriptInterface public void setAutoArt(boolean on) {
            prefs.edit().putBoolean("autoArt", on).apply();
            sendState();
            if (on) fetchCovers(false);
        }
        @JavascriptInterface public void findCovers() { fetchCovers(true); }
        @JavascriptInterface public void chooseSyncFolder() { pickTree(PICK_SYNC, "primary%3ASync"); }
        @JavascriptInterface public void addSaveFolder() { pickTree(PICK_SAVES, "primary%3APPSSPP%2FPSP%2FSAVEDATA"); }
        @JavascriptInterface public void findSaveFolders() {
            if (Root.enabled(MainActivity.this)) MainActivity.this.findSaveFolders();
            else toast("Emulators keep most saves in Android/data, which needs root features. Add other folders with Add save folder.");
        }
        @JavascriptInterface public void removeSaveSet(String label) {
            JSONArray sets = saveSets(), kept = new JSONArray();
            for (int i = 0; i < sets.length(); i++) if (!label.equals(sets.optJSONObject(i).optString("label"))) kept.put(sets.optJSONObject(i));
            prefs.edit().putString("sync.sets", kept.toString()).apply();
            sendState();
        }
        @JavascriptInterface public void clearSyncFolder() { prefs.edit().remove("sync.folder").remove("sync.status").remove("sync.last").apply(); sendState(); }
        @JavascriptInterface public void setSaveAuto(boolean on) { prefs.edit().putBoolean("sync.auto", on).apply(); sendState(); }
        @JavascriptInterface public void syncSaves() {
            if (prefs.getString("sync.folder", "").isEmpty()) { toast("Choose a sync folder first."); return; }
            MainActivity.this.syncSaves(false);
        }
        @JavascriptInterface public void importMedia() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3AES-DE"));
                try { startActivityForResult(intent, PICK_MEDIA); } catch (Exception e) { toast("The Android folder picker is unavailable."); }
            });
        }
        @JavascriptInterface public void hide(String id, boolean hidden) {
            if (findGame(id) == null) return;
            if (hidden) prefs.edit().putBoolean("hidden." + id, true).apply(); else prefs.edit().remove("hidden." + id).apply();
            sendState();
        }
        /** Which version of a game (region, revision) its library entry plays. */
        @JavascriptInterface public void setVersion(String id) {
            JSONObject g = findGame(id);
            if (g == null || g.optString("group").isEmpty()) return;
            prefs.edit().putString("version." + g.optString("group"), id).apply();
            sendState();
        }
        @JavascriptInterface public void fpsPatch(String id, boolean on) {
            if (findGame(id) == null) return;
            prefs.edit().putBoolean("fps60." + id, on).apply();
            sendState();
        }
        /** Pins the emulator for a whole system ("system") or one game ("game"); an empty key goes back to automatic. */
        @JavascriptInterface public void setEmulator(String scope, String id, String key) {
            String pref = "game".equals(scope) ? "emu.game." + id : "emu.system." + id;
            if ("game".equals(scope) && findGame(id) == null) return;
            if (key == null || key.isEmpty()) prefs.edit().remove(pref).apply();
            else prefs.edit().putString(pref, key).apply();
            sendState();
        }
        /** Opens an emulator's official page; only sites from the built-in catalogue can be opened. */
        @JavascriptInterface public void emulatorSite(String emulatorId) {
            Systems.Emulator e = Systems.emulatorById(emulatorId);
            if (e == null || e.site == null) return;
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ex) { toast("No browser is available to open " + e.site); }
            });
        }
        /** Opens the emulator currently chosen for a system, for its own settings (BIOS, controls). */
        @JavascriptInterface public void openEmulator(String systemId) {
            Systems.System s = Systems.byId(systemId);
            Systems.Choice c = Systems.resolve(MainActivity.this, s, null, prefs.getString("emu.system." + s.id, null));
            if (c != null) runOnUiThread(() -> MainActivity.this.openApp(c.emulator.installed(MainActivity.this)));
        }
        @JavascriptInterface public void settings(String which) { runOnUiThread(() -> MainActivity.this.settings(which)); }
        @JavascriptInterface public void openApp(String pkg) { runOnUiThread(() -> MainActivity.this.openApp(pkg)); }
        @JavascriptInterface public void deleteGame(String id) { runOnUiThread(() -> MainActivity.this.deleteGame(id)); }
        @JavascriptInterface public void pickCover(String id) {
            if (!id.startsWith("app:") && findGame(id) == null) return;
            pendingCoverId = id;
            runOnUiThread(() -> pickImage(PICK_COVER));
        }
        @JavascriptInterface public void pickPreview(String id) {
            if (findGame(id) == null) return;
            pendingCoverId = id;
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4");
                try { startActivityForResult(intent, PICK_VIDEO); } catch (Exception e) { toast("The video picker is unavailable."); }
            });
        }
        @JavascriptInterface public void clearPreview(String id) {
            if (findGame(id) == null) return;
            new File(artDir, id + "-video.mp4").delete();
            sendState();
        }
        @JavascriptInterface public void clearCover(String id) {
            try { new File(artDir, artKey(id) + "-cover.jpg").delete(); } catch (Exception ignored) { }
            sendState();
        }
        @JavascriptInterface public void showWallpaper(boolean show) { runOnUiThread(() -> MainActivity.this.showWallpaper(show)); }
        @JavascriptInterface public void pickWallpaper() { runOnUiThread(() -> pickImage(PICK_WALLPAPER)); }
        @JavascriptInterface public void clearWallpaper() { new File(artDir, "wallpaper.jpg").delete(); sendState(); }
        @JavascriptInterface public void blackWallpaper() {
            worker.execute(() -> toast(setBlackWallpaper() ? "Phone wallpaper set to black." : "Could not change the wallpaper."));
        }
        @JavascriptInterface public void media(String action) {
            runOnUiThread(() -> {
                if ("open".equals(action)) {
                    String pkg = media.sessionPackage();
                    MainActivity.this.openApp(pkg != null ? pkg : "com.spotify.music");
                } else if ("access".equals(action)) {
                    try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                    catch (Exception e) { toast("Open Android Settings and enable Nomad notification access."); }
                } else {
                    media.control(action);
                }
            });
        }
    }
}
