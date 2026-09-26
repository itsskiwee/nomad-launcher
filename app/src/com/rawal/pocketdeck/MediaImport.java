package com.rawal.pocketdeck;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
 * system when a folder along the way names one. Media a gamelist names in <thumbnail>, <image>
 * or <video> fills in for games the folder layout found nothing for.
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

    static final class Meta {
        String name, thumbnail, image, video, system, stem;
        boolean favorite, hidden;
        List<String> folder;   // document ids from the picked folder down to the gamelist's
    }

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
        String root = DocumentsContract.getTreeDocumentId(tree);
        walk(resolver, tree, root, new ArrayList<>(Collections.singletonList(root)), 0, null, null, found, games, new int[] { 0 });
        Map<String, Map<String, String>> listings = new HashMap<>();
        for (Meta m : found.meta.values()) {
            if (m.folder == null || !games.wanted(m.system, m.stem)) continue;
            String k = key(m.system, m.stem);
            if (lookup(found.covers, m.system, m.stem) == null) {
                for (String ref : new String[] { m.thumbnail, m.image }) {
                    if (ref == null || !ref.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)")) continue;
                    Uri file = resolve(resolver, tree, root, m.folder, ref, listings);
                    if (file != null) { found.covers.put(k, file); found.coverRank.put(k, COVER_KINDS.length); break; }
                }
            }
            if (lookup(found.videos, m.system, m.stem) == null && m.video != null && m.video.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                Uri file = resolve(resolver, tree, root, m.folder, m.video, listings);
                if (file != null) found.videos.put(k, file);
            }
        }
        return found;
    }

    private static void walk(ContentResolver resolver, Uri tree, String doc, List<String> folder, int depth, String system, String kind,
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
                if (lower.equals("gamelist.xml")) { readGamelist(resolver, file, system, folder, found); continue; }
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
            List<String> child = new ArrayList<>(folder);
            child.add(d[0]);
            walk(resolver, tree, d[0], child, depth + 1, sub, nextKind, found, games, visited);
        }
    }

    /** ES-DE/EmulationStation gamelist.xml: the display name, media paths, favorite and hidden flags per ROM path. */
    static void readGamelist(ContentResolver resolver, Uri file, String system, List<String> folder, Found found) {
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
                        case "thumbnail": meta.thumbnail = text; break;
                        case "image": meta.image = text; break;
                        case "video": meta.video = text; break;
                        case "favorite": meta.favorite = "true".equalsIgnoreCase(text); break;
                        case "hidden": meta.hidden = "true".equalsIgnoreCase(text); break;
                        default: break;
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    if ("game".equals(p.getName()) && meta != null && path != null) {
                        String base = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
                        meta.system = system;
                        meta.stem = stem(base);
                        meta.folder = folder;
                        found.meta.put(key(system, meta.stem), meta);
                        meta = null;
                    }
                    tag = null;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PocketDeck", "gamelist " + file, e);
        }
    }

    /**
     * The file a gamelist media path names: "./media/images/x.png" from the gamelist's folder, or an
     * absolute path that lies inside the picked folder. Null when it is elsewhere or missing.
     */
    static Uri resolve(ContentResolver resolver, Uri tree, String root, List<String> base, String ref,
                       Map<String, Map<String, String>> listings) {
        String inTree = treeRelative(root, ref);
        List<String> folders = new ArrayList<>();
        if (inTree != null) folders.add(root);
        else if (ref.startsWith("/") || ref.startsWith("~") || ref.contains(":")) return null;
        else folders.addAll(base);
        String id = null;
        for (String part : (inTree != null ? inTree : ref).replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (folders.size() <= 1) return null;
                folders.remove(folders.size() - 1);
                id = null;
                continue;
            }
            id = children(resolver, tree, folders.get(folders.size() - 1), listings).get(part.toLowerCase(Locale.ROOT));
            if (id == null) return null;
            folders.add(id);
        }
        return id == null ? null : DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    /** A folder's entries by lower-case name, listed once per import. */
    private static Map<String, String> children(ContentResolver resolver, Uri tree, String doc, Map<String, Map<String, String>> listings) {
        Map<String, String> names = listings.get(doc);
        if (names != null) return names;
        names = new HashMap<>();
        try (Cursor c = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc),
                new String[] { "document_id", "_display_name" }, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name != null && !names.containsKey(name.toLowerCase(Locale.ROOT))) names.put(name.toLowerCase(Locale.ROOT), c.getString(0));
            }
        } catch (Exception e) {
            android.util.Log.w("PocketDeck", "media folder " + doc, e);
        }
        listings.put(doc, names);
        return names;
    }

    /**
     * An absolute storage path as a path inside the picked folder, for the external-storage
     * provider's "primary:ROMs"-style ids: "/storage/emulated/0/ROMs/psx/a.png" -> "psx/a.png".
     */
    static String treeRelative(String root, String path) {
        int colon = root.indexOf(':');
        if (colon < 0 || !path.startsWith("/")) return null;
        String volume = root.substring(0, colon), rootPath = root.substring(colon + 1);
        String p = path.replace('\\', '/'), rest = null;
        String[] prefixes = volume.equals("primary")
            ? new String[] { "/storage/emulated/0/", "/sdcard/", "/storage/self/primary/", "/mnt/sdcard/" }
            : new String[] { "/storage/" + volume + "/", "/mnt/media_rw/" + volume + "/" };
        for (String prefix : prefixes) if (p.startsWith(prefix)) { rest = p.substring(prefix.length()); break; }
        if (rest == null) return null;
        if (rootPath.isEmpty()) return rest;
        int n = rootPath.length();
        return rest.length() > n && rest.charAt(n) == '/' && rest.regionMatches(true, 0, rootPath, 0, n) ? rest.substring(n + 1) : null;
    }

    /** The entry for a game: its own system's first, else one found outside any system folder. */
    static <T> T lookup(Map<String, T> map, String system, String stem) {
        T v = map.get(key(system, stem));
        return v != null ? v : map.get(key(null, stem));
    }
}
