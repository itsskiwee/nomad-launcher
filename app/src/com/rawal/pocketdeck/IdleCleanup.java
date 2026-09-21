package com.rawal.pocketdeck;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Called off the UI thread; the root controller rechecks visibility and playback. */
final class IdleCleanup {
    static void run(Context context) {
        try {
            File script = new File(context.getFilesDir(), "idle-cleanup.sh");
            if (!script.exists()) {
                try (InputStream in = context.getAssets().open("idle-cleanup.sh");
                     FileOutputStream out = new FileOutputStream(script)) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
            String result = Root.run("sh '" + script.getAbsolutePath() + "'", 10);
            if (result != null && !result.trim().isEmpty()) Log.i("PocketDeck", result.trim());
        } catch (Exception e) { Log.w("PocketDeck", "Idle cleanup skipped", e); }
    }

    static void reset(Context context) {
        // Always refresh the private controller after an APK update / activity recreation.
        new File(context.getFilesDir(), "idle-cleanup.sh").delete();
    }
}
