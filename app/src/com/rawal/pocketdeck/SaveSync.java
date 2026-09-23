package com.rawal.pocketdeck;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Two-way, newest-wins sync between emulator save folders and a folder the user picks (a
 * Syncthing or cloud-drive folder moves it between devices). Each save folder mirrors into
 * "Nomad Saves/<label>/" next to a manifest of what was last synced: file hash, the source
 * file's time and the device it came from. A file whose content differs from the manifest is
 * copied in whichever direction is newer; files are never deleted, and a local save is kept in
 * ".previous/" before a newer copy replaces it.
 */
final class SaveSync {
    static final String ROOT = "Nomad Saves", MANIFEST = ".nomad-manifest.tsv", PREVIOUS = ".previous";
    static final long MAX_FILE = 64L << 20;

    /** A folder of saves: a document tree, or a path reached through root. */
    interface Store {
        /** Relative path -> {modified ms, size}. */
        Map<String, long[]> list() throws IOException;
        InputStream open(String rel) throws IOException;
        void write(String rel, byte[] data) throws IOException;
    }

    /** The synced side: a store that also holds the manifest. */
    interface Mirror extends Store {
        byte[] readFile(String name) throws IOException;
        void writeFile(String name, byte[] data) throws IOException;
    }

    static final class Result { int up, down, same; String error; }

    /** One manifest line: what a file held when it was last synced, and where it came from. */
    static final class Entry {
        final String hash, device; final long modified;
        Entry(String hash, long modified, String device) { this.hash = hash; this.modified = modified; this.device = device; }
    }

