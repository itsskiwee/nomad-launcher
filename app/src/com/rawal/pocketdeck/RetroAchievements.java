package com.rawal.pocketdeck;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * RetroAchievements progress for library games. A game is identified the way RetroAchievements'
 * own clients do (rcheevos rc_hash): an MD5 of the ROM with copier headers removed and N64 byte
 * order normalised, of the file name for arcade sets, or of PARAM.SFO + EBOOT.BIN for PSP discs.
 * Disc systems whose hash needs the boot executable of a raw CD image are not covered. The Web
 * API then maps hashes to game ids (per-console lists, cached weekly) and returns progress.
 */
final class RetroAchievements {
    private static final String API = "https://retroachievements.org/API/";
    private static final long LIST_TTL = 7L * 24 * 60 * 60 * 1000;
    static final long MAX_ROM = 64L << 20;

    /** RetroAchievements console id for a library game, or 0 when its hash is not supported here. */
    static int console(String system, String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        switch (system) {
            case "genesis": return 1;
            case "n64": return 2;
            case "snes": return 3;
            case "gb": return lower.endsWith(".gbc") ? 6 : 4;
            case "gba": return 5;
            case "nes": return 7;
            case "pce": return 8;
            case "32x": return 10;
            case "arcade": return 27;
            case "psp": return lower.endsWith(".iso") ? 41 : 0;
            default: return 0;
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Arcade sets are identified by name alone. */
    static String hashName(String filename) {
        return hex(md5().digest(filename.replaceFirst("\\.[^.]+$", "").getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Hash of a cartridge ROM held in memory: iNES/FDS (16 bytes) and SNES/PC Engine copier
     * (512 bytes) headers are skipped, and byte-swapped or little-endian N64 images are put
     * into big-endian (.z64) order first.
     */
    static String hashRom(int console, byte[] rom) {
        int skip = 0;
        if (console == 7 && rom.length >= 16 && ((rom[0] == 'N' && rom[1] == 'E' && rom[2] == 'S') || (rom[0] == 'F' && rom[1] == 'D' && rom[2] == 'S')) && rom[3] == 0x1A) skip = 16;
        if (console == 3 && rom.length % 0x2000 == 512) skip = 512;
        if (console == 8 && rom.length % 0x20000 == 512) skip = 512;
        if (console == 2 && rom.length >= 4) {
            int b0 = rom[0] & 0xFF;
            if (b0 == 0x37) { for (int i = 0; i + 1 < rom.length; i += 2) { byte t = rom[i]; rom[i] = rom[i + 1]; rom[i + 1] = t; } }
            else if (b0 == 0x40) {
                for (int i = 0; i + 3 < rom.length; i += 4) {
                    byte a = rom[i], b = rom[i + 1];
                    rom[i] = rom[i + 3]; rom[i + 1] = rom[i + 2]; rom[i + 2] = b; rom[i + 3] = a;
                }
            }
        }
        MessageDigest md = md5();
        md.update(rom, skip, rom.length - skip);
        return hex(md.digest());
    }

    /** A cartridge file, or the first file inside a .zip (what RetroArch loads from it). */
    static byte[] readRom(InputStream in, String filename) throws IOException {
        if (filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) if (!e.isDirectory()) return SaveSync.readAll(zip);
            throw new IOException("Empty zip");
        }
        return SaveSync.readAll(in);
    }

    /** PSP ISO: PARAM.SFO followed by PSP_GAME/SYSDIR/EBOOT.BIN, both read in place. */
    static String hashPspIso(FileChannel ch) throws IOException {
        byte[] volume = GameArt.read(ch, 32768, 2048);
        if (!new String(volume, 1, 5, StandardCharsets.US_ASCII).equals("CD001")) throw new IOException("Not an ISO");
        long[] game = GameArt.directory(ch, GameArt.u32(volume, 158), (int) GameArt.u32(volume, 166)).get("PSP_GAME");
        if (game == null) throw new IOException("No PSP_GAME");
        Map<String, long[]> root = GameArt.directory(ch, game[0], (int) game[1]);
        long[] sfo = root.get("PARAM.SFO"), sys = root.get("SYSDIR");
        if (sfo == null || sys == null) throw new IOException("No PARAM.SFO or SYSDIR");
        long[] eboot = GameArt.directory(ch, sys[0], (int) sys[1]).get("EBOOT.BIN");
        if (eboot == null) throw new IOException("No EBOOT.BIN");
        MessageDigest md = md5();
        for (long[] f : new long[][] { sfo, eboot }) {
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            long pos = f[0] * 2048, end = pos + f[1];
            while (pos < end) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), end - pos));
                int n = ch.read(buf, pos);
                if (n <= 0) throw new IOException("Short read");
                md.update(buf.array(), 0, n);
                pos += n;
            }
        }
        return hex(md.digest());
    }

    // ---- Web API ----

    private final File cache;
    private final String user, key;
    private final Map<Integer, Map<String, Integer>> lists = new HashMap<>();

    RetroAchievements(File cache, String user, String key) { this.cache = cache; this.user = user; this.key = key; cache.mkdirs(); }

    private String url(String endpoint, String query) throws IOException {
        return API + endpoint + "?y=" + URLEncoder.encode(key, "UTF-8") + "&" + query;
    }

    /** Hash -> game id for one console (games with achievements only). */
    synchronized Map<String, Integer> games(int console) throws Exception {
        if (lists.containsKey(console)) return lists.get(console);
        File file = new File(cache, "console-" + console + ".json");
        String json;
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < LIST_TTL) {
            json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } else {
            json = new String(CoverArt.get(url("API_GetGameList.php", "i=" + console + "&h=1&f=1"), 32 << 20), StandardCharsets.UTF_8);
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Integer> out = new HashMap<>();
        JSONArray list = new JSONArray(json);
        for (int i = 0; i < list.length(); i++) {
            JSONObject g = list.getJSONObject(i);
            JSONArray hashes = g.optJSONArray("Hashes");
            if (hashes != null) for (int j = 0; j < hashes.length(); j++) out.put(hashes.getString(j).toLowerCase(Locale.ROOT), g.getInt("ID"));
        }
        lists.put(console, out);
        return out;
    }

    /** Game id -> {achieved, possible, achieved in hardcore} for this account. */
    Map<Integer, int[]> progress(List<Integer> ids) throws Exception {
        Map<Integer, int[]> out = new HashMap<>();
        for (int from = 0; from < ids.size(); from += 100) {
            StringBuilder csv = new StringBuilder();
            for (int id : ids.subList(from, Math.min(ids.size(), from + 100))) csv.append(csv.length() > 0 ? "," : "").append(id);
            JSONObject all = new JSONObject(new String(CoverArt.get(url("API_GetUserProgress.php", "u=" + URLEncoder.encode(user, "UTF-8") + "&i=" + csv), 4 << 20), StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject p = all.getJSONObject(id);
                out.put(Integer.parseInt(id), new int[] { p.optInt("NumAchieved"), p.optInt("NumPossibleAchievements"), p.optInt("NumAchievedHardcore") });
            }
        }
        return out;
    }
}
