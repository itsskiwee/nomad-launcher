package com.rawal.pocketdeck;

import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONObject;

/** Discharge observations, scoped to uninterrupted receiver coverage in this process. */
final class BatteryTracker {
    private long start = -1;
    private int initial = -1, previous = -1;
    private boolean plugged;
    synchronized void sample(Intent battery) {
        if (battery == null) return;
        int raw = battery.getIntExtra("level", -1), scale = battery.getIntExtra("scale", 100);
        if (raw < 0 || scale <= 0) return;
        int level = Math.min(100, Math.round(raw * 100f / scale));
        boolean charging = battery.getIntExtra("plugged", 0) != 0;
        long now = SystemClock.elapsedRealtime();
        if (charging) { start = -1; initial = -1; }
        else if (start < 0 || plugged || level > previous) { start = now; initial = level; }
        previous = level; plugged = charging;
    }
    synchronized JSONObject snapshot() throws Exception {
        JSONObject out = new JSONObject();
        long elapsed = start < 0 ? 0 : SystemClock.elapsedRealtime() - start;
        int lost = initial < 0 ? 0 : Math.max(0, initial - previous);
        boolean ready = !plugged && elapsed >= 600000 && lost >= 2;
        double rate = ready ? lost * 3600000.0 / elapsed : 0;
        return out.put("observedMs", elapsed).put("lost", lost).put("ready", ready)
            .put("rate", rate).put("remainingMs", ready ? previous / rate * 3600000 : 0);
    }
}
