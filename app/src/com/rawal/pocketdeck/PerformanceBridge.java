package com.rawal.pocketdeck;

import android.app.Activity;
import android.os.Build;
import android.widget.Toast;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Narrow bridge: only known profile names can reach a private root session. */
public final class PerformanceBridge {
    private final Activity activity;
    private final WebView web;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile Process root;
    private BufferedReader reader;
    private BufferedWriter writer;
    private long sequence;

    private PerformanceBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        web.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { }
            public void onViewDetachedFromWindow(View view) {
                Process process = root;
                if (process != null) process.destroyForcibly();
                worker.shutdownNow();
                watchdog.shutdownNow();
            }
        });
    }
    public static void attach(Activity activity, WebView web) {
        web.addJavascriptInterface(new PerformanceBridge(activity, web), "Performance");
    }
    private final AtomicBoolean poweringOff = new AtomicBoolean();
    /** Powers the phone off immediately; the launcher's own button is the confirmation. */
    @JavascriptInterface public void shutdown() {
        if (!Root.enabled(activity)) return;
        if (activity.isDestroyed() || !poweringOff.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                Process command = new ProcessBuilder("su", "-c", "/system/bin/svc power shutdown").redirectErrorStream(true).start();
                if (!command.waitFor(15, TimeUnit.SECONDS)) {
                    command.destroyForcibly();
                    throw new IOException("Shutdown timed out");
                }
                if (command.exitValue() != 0) throw new IOException("Shutdown denied");
            } catch (Exception error) {
                poweringOff.set(false);
                activity.runOnUiThread(() -> Toast.makeText(activity, "Could not power off. Check root permission and retry.", Toast.LENGTH_LONG).show());
            }
        }, "pocketdeck-power").start();
    }
    /** Profiles are tuned to one SoC's tables; everywhere else only live readings are offered. */
    @JavascriptInterface public boolean profilesSupported() { return "begonia".equals(Build.DEVICE); }
    @JavascriptInterface public String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (maker.isEmpty() || model.toLowerCase().startsWith(maker.toLowerCase())) return model;
        return Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
    }
    @JavascriptInterface public void status() { run("status"); }
    @JavascriptInterface public void apply(String mode) {
        if (!profilesSupported()) return;
        if ("saver".equals(mode) || "balanced".equals(mode) || "performance".equals(mode) || "turbo".equals(mode)) run(mode);
    }
    private void run(String mode) {
        if (!Root.enabled(activity)) {
            activity.runOnUiThread(() -> web.evaluateJavascript("window.receivePerformance && window.receivePerformance({available:false,error:'Enable root features in Settings → Device to use these controls.'})", null));
            return;
        }
        if (worker.isShutdown() || !busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            JSONObject result = new JSONObject();
            ScheduledFuture<?> deadline = null;
            AtomicBoolean timedOut = new AtomicBoolean();
            try {
                File script = new File(activity.getFilesDir(), "performance-control.sh");
                if (root == null || !root.isAlive()) {
                    try (InputStream in = activity.getAssets().open("performance-control.sh");
                         OutputStream out = new FileOutputStream(script)) {
                        byte[] buffer = new byte[8192]; int length;
                        while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                    }
                    root = new ProcessBuilder("su", "-c", "/system/bin/sh").redirectErrorStream(true).start();
                    reader = new BufferedReader(new InputStreamReader(root.getInputStream()));
                    writer = new BufferedWriter(new OutputStreamWriter(root.getOutputStream()));
                }
                Process session = root;
                deadline = watchdog.schedule(() -> { timedOut.set(true); session.destroyForcibly(); }, 25, TimeUnit.SECONDS);
                String path = "'" + script.getAbsolutePath().replace("'", "'\\''") + "'";
                String marker = "POCKETDECK_DONE_" + (++sequence) + "=";
                writer.write("/system/bin/sh " + path + " " + mode + "; echo " + marker + "$?\n");
                writer.flush();
                String line; int exit = -1;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(marker)) { exit = Integer.parseInt(line.substring(marker.length())); break; }
                    int split = line.indexOf('=');
                    if (split > 0) result.put(line.substring(0, split), line.substring(split + 1));
                }
                if (exit != 0 || !result.has("active")) {
                    String fallback = timedOut.get() ? "Root request timed out. Check Nomad’s root permission and retry." : "Root access unavailable. Allow Nomad in your root manager and retry.";
                    result.put("error", result.optString("error", fallback));
                    result.put("available", false);
                } else result.put("available", true);
            } catch (Exception error) {
                if (root != null) root.destroyForcibly();
                try { result.put("available", false); result.put("error", "Root connection interrupted. Retry to check or restore the controls."); } catch (Exception ignored) { }
            } finally {
                if (deadline != null) deadline.cancel(false);
                busy.set(false);
                try { result.put("request", mode); } catch (Exception ignored) { }
                String js = "window.receivePerformance && window.receivePerformance(" + result.toString() + ");";
                activity.runOnUiThread(() -> { if (!activity.isDestroyed()) web.evaluateJavascript(js, null); });
            }
        });
    }
}