    /** "path<TAB>sha1<TAB>modified ms<TAB>device" per line; a small format any tool can read. */
    static Map<String, Entry> readManifest(byte[] raw) {
        Map<String, Entry> out = new java.util.TreeMap<>();
        if (raw == null) return out;
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            String[] p = line.split("\t", 4);
            if (p.length < 3 || line.startsWith("#")) continue;
            try { out.put(p[0], new Entry(p[1], Long.parseLong(p[2]), p.length > 3 ? p[3] : "")); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static byte[] writeManifest(Map<String, Entry> files) {
        StringBuilder sb = new StringBuilder("# Nomad save sync manifest: path, sha1, modified (ms), device\n");
        for (Map.Entry<String, Entry> e : files.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().hash).append('\t').append(e.getValue().modified).append('\t').append(e.getValue().device.replaceAll("\\s+", " ")).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- Storage Access Framework ----

    static final class SafStore implements Mirror {
        final ContentResolver resolver; final Uri tree; final String rootDoc;
        private final Map<String, String> docs = new HashMap<>();   // relative path -> document id (files and folders)

        SafStore(ContentResolver resolver, Uri tree, String rootDoc) { this.resolver = resolver; this.tree = tree; this.rootDoc = rootDoc; }

        static SafStore of(ContentResolver resolver, Uri tree) { return new SafStore(resolver, tree, DocumentsContract.getTreeDocumentId(tree)); }

        /** The store for a sub-folder, created when missing. */
        SafStore dir(String name) throws IOException {
            return new SafStore(resolver, tree, ensureDir(rootDoc, name));
        }

        private String find(String parent, String name) throws IOException {
            try (Cursor c = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                    new String[] { "document_id", "_display_name" }, null, null, null)) {
                if (c == null) throw new IOException("No access to the sync folder");
                while (c.moveToNext()) if (name.equals(c.getString(1))) return c.getString(0);
            }
            return null;
        }

        private String ensureDir(String parent, String name) throws IOException {
            String id = find(parent, name);
            if (id != null) return id;
            try {
                Uri made = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, name);
                if (made == null) throw new IOException("Could not create " + name);
                return DocumentsContract.getDocumentId(made);
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
        }

        @Override public Map<String, long[]> list() throws IOException {
            Map<String, long[]> out = new HashMap<>();
            docs.clear();
            walk(rootDoc, "", out, 0);
            return out;
        }

        private void walk(String doc, String prefix, Map<String, long[]> out, int depth) throws IOException {
            if (depth > 8) return;
            try (Cursor c = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc),
                    new String[] { "document_id", "_display_name", "mime_type", "last_modified", "_size" }, null, null, null)) {
                if (c == null) throw new IOException("No access to " + tree);
                while (c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), rel = prefix + name;
                    if (name == null || name.equals(MANIFEST) || name.equals(PREVIOUS) || name.endsWith(".nomad-part")) continue;
                    docs.put(rel, id);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) { walk(id, rel + "/", out, depth + 1); continue; }
                    long size = c.isNull(4) ? 0 : c.getLong(4);
                    if (size <= MAX_FILE) out.put(rel, new long[] { c.isNull(3) ? 0 : c.getLong(3), size });
                }
            }
        }

        @Override public InputStream open(String rel) throws IOException {
            String id = docs.get(rel);
            if (id == null) throw new IOException("Missing " + rel);
            InputStream in = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            if (in == null) throw new IOException("Cannot open " + rel);
            return in;
        }

        @Override public void write(String rel, byte[] data) throws IOException {
            String parent = rootDoc, path = "";
            String[] parts = rel.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                path += parts[i] + "/";
                String known = docs.get(path.substring(0, path.length() - 1));
                parent = known != null ? known : ensureDir(parent, parts[i]);
                docs.put(path.substring(0, path.length() - 1), parent);
            }
            String name = parts[parts.length - 1];
            String id = docs.containsKey(rel) ? docs.get(rel) : find(parent, name);
            Uri uri;
            try {
                uri = id != null ? DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    : DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), "application/octet-stream", name);
            } catch (Exception e) { throw new IOException(e); }
            if (uri == null) throw new IOException("Could not create " + rel);
            docs.put(rel, DocumentsContract.getDocumentId(uri));
            try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot write " + rel);
                out.write(data);
            }
        }

        @Override public byte[] readFile(String name) throws IOException {
            String id = find(rootDoc, name);
            if (id == null) return null;
            try (InputStream in = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))) { return readAll(in); }
        }

        @Override public void writeFile(String name, byte[] data) throws IOException {
            docs.remove(name);
            write(name, data);
        }
    }

    // ---- root ----

    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    static final class RootStore implements Store {
        final String dir;
        RootStore(String dir) { this.dir = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir; }

        @Override public Map<String, long[]> list() throws IOException {
            String out = Root.run("cd " + quote(dir) + " && find . -type f -size -" + (MAX_FILE >> 10) + "k -exec stat -c '%Y %s %n' {} +", 30);
            if (out == null) throw new IOException("Root could not read " + dir);
            Map<String, long[]> files = new HashMap<>();
            for (String line : out.split("\n")) {
                String[] p = line.split(" ", 3);
                if (p.length < 3 || !p[2].startsWith("./")) continue;
                try { files.put(p[2].substring(2), new long[] { Long.parseLong(p[0]) * 1000, Long.parseLong(p[1]) }); } catch (NumberFormatException ignored) { }
            }
            return files;
        }

        @Override public InputStream open(String rel) throws IOException {
            Process p = new ProcessBuilder("su", "-c", "cat " + quote(dir + "/" + rel)).start();
            return new FilterInputStream(p.getInputStream()) {
                @Override public void close() throws IOException { super.close(); p.destroy(); }
            };
        }

        /** Written through /storage so the emulator keeps owning its file (sdcardfs/FUSE derive the owner). */
        @Override public void write(String rel, byte[] data) throws IOException {
            String path = dir + "/" + rel, parent = path.substring(0, path.lastIndexOf('/'));
            Process p = new ProcessBuilder("su", "-c", "mkdir -p " + quote(parent) + " && cat > " + quote(path + ".nomad-part")
                + " && mv -f " + quote(path + ".nomad-part") + " " + quote(path)).start();
            try (OutputStream out = p.getOutputStream()) { out.write(data); }
            try {
                if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("Root could not write " + path);
            } catch (InterruptedException e) { throw new IOException(e); }
        }
    }

    // ---- sync ----

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FILE) throw new IOException("Save file larger than 64 MB");
        }
        return out.toByteArray();
    }

    static String sha1(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Syncs one save folder with its mirror; `cache` remembers local hashes by file time and size. */
    static Result sync(Store local, Mirror mirror, String device, SharedPreferences cache, String cacheKey) {
        Result r = new Result();
        try {
            Map<String, Entry> files = readManifest(mirror.readFile(MANIFEST));
            Map<String, long[]> here = local.list(), there = mirror.list();
            SharedPreferences.Editor edit = cache.edit();
            boolean changed = false;
            for (Map.Entry<String, long[]> e : here.entrySet()) {
                String rel = e.getKey();
                long modified = e.getValue()[0], size = e.getValue()[1];
                String stamp = modified + ":" + size, key = cacheKey + "|" + rel;
                String cached = cache.getString(key, "");
                byte[] data = null;
                String hash;
                if (cached.startsWith(stamp + ":")) hash = cached.substring(stamp.length() + 1);
                else {
                    try (InputStream in = local.open(rel)) { data = readAll(in); }
                    hash = sha1(data);
                    edit.putString(key, stamp + ":" + hash);
                }
                Entry entry = files.get(rel);
                boolean inMirror = there.containsKey(rel);
                if (entry != null && inMirror && hash.equals(entry.hash)) { r.same++; continue; }
                if (entry == null || !inMirror || modified > entry.modified) {
                    if (data == null) try (InputStream in = local.open(rel)) { data = readAll(in); }
                    mirror.write(rel, data);
                    files.put(rel, new Entry(hash, modified, device));
                    r.up++; changed = true;
                } else {
                    download(local, mirror, rel, edit, key, entry, r);
                }
            }
            // Saves only the mirror has (another device's games, or a fresh install here).
            for (Map.Entry<String, Entry> e : files.entrySet()) {
                String rel = e.getKey();
                if (here.containsKey(rel) || !there.containsKey(rel)) continue;
                download(local, mirror, rel, edit, cacheKey + "|" + rel, e.getValue(), r);
            }
            edit.apply();
            if (changed) mirror.writeFile(MANIFEST, writeManifest(files));
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            android.util.Log.w("PocketDeck", "Save sync " + cacheKey, e);
        }
        return r;
    }

    private static void download(Store local, Mirror mirror, String rel, SharedPreferences.Editor edit, String key, Entry entry, Result r) throws Exception {
        byte[] data;
        try (InputStream in = mirror.open(rel)) { data = readAll(in); }
        if (!sha1(data).equals(entry.hash)) return;   // the sync app is still copying it in
        try {
            byte[] old;
            try (InputStream in = local.open(rel)) { old = readAll(in); }
            mirror.write(PREVIOUS + "/" + rel, old);
        } catch (IOException missing) { /* nothing here to keep */ }
        local.write(rel, data);
        // The new local copy's time is unknown until the next listing; clear the cache so it is re-hashed then.
        edit.remove(key);
        r.down++;
    }
}
