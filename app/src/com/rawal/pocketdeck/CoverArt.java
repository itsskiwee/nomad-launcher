package com.rawal.pocketdeck;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Box art from the libretro-thumbnails collection (thumbnails.libretro.com): free, no account,
 * named after No-Intro/Redump titles. Each system's file list is fetched once a week and cached;
 * a game is matched by its title with punctuation, articles and region tags ignored, preferring
 * the region its own file name carries.
 */
final class CoverArt {
    private static final String HOST = "https://thumbnails.libretro.com/";
    private static final long INDEX_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final Map<String, String> DIRS = new HashMap<>();
    static {
        DIRS.put("psp", "Sony - PlayStation Portable");
        DIRS.put("ps1", "Sony - PlayStation");
        DIRS.put("ps2", "Sony - PlayStation 2");
        DIRS.put("vita", "Sony - PlayStation Vita");
        DIRS.put("n64", "Nintendo - Nintendo 64");
        DIRS.put("gc", "Nintendo - GameCube");
        DIRS.put("wii", "Nintendo - Wii");
        DIRS.put("3ds", "Nintendo - Nintendo 3DS");
        DIRS.put("nds", "Nintendo - Nintendo DS");
        DIRS.put("snes", "Nintendo - Super Nintendo Entertainment System");
        DIRS.put("nes", "Nintendo - Nintendo Entertainment System");
        DIRS.put("gba", "Nintendo - Game Boy Advance");
        DIRS.put("gb", "Nintendo - Game Boy");
        DIRS.put("genesis", "Sega - Mega Drive - Genesis");
        DIRS.put("32x", "Sega - 32X");
        DIRS.put("saturn", "Sega - Saturn");
        DIRS.put("dreamcast", "Sega - Dreamcast");
        DIRS.put("arcade", "FBNeo - Arcade Games");
        DIRS.put("pce", "NEC - PC Engine - TurboGrafx 16");
    }
    private static final Pattern HREF = Pattern.compile("href=\"([^\"?/]+\\.png)\"");
    private static final Pattern TAG = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*\\]");

    private final File cache;
    private final Map<String, List<String>> indexes = new HashMap<>();

    CoverArt(File cache) { this.cache = cache; cache.mkdirs(); }

    /** The collection folder for a game, or null for systems it does not cover. */
    static String dirFor(String system, String filename) {
        if ("gb".equals(system) && filename.toLowerCase(Locale.ROOT).endsWith(".gbc")) return "Nintendo - Game Boy Color";
        return DIRS.get(system);
    }

    /** "Legend of Zelda, The - Ocarina of Time (USA) [!]" and "The Legend of Zelda: Ocarina of Time" -> "legendofzeldaocarinaoftime". */
    static String normalize(String title) {
        String s = TAG.matcher(title.replaceFirst("\\.[a-zA-Z0-9]{1,4}$", "")).replaceAll(" ").toLowerCase(Locale.ROOT)
            .replace("&", " and ").replaceFirst("^\\d{3,5} - ", "");
        s = s.replaceAll("\\bthe\\b", " ").replaceAll("\\ba\\b", " ");
        return s.replaceAll("[^a-z0-9]+", "");
    }

    private static int rank(String name, String wantedTags) {
        String tags = tags(name), want = wantedTags.toLowerCase(Locale.ROOT);
        for (String region : new String[] { "usa", "europe", "japan", "world", "asia", "korea" }) {
            if (want.contains(region) && tags.contains(region)) return 0;
        }
        if (tags.contains("usa")) return 1;
        if (tags.contains("world")) return 2;
        if (tags.contains("europe")) return 3;
        if (tags.contains("japan")) return 5;
        return 4;
    }

    private static String tags(String name) {
        StringBuilder sb = new StringBuilder();
        Matcher m = TAG.matcher(name);
        while (m.find()) sb.append(m.group().toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    /** The best image name in `names` for a game, or null. Beta, demo and prototype art is skipped. */
    static String match(List<String> names, String filename, String title) {
        String stem = filename.replaceFirst("\\.[^.]+$", "");
        for (String n : names) if (n.equalsIgnoreCase(stem + ".png")) return n;   // exact No-Intro/Redump name
        String[] keys = { normalize(filename), normalize(title) };
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String n : names) {
            String t = tags(n);
            if (t.contains("beta") || t.contains("demo") || t.contains("proto") || t.contains("sample")) continue;
            String key = normalize(n);
            if (key.isEmpty() || !(key.equals(keys[0]) || key.equals(keys[1]))) continue;
            // Region first, then the plainest release: "(USA)" beats "(USA) (GameCube)".
            int r = rank(n, tags(filename)) * 100 + tags(n).split("\\(|\\[", -1).length;
            if (r < bestRank) { bestRank = r; best = n; }
        }
        return best;
    }

    /** The image names available for a collection folder, from the weekly cache or the server. */
    synchronized List<String> index(String dir) throws IOException {
        if (indexes.containsKey(dir)) return indexes.get(dir);
        File file = new File(cache, dir.replaceAll("[^A-Za-z0-9]+", "-") + ".txt");
        List<String> names;
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < INDEX_TTL) {
            names = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } else {
            String html = new String(get(HOST + encode(dir) + "/Named_Boxarts/", 8 << 20), StandardCharsets.UTF_8);
            names = new ArrayList<>();
            Matcher m = HREF.matcher(html);
            while (m.find()) names.add(URLDecoder.decode(m.group(1).replace("+", "%2B"), "UTF-8"));
            if (names.isEmpty()) throw new IOException("Empty thumbnail index for " + dir);
            Files.write(file.toPath(), names, StandardCharsets.UTF_8);
        }
        indexes.put(dir, names);
        return names;
    }

    /** Downloads one box art image and stores it as a JPEG no taller than 800 px. */
    static boolean download(String dir, String name, File target) throws IOException {
        byte[] png = get(HOST + encode(dir) + "/Named_Boxarts/" + encode(name), 12 << 20);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, bounds.outHeight / 800);
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, opts);
        if (bitmap == null) return false;
        File temp = new File(target.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            bitmap.recycle();
        }
        return temp.renameTo(target);
    }

    private static String encode(String part) throws IOException {
        return URLEncoder.encode(part, "UTF-8").replace("+", "%20");
    }

    static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Nomad-Launcher (+https://github.com/itsskiwee/nomad-launcher)");
        try {
            // Error messages end up in logs; keep API keys (RetroAchievements' y=) out of them.
            String shown = url.replaceAll("([?&]y=)[^&]*", "$1(key)");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + shown);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) throw new IOException("Response too large: " + shown);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
