package com.rawal.pocketdeck;

import android.content.SharedPreferences;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Two devices sharing one mirror, with in-memory folders standing in for SAF and root. */
public final class SaveSyncTest {
    static final class Mem implements SaveSync.Mirror {
        final Map<String, byte[]> files = new HashMap<>();
        final Map<String, Long> times = new HashMap<>();
        final Map<String, byte[]> named = new HashMap<>();
        long clock = 1000;
        void put(String rel, String text, long time) { files.put(rel, text.getBytes(StandardCharsets.UTF_8)); times.put(rel, time); }
        String get(String rel) { byte[] b = files.get(rel); return b == null ? null : new String(b, StandardCharsets.UTF_8); }
        @Override public Map<String, long[]> list() {
            Map<String, long[]> out = new HashMap<>();
            for (String k : files.keySet()) if (!k.startsWith(SaveSync.PREVIOUS + "/")) out.put(k, new long[] { times.get(k), files.get(k).length });
            return out;
        }
        @Override public InputStream open(String rel) throws IOException {
            if (!files.containsKey(rel)) throw new IOException("missing " + rel);
            return new ByteArrayInputStream(files.get(rel));
        }
        @Override public void write(String rel, byte[] data) { files.put(rel, data); times.put(rel, clock += 1000); }
        @Override public byte[] readFile(String name) { return named.get(name); }
        @Override public void writeFile(String name, byte[] data) { named.put(name, data); }
    }

    /** Just enough SharedPreferences for the hash cache. */
    static final class Prefs implements SharedPreferences, SharedPreferences.Editor {
        final Map<String, Object> map = new HashMap<>();
        public Map<String, ?> getAll() { return map; }
        public String getString(String k, String d) { return map.containsKey(k) ? (String) map.get(k) : d; }
        public Set<String> getStringSet(String k, Set<String> d) { return d; }
        public int getInt(String k, int d) { return d; }
        public long getLong(String k, long d) { return d; }
        public float getFloat(String k, float d) { return d; }
        public boolean getBoolean(String k, boolean d) { return d; }
        public boolean contains(String k) { return map.containsKey(k); }
        public Editor edit() { return this; }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public Editor putString(String k, String v) { map.put(k, v); return this; }
        public Editor putStringSet(String k, Set<String> v) { return this; }
        public Editor putInt(String k, int v) { return this; }
        public Editor putLong(String k, long v) { return this; }
        public Editor putFloat(String k, float v) { return this; }
        public Editor putBoolean(String k, boolean v) { return this; }
        public Editor remove(String k) { map.remove(k); return this; }
        public Editor clear() { map.clear(); return this; }
        public boolean commit() { return true; }
        public void apply() { }
    }

    static void eq(Object a, Object e, String what) { if (e == null ? a != null : !e.equals(a)) throw new AssertionError(what + ": expected " + e + " but got " + a); }

    public static void main(String[] args) {
        Mem mirror = new Mem(), phone = new Mem(), handheld = new Mem();
        Prefs phoneCache = new Prefs(), handheldCache = new Prefs();
        phone.put("GAME1/SAVE.BIN", "phone v1", 5000);

        SaveSync.Result r = SaveSync.sync(phone, mirror, "phone", phoneCache, "PPSSPP");
        eq(r.up, 1, "first upload"); eq(r.error, null, "no error");
        eq(mirror.get("GAME1/SAVE.BIN"), "phone v1", "mirror has phone save");

        // A fresh device receives the save; syncing again changes nothing on either side.
        r = SaveSync.sync(handheld, mirror, "handheld", handheldCache, "PPSSPP");
        eq(r.down, 1, "handheld receives"); eq(handheld.get("GAME1/SAVE.BIN"), "phone v1", "handheld content");
        r = SaveSync.sync(handheld, mirror, "handheld", handheldCache, "PPSSPP");
        eq(r.up + r.down, 0, "handheld settled");
        r = SaveSync.sync(phone, mirror, "phone", phoneCache, "PPSSPP");
        eq(r.up + r.down, 0, "phone settled");

        // The handheld plays on (its clock is later); the phone picks it up and keeps its old copy.
        handheld.put("GAME1/SAVE.BIN", "handheld v2", 90000);
        eq(SaveSync.sync(handheld, mirror, "handheld", handheldCache, "PPSSPP").up, 1, "handheld sends v2");
        r = SaveSync.sync(phone, mirror, "phone", phoneCache, "PPSSPP");
        eq(r.down, 1, "phone receives v2");
        eq(phone.get("GAME1/SAVE.BIN"), "handheld v2", "phone has v2");
        eq(mirror.get(SaveSync.PREVIOUS + "/GAME1/SAVE.BIN"), "phone v1", "old phone save kept");

        // Both changed: the newer one wins wherever it syncs from.
        phone.put("GAME1/SAVE.BIN", "phone v3 (older)", 95000);
        handheld.put("GAME1/SAVE.BIN", "handheld v4 (newer)", 99000);
        SaveSync.sync(handheld, mirror, "handheld", handheldCache, "PPSSPP");
        r = SaveSync.sync(phone, mirror, "phone", phoneCache, "PPSSPP");
        eq(r.down, 1, "older local loses");
        eq(phone.get("GAME1/SAVE.BIN"), "handheld v4 (newer)", "newest content everywhere");

        // A mirror file still being copied in (hash does not match the manifest yet) is left alone.
        mirror.files.put("GAME1/SAVE.BIN", "partial".getBytes(StandardCharsets.UTF_8));
        phone.put("GAME1/SAVE.BIN", "handheld v4 (newer)", 10);
        phoneCache.map.clear();
        r = SaveSync.sync(phone, mirror, "phone", phoneCache, "PPSSPP");
        eq(phone.get("GAME1/SAVE.BIN"), "handheld v4 (newer)", "partial copy not applied");
        System.out.println("SaveSyncTest passed");
    }
}
