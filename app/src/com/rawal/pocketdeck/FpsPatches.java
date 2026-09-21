package com.rawal.pocketdeck;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The bundled 60 FPS patch table (assets/fps-patches.txt), keyed by PSP DISC_ID. */
final class FpsPatches {
    static final class Patch {
        final String discId, name; final int clock; final List<String> lines = new ArrayList<>();
        Patch(String discId, String name, int clock) { this.discId = discId; this.name = name; this.clock = clock; }

        /** The cheat file PPSSPP loads at boot: one enabled cheat, tagged so Nomad only ever deletes its own. */
        String cheatFile() {
            StringBuilder sb = new StringBuilder("_S ").append(discId.substring(0, 4)).append('-').append(discId.substring(4)).append('\n');
            sb.append("_G ").append(name).append('\n').append("_C1 60 FPS (Nomad)\n");
            for (String line : lines) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static Map<String, Patch> table;

    static synchronized Patch find(Context context, String discId) {
        if (table == null) table = load(context);
        return discId == null ? null : table.get(discId.trim().toUpperCase());
    }

    private static Map<String, Patch> load(Context context) {
        Map<String, Patch> map = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(context.getAssets().open("fps-patches.txt"), "UTF-8"))) {
            Patch current = null;
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int end = line.indexOf(']');
                    String id = line.substring(1, end).trim().toUpperCase(), rest = line.substring(end + 1).trim();
                    int clock = 0, at = rest.indexOf("clock=");
                    if (at >= 0) { clock = Integer.parseInt(rest.substring(at + 6).trim()); rest = rest.substring(0, at).trim(); }
                    current = new Patch(id, rest, clock);
                    map.put(id, current);
                } else if (line.startsWith("_L ") && current != null) {
                    current.lines.add(line);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PocketDeck", "fps-patches", e);
        }
        return map;
    }
}
