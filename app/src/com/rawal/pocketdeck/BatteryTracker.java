package com.rawal.pocketdeck;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;

/** Device-wide battery observations; no root or charger-rating assumptions. */
final class BatteryTracker {
    private long start = -1;
    private int initial = -1, previous = -1, status = BatteryManager.BATTERY_STATUS_UNKNOWN;
    private boolean plugged;
    synchronized void sample(Intent battery) {
        if (battery == null) return;
        int raw = battery.getIntExtra("level", -1), scale = battery.getIntExtra("scale", 100);
        if (raw < 0 || scale <= 0) return;
        int level = Math.min(100, Math.round(raw * 100f / scale));
        boolean connected = battery.getIntExtra("plugged", 0) != 0;
        int nextStatus = battery.getIntExtra("status", BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = nextStatus == BatteryManager.BATTERY_STATUS_CHARGING;
        if (start < 0 || connected != plugged || nextStatus != status
                || (charging ? level < previous : level > previous)) {
            start = SystemClock.elapsedRealtime(); initial = level;
        }
        previous = level; plugged = connected; status = nextStatus;
    }
    synchronized JSONObject snapshot(Context context, Intent battery) throws Exception {
        sample(battery);
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int current = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        int average = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        int charge = property(manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int voltage = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        double watts = BatteryMath.watts(current, voltage);
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL;
        boolean charging = !full && (BatteryMath.currentAvailable(current) && current != 0
            ? current > 0 : status == BatteryManager.BATTERY_STATUS_CHARGING);
        boolean discharging = !full && (BatteryMath.currentAvailable(current) && current != 0
            ? current < 0 : status == BatteryManager.BATTERY_STATUS_DISCHARGING);
        String flow = full ? "full" : charging ? "charging" : discharging ? "discharging" : "idle";
        long elapsed = start < 0 ? 0 : SystemClock.elapsedRealtime() - start;
        int change = initial < 0 ? 0 : previous - initial;
        boolean ready = elapsed >= 600000 && Math.abs(change) >= 2
            && (charging ? change > 0 : discharging && change < 0);
        double rate = ready ? Math.abs(change) * 3600000.0 / elapsed : 0;
        long remaining = -1;
        String source = "";
        if (charging && manager != null && Build.VERSION.SDK_INT >= 28) {
            try { remaining = manager.computeChargeTimeRemaining(); } catch (RuntimeException ignored) { }
            if (remaining > 0) source = "Android estimate"; else remaining = -1;
        }
        if (remaining < 0 && ready) {
            remaining = Math.round((charging ? 100 - previous : previous) / rate * 3600000);
            source = "Observed percentage change";
        }
        if (remaining < 0 && (charging || discharging)) {
            remaining = BatteryMath.remaining(previous, charge, average, charging);
            if (remaining >= 0) source = "Battery gauge average current";
        }
        return new JSONObject().put("observedMs", elapsed).put("lost", Math.max(0, -change))
            .put("change", change).put("ready", ready).put("rate", rate).put("flow", flow)
            .put("watts", Double.isFinite(watts) ? watts : JSONObject.NULL)
            .put("remainingMs", remaining).put("estimateSource", source);
    }
    private int property(BatteryManager manager, int id) {
        try { return manager == null ? Integer.MIN_VALUE : manager.getIntProperty(id); }
        catch (RuntimeException ignored) { return Integer.MIN_VALUE; }
    }
}
