package com.rawal.pocketdeck;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;

/** Only attributes foreground emulator/app time within a Nomad launch session. */
final class PlayTracker {
    private final Context context;
    private final SharedPreferences prefs;
    PlayTracker(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("playtime", 0);
    }
    boolean enabled() {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    synchronized long total(String id) { return prefs.getLong("total." + id, 0); }
    synchronized long last(String id) { return prefs.getLong("last." + id, 0); }
    private int bootCount() {
        return android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
    }
    synchronized void begin(String id, String pkg, long started) {
        settle();
        prefs.edit().remove("id").apply();
        if (!enabled()) return;
        // Commit the launch marker before Android can reclaim the launcher process.
        prefs.edit().putString("id", id).putString("package", pkg).putLong("start", started)
            .putInt("boot", bootCount()).putLong("base", total(id)).putLong("counted", 0).remove("end").commit();
    }
    synchronized void settle() {
        String id = prefs.getString("id", "");
        if (id.isEmpty()) return;
        if (!enabled()) { prefs.edit().remove("id").apply(); return; }
        long start = prefs.getLong("start", 0), end = prefs.getLong("end", System.currentTimeMillis());
        prefs.edit().putLong("end", end).apply();
        if (prefs.getInt("boot", -1) != bootCount() || start <= 0 || end < start) { prefs.edit().remove("id").apply(); return; }
        try {
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = manager.queryEvents(start, end);
            if (events == null) return;
            String pkg = prefs.getString("package", "");
            UsageEvents.Event event = new UsageEvents.Event();
            PlayTimeline timeline = new PlayTimeline(pkg, context.getPackageName(), start, end);
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (!timeline.event(event.getEventType(), event.getPackageName(), event.getTimeStamp())) break;
            }
            long duration = Math.max(prefs.getLong("counted", 0), timeline.duration);
            // Do not invent time for an unclosed interval: late system events are retried next resume.
            prefs.edit().putLong("counted", duration).putLong("total." + id, prefs.getLong("base", 0) + duration)
                .putLong("last." + id, duration).commit();
        } catch (RuntimeException ignored) { /* Keep the marker for a later retry. */ }
    }
}
