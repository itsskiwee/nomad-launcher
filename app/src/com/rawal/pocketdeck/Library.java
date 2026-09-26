package com.rawal.pocketdeck;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Library scan rules that do not need Android: version grouping and playlist references. */
final class Library {
    private static final Pattern LATIN_MARKS = Pattern.compile("(?<=\\p{IsLatin})\\p{M}+");

    /**
     * Games with the same key are versions of one game. Letters and digits of any script count,
     * so "ドラゴンクエスト" and "ファイナルファンタジー" stay apart; accents on Latin letters are
     * dropped so "Pokémon" and "Pokemon" group. A title with neither gives "" (no group).
     */
    static String groupKey(String system, String title) {
        String t = Normalizer.normalize(title.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        t = LATIN_MARKS.matcher(t).replaceAll("");
        t = Normalizer.normalize(t, Normalizer.Form.NFC).replaceAll("[^\\p{L}\\p{N}\\p{M}]+", "");
        return t.isEmpty() ? "" : system + "|" + t;
    }

    /**
     * A playlist line as a lower-case path relative to the playlist's folder ("discs/game (disc 1).chd"),
     * or null for blank lines and comments. Paths that start elsewhere keep only their file name.
     */
    static String playlistEntry(String line) {
        line = line.replace("﻿", "").trim();
        if (line.isEmpty() || line.startsWith("#")) return null;
        String path = line.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith("/") || path.matches("^[a-z][a-z0-9+.-]*:.*")) return name;
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return name;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The paths among `paths` that lie inside `folder`, relative to it. */
    static Set<String> inside(Set<String> paths, String folder) {
        String prefix = folder.toLowerCase(Locale.ROOT) + "/";
        Set<String> out = new HashSet<>();
        for (String p : paths) if (p.startsWith(prefix)) out.add(p.substring(prefix.length()));
        return out;
    }
}
