package com.rawal.pocketdeck;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** One-shot root commands for device-level tweaks (screen timeout, PPSSPP config, listener access). */
final class Root {
    private Root() { }

    static boolean enabled(android.content.Context context) {
        return context.getSharedPreferences("deck", 0).getBoolean("rootFeatures", false);
    }

    /** Runs a shell snippet as root; returns its stdout, or null if root was denied or it timed out. */
    static String run(String script, int timeoutSeconds) { return exec(timeoutSeconds, "su", "-c", script); }

    /**
     * Runs as root in the global mount namespace. Android isolates app data, so another app's
     * /data/data folder is invisible from Nomad's own namespace; Magisk's "-t 1" borrows init's.
     * Root solutions without that option run the script as usual.
     */
    static String runGlobal(String script, int timeoutSeconds) {
        String out = exec(timeoutSeconds, "su", "-t", "1", "-c", script);
        return out != null ? out : run(script, timeoutSeconds);
    }

    private static String exec(int timeoutSeconds, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) out.append(line).append('\n');
                } catch (Exception ignored) { }
            });
            pump.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            pump.join(2000);
            return process.exitValue() == 0 ? out.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean run(String script) { return run(script, 20) != null; }
}
