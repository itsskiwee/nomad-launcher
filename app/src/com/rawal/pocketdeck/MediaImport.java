package com.rawal.pocketdeck;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Reads another frontend's scraped media so switching to Nomad keeps it. Understands ES-DE
 * (downloaded_media/<system>/<covers|3dboxes|miximages|videos>/<rom name>.* and
 * gamelists/<system>/gamelist.xml) and the common "media/box2dfront", "images", "videos" layouts
 * that Skraper and EmulationStation use. Files are matched to games by ROM file name, and by
 * system when a folder along the way names one.
 */
final class MediaImport {
    /** Cover folder names, best first. */
    private static final String[] COVER_KINDS = { "covers", "box2dfront", "boxart", "box", "boxfront", "images", "3dboxes", "box3d", "miximages", "mixrbv2" };
    private static final String[] VIDEO_KINDS = { "videos", "video", "snap", "snaps" };

    static final class Found {
        final Map<String, Uri> covers = new HashMap<>(), videos = new HashMap<>();
        final Map<String, Integer> coverRank = new HashMap<>();
        final Map<String, Meta> meta = new HashMap<>();
    }

    static final class Meta { String name; boolean favorite, hidden; }

    interface Games { boolean wanted(String system, String stem); }

    /** Key for a game in the maps: "<system or *>|<rom file name without extension, lower case>". */
    static String key(String system, String stem) { return (system == null ? "*" : system) + "|" + stem.toLowerCase(Locale.ROOT); }

    static String stem(String name) {
        String s = name.replaceFirst("\\.[^.]+$", "");
        // Skraper/EmulationStation classic name images "<rom>-image.png" and videos "<rom>-video.mp4".
        return s.replaceFirst("-(image|video|thumb|marquee)$", "");
    }

    static int coverRank(String folder) {
        String f = folder.toLowerCase(Locale.ROOT);
        for (int i = 0; i < COVER_KINDS.length; i++) if (COVER_KINDS[i].equals(f)) return i;
        return -1;
    }

    static boolean videoFolder(String folder) {
        String f = folder.toLowerCase(Locale.ROOT);
        for (String v : VIDEO_KINDS) if (v.equals(f)) return true;
        return false;
    }

    /** Walks the picked folder (at most 7 levels, 60 000 entries). */
    static Found scan(ContentResolver resolver, Uri tree, Games games) throws Exception {
        Found found = new Found();
        walk(resolver, tree, DocumentsContract.getTreeDocumentId(tree), 0, null, null, found, games, new int[] { 0 });
        return found;
    }

    private static void walk(ContentResolver resolver, Uri tree, String doc, int depth, String system, String kind,
                             Found found, Games games, int[] visited) throws Exception {
        if (depth > 7 || visited[0] > 60000) return;
        List<String[]> dirs = new ArrayList<>();
        try (Cursor c = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc),
                new String[] { "document_id", "_display_name", "mime_type" }, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                visited[0]++;
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) { dirs.add(new String[] { id, name }); continue; }
                String lower = name.toLowerCase(Locale.ROOT);
                Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                if (lower.equals("gamelist.xml")) { readGamelist(resolver, file, system, found); continue; }
                if (kind == null) continue;
                String stem = stem(name);
                if (!games.wanted(system, stem)) continue;
                String k = key(system, stem);
                if (videoFolder(kind) && lower.endsWith(".mp4")) {
                    found.videos.put(k, file);
                } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                    int rank = coverRank(kind);
                    if (rank < 0) continue;
                    Integer had = found.coverRank.get(k);
                    if (had == null || rank < had) { found.covers.put(k, file); found.coverRank.put(k, rank); }
                }
            }
        }
        for (String[] d : dirs) {
            Systems.System sys = Systems.forFolder(d[1]);
            String sub = sys != null ? sys.id : system;
            String nextKind = coverRank(d[1]) >= 0 || videoFolder(d[1]) ? d[1] : sys != null ? null : kind;
            walk(resolver, tree, d[0], depth + 1, sub, nextKind, found, games, visited);
        }
    }

    /** ES-DE/EmulationStation gamelist.xml: the display name, favorite and hidden flags per ROM path. */
    static void readGamelist(ContentResolver resolver, Uri file, String system, Found found) {
        try (InputStream in = resolver.openInputStream(file)) {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(in, null);
            String path = null, tag = null;
            Meta meta = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("game".equals(tag)) { meta = new Meta(); path = null; }
                } else if (e == XmlPullParser.TEXT && meta != null && tag != null) {
                    String text = p.getText().trim();
                    if (text.isEmpty()) continue;
                    switch (tag) {
                        case "path": path = text; break;
                        case "name": meta.name = text; break;
                        case "favorite": meta.favorite = "true".equalsIgnoreCase(text); break;
                        case "hidden": meta.hidden = "true".equalsIgnoreCase(text); break;
                        default: break;
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    if ("game".equals(p.getName()) && meta != null && path != null) {
                        String base = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
                        found.meta.put(key(system, stem(base)), meta);
                        meta = null;
                    }
                    tag = null;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PocketDeck", "gamelist " + file, e);
        }
    }

    /** The entry for a game: its own system's first, else one found outside any system folder. */
    static <T> T lookup(Map<String, T> map, String system, String stem) {
        T v = map.get(key(system, stem));
        return v != null ? v : map.get(key(null, stem));
    }
}
