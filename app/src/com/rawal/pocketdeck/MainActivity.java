package com.rawal.pocketdeck;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
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
    private static final int PICK_FOLDER = 41, PICK_COVER = 42, PICK_WALLPAPER = 43;
    private static final String[] EMULATORS = { "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold" };

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
    private volatile String emulator;
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

        web = new WebView(this);
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
                        if (name.matches("themes/[a-z0-9-]+\\.mp4")) return ThemeBridge.video(new File(artDir, name), request);
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
                    String id = g.getString("id");
                    g.put("favorite", prefs.getBoolean("favorite." + id, false));
                    g.put("lastPlayed", prefs.getLong("played." + id, 0L));
                    g.put("playtimeMs", playTracker.total(id));
                    g.put("lastSessionMs", playTracker.last(id));
                    g.put("plays", prefs.getInt("plays." + id, 0));
                    g.put("cover", coverFor(id));
                    g.put("fpsPatch", fpsPatchState(g));
                }
                JSONArray appList = new JSONArray();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject a = new JSONObject(apps.getJSONObject(i).toString());
                    String id = "app:" + a.getString("package");
                    a.put("id", id);
                    a.put("favorite", prefs.getBoolean("favorite." + id, false));
                    a.put("lastPlayed", prefs.getLong("played." + id, 0L));
                    a.put("playtimeMs", playTracker.total(id));
                    a.put("lastSessionMs", playTracker.last(id));
                    a.put("plays", prefs.getInt("plays." + id, 0));
                    a.put("cover", coverFor(id));
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
                state.put("batteryStats", batteryTracker.snapshot());
                state.put("usageAccess", playTracker.enabled());
                state.put("network", networkName());
                state.put("emulator", emulator != null);
                state.put("defaultHome", defaultHome);
                File wallpaper = new File(artDir, "wallpaper.jpg");
                state.put("wallpaper", wallpaper.exists() ? "wallpaper.jpg?v=" + wallpaper.lastModified() : "");
                state.put("mediaAccess", media.enabled());
                state.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                state.put("rootFeatures", Root.enabled(this));
                if (webGone) return;
                pageAskedAt = System.currentTimeMillis();
                web.evaluateJavascript("window.receiveState && window.receiveState(" + state + ")", value -> pageAnsweredAt = System.currentTimeMillis());
            } catch (Exception e) {
                Log.e("PocketDeck", "State update", e);
            }
        });
    }

    private String coverFor(String id) {
        try {
            String name = artKey(id) + "-cover.jpg";
            File f = new File(artDir, name);
            return f.exists() ? name + "?v=" + f.lastModified() : "";
        } catch (Exception e) {
            return "";
        }
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

    private String getEmulator() {
        for (String pkg : EMULATORS) {
            try { getPackageManager().getPackageInfo(pkg, 0); return pkg; } catch (Exception ignored) { }
        }
        return null;
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
        emulator = getEmulator();
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
                for (String folder : list) {
                    try {
                        Uri uri = Uri.parse(folder);
                        String leaf = folderName(folder);
                        Systems.System root = Systems.forFolder(leaf.substring(leaf.lastIndexOf('/') + 1).trim());
                        walk(uri, DocumentsContract.getTreeDocumentId(uri), 0, found, new int[] { 0 }, root == null ? Systems.PSP : root);
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
            }
        });
    }

    private void walk(Uri tree, String documentId, int depth, JSONArray out, int[] visited, Systems.System system) throws Exception {
        if (depth > 8) throw new IOException("Folder exceeds scan limit");
        if (visited[0] > 10000) throw new IOException("Folder exceeds scan limit");
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
                    walk(tree, id, depth + 1, out, visited, sub == null ? system : sub);
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!system.accepts(lower)) continue;
                if (out.length() >= 500) throw new IOException("More than 500 games");
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String key = hash(doc.toString());
                JSONObject known = findGame(key);
                GameArt.Result art;
                if (system != Systems.PSP) {
                    art = new GameArt.Result();   // only PSP discs carry their own icon and title
                } else if (known == null || (known.optString("icon").isEmpty() && known.optString("background").isEmpty()) || known.optString("discId").isEmpty()) {
                    art = GameArt.extract(getContentResolver(), doc, artDir, key);   // also (re)reads DISC_ID for older entries
                } else {
                    art = new GameArt.Result();
                    art.title = known.optString("title");
                    art.discId = known.optString("discId");
                    art.icon = known.optString("icon");
                    art.background = known.optString("background");
                }
                String title = name.replaceFirst("\\.[^.]+$", "").replaceFirst("^\\d{3,5} - ", "").replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s*\\[[^\\]]*\\]", "").trim();
                if (!art.title.isEmpty()) title = art.title;
                out.put(new JSONObject().put("id", key).put("uri", doc.toString()).put("title", title).put("filename", name)
                    .put("discId", art.discId).put("icon", art.icon).put("background", art.background).put("system", system.id).put("size", cursor.isNull(3) ? 0 : cursor.getLong(3))
                    .put("format", lower.substring(lower.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT)));
            }
        }
    }

    // ---- launching ----

    private void recordPlay(String id) {
        prefs.edit().putLong("played." + id, System.currentTimeMillis()).putInt("plays." + id, prefs.getInt("plays." + id, 0) + 1).apply();
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
        if (system != Systems.PSP) { launchOn(system, id, Uri.parse(game.optString("uri"))); return; }
        String emulator = this.emulator != null ? this.emulator : getEmulator();
        if (emulator == null) { toast("Install PPSSPP to play PSP games."); return; }
        Uri uri = Uri.parse(game.optString("uri"));
        // Play gets its own thread: the shared worker may be mid-scan or waiting on root.
        new Thread(() -> {
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd == null) throw new IOException();
            } catch (Exception e) {
                toast("This game cannot be opened. Reconnect its folder in Settings.");
                return;
            }
            patchEmulatorConfig(game);
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(emulator, "org.ppsspp.ppsspp.PpssppActivity")).setData(uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newRawUri("PSP game", uri));
                    long started = System.currentTimeMillis();
                    startActivity(intent);
                    playTracker.begin(id, emulator, started);
                    recordPlay(id);
                    sendState();
                } catch (Exception e) {
                    toast("PPSSPP could not open this game: " + e.getClass().getSimpleName());
                }
            });
        }, "pocketdeck-launch").start();
    }

    /** Hands a non-PSP game to its emulator (see Systems). */
    private void launchOn(Systems.System system, String id, Uri uri) {
        if (!Systems.installed(this, system.pkg)) { toast("Install " + emulatorName(system) + " to play " + system.name + " games."); return; }
        try {
            long started = System.currentTimeMillis();
            startActivity(Systems.launch(this, system, uri));
            playTracker.begin(id, system.pkg, started);
            recordPlay(id);
            sendState();
        } catch (Exception e) {
            Log.w("PocketDeck", "Launch " + system.id, e);
            toast(emulatorName(system) + " could not open this game: " + e.getClass().getSimpleName());
        }
    }

    private static String emulatorName(Systems.System system) {
        switch (system.pkg) {
            case Systems.RETROARCH: return "RetroArch";
            case "xyz.aethersx2.android": return "NetherSX2";
            case "com.github.stenzek.duckstation": return "DuckStation";
            case "org.mupen64plusae.v3.fzurita": return "M64Plus FZ";
            default: return "the emulator";
        }
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
            for (String suffix : new String[] { "-icon.jpg", "-bg.jpg", "-cover.jpg" }) new File(artDir, id + suffix).delete();
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
        @JavascriptInterface public void fpsPatch(String id, boolean on) {
            if (findGame(id) == null) return;
            prefs.edit().putBoolean("fps60." + id, on).apply();
            sendState();
        }
        @JavascriptInterface public void settings(String which) { runOnUiThread(() -> MainActivity.this.settings(which)); }
        @JavascriptInterface public void openApp(String pkg) { runOnUiThread(() -> MainActivity.this.openApp(pkg)); }
        @JavascriptInterface public void deleteGame(String id) { runOnUiThread(() -> MainActivity.this.deleteGame(id)); }
        @JavascriptInterface public void pickCover(String id) {
            if (!id.startsWith("app:") && findGame(id) == null) return;
            pendingCoverId = id;
            runOnUiThread(() -> pickImage(PICK_COVER));
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
