package com.rawal.pocketdeck;

import android.app.Activity;
import android.app.Fragment;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Theme support for the page: the phone wallpaper's reported colours (so "Match background"
 * can follow a live wallpaper) and the video themes, one looping clip each in files/art/themes/.
 * The video picker runs through a headless fragment so this stays out of MainActivity.
 */
public final class ThemeBridge {
    private static final String THEMES = "themes";
    private final Activity activity;
    private final WebView web;
    private final File artDir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WallpaperManager.OnColorsChangedListener listener;

    private ThemeBridge(Activity activity, WebView web, File artDir) {
        this.activity = activity;
        this.web = web;
        this.artDir = artDir;
    }

    public static void attach(Activity activity, WebView web, File artDir) {
        web.addJavascriptInterface(new ThemeBridge(activity, web, artDir), "Theme");
    }

    /** Serves the video backdrop with byte ranges, which the WebView's media player needs to loop. */
    public static WebResourceResponse video(File file, WebResourceRequest request) throws IOException {
        long total = file.length(), start = 0, end = total - 1;
        String range = request.getRequestHeaders().get("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] parts = range.substring(6).split("-", 2);
            if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
        }
        if (start > end || start >= total) throw new IOException("bad range");
        long length = end - start + 1;
        FileInputStream in = new FileInputStream(file);
        long skipped = 0;
        while (skipped < start) { long n = in.skip(start - skipped); if (n <= 0) break; skipped += n; }
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Content-Length", String.valueOf(length));
        headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
        boolean partial = range != null;
        return new WebResourceResponse("video/mp4", null, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                headers, new Bounded(in, length));
    }

    /** Reads at most `left` bytes from the wrapped stream. */
    private static final class Bounded extends InputStream {
        private final InputStream in; private long left;
        Bounded(InputStream in, long left) { this.in = in; this.left = left; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int b = in.read(); if (b >= 0) left--; return b; }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    /** Slugs of the installed video themes, sorted; each is files/art/themes/<slug>.mp4. */
    private String themeList() {
        JSONArray out = new JSONArray();
        String[] names = new File(artDir, THEMES).list((dir, name) -> name.matches("[a-z0-9-]+\\.mp4"));
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) out.put(name.substring(0, name.length() - 4));
        }
        return out.toString();
    }

    private void js(String call) { main.post(() -> web.evaluateJavascript(call, null)); }

    private void sendThemes() { js("window.receiveThemes && window.receiveThemes(" + themeList() + ")"); }

    private void sendColors(WallpaperColors colors) {
        try {
            JSONObject out = new JSONObject();
            if (colors != null) {
                out.put("primary", hex(colors.getPrimaryColor()));
                if (colors.getSecondaryColor() != null) out.put("secondary", hex(colors.getSecondaryColor()));
                if (colors.getTertiaryColor() != null) out.put("tertiary", hex(colors.getTertiaryColor()));
            }
            js("window.receiveWallpaperColors && window.receiveWallpaperColors(" + out + ")");
        } catch (Exception e) {
            Log.w("PocketDeck", "Wallpaper colours", e);
        }
    }

    private static String hex(Color c) { return String.format("#%06x", c.toArgb() & 0xffffff); }

    @JavascriptInterface public String themes() { return themeList(); }

    /** Starts or stops following the system wallpaper's colours; the current ones are sent at once. */
    @JavascriptInterface public void watchWallpaper(boolean on) {
        main.post(() -> {
            WallpaperManager wm = WallpaperManager.getInstance(activity);
            if (listener != null) { wm.removeOnColorsChangedListener(listener); listener = null; }
            if (!on) return;
            listener = (colors, which) -> { if ((which & WallpaperManager.FLAG_SYSTEM) != 0) sendColors(colors); };
            wm.addOnColorsChangedListener(listener, main);
            try { sendColors(wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)); } catch (Exception e) { sendColors(null); }
        });
    }

    @JavascriptInterface public void pickVideo() {
        main.post(() -> {
            Picker picker = new Picker();
            picker.bridge = this;
            activity.getFragmentManager().beginTransaction().add(picker, "videoPicker").commitAllowingStateLoss();
        });
    }

    @JavascriptInterface public void removeTheme(String slug) {
        if (!slug.matches("[a-z0-9-]+")) return;
        new File(new File(artDir, THEMES), slug + ".mp4").delete();
        sendThemes();
    }

    /** The theme takes its name from the file: "Twilight at Fuji.3840x2160.mp4" -> twilight-at-fuji. */
    private String slugFor(Uri uri) {
        String name = "";
        try (Cursor c = activity.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        name = name.replaceAll("\\.[^.]*$", "").replaceAll("(?i)[._ -]*\\d{3,4}x\\d{3,4}$", "").replaceAll("(?i)[._ -]*\\d+k[._ -]*live$", "");
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? "video-" + System.currentTimeMillis() : slug;
    }

    private void save(Uri uri) {
        worker.execute(() -> {
            File dir = new File(artDir, THEMES);
            dir.mkdirs();
            File target = new File(dir, slugFor(uri) + ".mp4"), temp = new File(dir, target.getName() + ".part");
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                if (!temp.renameTo(target)) throw new IOException("rename");
                sendThemes();
            } catch (Exception e) {
                Log.w("PocketDeck", "Video", e);
                temp.delete();
                main.post(() -> Toast.makeText(activity, "That video could not be used.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Invisible fragment that owns the document picker round-trip. */
    public static final class Picker extends Fragment {
        ThemeBridge bridge;

        @Override public void onStart() {
            super.onStart();
            if (bridge == null) { remove(); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4");
            try { startActivityForResult(intent, 1); }
            catch (Exception e) { Toast.makeText(getActivity(), "The video picker is unavailable.", Toast.LENGTH_SHORT).show(); remove(); }
        }

        @Override public void onActivityResult(int request, int result, Intent data) {
            if (bridge != null && result == Activity.RESULT_OK && data != null && data.getData() != null) bridge.save(data.getData());
            remove();
        }

        private void remove() {
            if (getFragmentManager() != null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
