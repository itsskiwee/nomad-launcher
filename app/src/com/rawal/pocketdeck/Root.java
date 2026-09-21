package com.rawal.pocketdeck;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** One-shot root commands for device-level tweaks (screen timeout, PPSSPP config, listener access). */
final class Root {
    private Root() { }

    /** Runs a shell snippet as root; returns its stdout, or null if root was denied or it timed out. */
    static String run(String script, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
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
