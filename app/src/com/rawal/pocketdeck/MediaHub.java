package com.rawal.pocketdeck;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import org.json.JSONObject;

/** Tracks the active media session (Spotify first) and mirrors it to the page as now-playing state. */
final class MediaHub {
    interface Listener { void onMediaChanged(); }

    private static final String SPOTIFY = "com.spotify.music";
    private final Context context;
    private final File artDir;
    private final Listener listener;
    private final MediaSessionManager manager;
    private final ComponentName component;
    private MediaController controller;
    private String artKey = "";
    private volatile String artFetching = "";
    private int artVersion;
    private boolean started;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { listener.onMediaChanged(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { listener.onMediaChanged(); }
        @Override public void onSessionDestroyed() { pick(null); listener.onMediaChanged(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    MediaHub(Context context, File artDir, Listener listener) {
        this.context = context;
        this.artDir = artDir;
        this.listener = listener;
        this.manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.component = new ComponentName(context, MediaListener.class);
    }

    boolean enabled() {
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(component.flattenToString());
    }

    /** Grants listener access through root once, so the user never sees the system dialog. */
    void ensureAccess() {
        if (enabled()) return;
        Root.run("cmd notification allow_listener " + component.flattenToString());
    }

    void start() {
        if (started || !enabled()) return;
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component);
            pick(manager.getActiveSessions(component));
            started = true;
        } catch (SecurityException ignored) { }
    }

    void stop() {
        if (!started) return;
        manager.removeOnActiveSessionsChangedListener(sessions);
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        started = false;
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
                if (playing && (best == null || !isPlaying(best))) best = c;
                else if (best == null && SPOTIFY.equals(c.getPackageName())) best = c;
                else if (best == null) best = c;
            }
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = best;
        if (controller != null) controller.registerCallback(callback);
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    String sessionPackage() { return controller == null ? null : controller.getPackageName(); }

    JSONObject state() throws Exception {
        JSONObject json = new JSONObject().put("enabled", enabled()).put("active", controller != null);
        if (controller == null) return json;
        MediaMetadata m = controller.getMetadata();
        PlaybackState s = controller.getPlaybackState();
        json.put("app", controller.getPackageName());
        json.put("playing", s != null && s.getState() == PlaybackState.STATE_PLAYING);
        if (m != null) {
            json.put("title", m.getString(MediaMetadata.METADATA_KEY_TITLE));
            json.put("artist", m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String key = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) + "|" + m.getString(MediaMetadata.METADATA_KEY_ALBUM) + "|" + m.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (!key.equals(artKey)) {
                Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (art != null) {
                    if (saveArt(art)) artKey = key;
                } else {
                    // Spotify only publishes a URI; fetch it off the UI thread and notify again when done.
                    String uri = m.getString("com.spotify.music.extra.ART_HTTPS_URI");
                    if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                    if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
                    if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (uri != null && !uri.equals(artFetching)) fetchArt(uri, key);
                }
            }
            json.put("art", key.equals(artKey) && artVersion > 0 ? "media.jpg?v=" + artVersion : "");
        }
        return json;
    }

    private boolean saveArt(Bitmap art) {
        try (FileOutputStream out = new FileOutputStream(new File(artDir, "media.jpg"))) {
            Bitmap small = Bitmap.createScaledBitmap(art, 160, 160, true);
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            artVersion++;
            return true;
        } catch (Exception e) {
            android.util.Log.w("PocketDeck", "media art", e);
            return false;
        }
    }

    private void fetchArt(String uri, String key) {
        artFetching = uri;
        new Thread(() -> {
            Bitmap art = null;
            try {
                InputStream in;
                if (uri.startsWith("content://")) {
                    in = context.getContentResolver().openInputStream(android.net.Uri.parse(uri));
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                    c.setConnectTimeout(5000); c.setReadTimeout(5000);
                    in = c.getInputStream();
                }
                try (InputStream stream = in) { art = BitmapFactory.decodeStream(stream); }
            } catch (Exception e) { android.util.Log.w("PocketDeck", "art fetch " + uri, e); }
            if (art != null && saveArt(art)) artKey = key;
            artFetching = "";
            listener.onMediaChanged();
        }, "pocketdeck-art").start();
    }

    void control(String action) {
        if (controller == null) return;
        MediaController.TransportControls t = controller.getTransportControls();
        switch (action) {
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "toggle": if (isPlaying(controller)) t.pause(); else t.play(); break;
            case "next": t.skipToNext(); break;
            case "previous": t.skipToPrevious(); break;
            default: break;
        }
    }
}
