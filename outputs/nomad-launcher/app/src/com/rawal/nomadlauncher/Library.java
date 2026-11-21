package com.rawal.nomadlauncher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Library extends SQLiteOpenHelper {
    static final int PAGE = 24;
    private static final int MAX_FOLDERS = 8;
    private static final int MAX_ENTRIES = 12000;
    private static final int MAX_DEPTH = 16;
    private static final String[] PROJECTION = {Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static Library instance;

    enum Shelf { ALL, RECENT, FAVORITES }

    static final class Game {
        final Uri uri;
        final String root, title, filename, mime;
        final GameSystem system;
        final Uri art;
        final boolean favorite;

        Game(Cursor row) {
            uri = Uri.parse(row.getString(0));
            root = row.getString(1);
            title = row.getString(2);
            filename = row.getString(3);
            mime = row.getString(4);
            system = GameSystem.valueOf(row.getString(5));
            art = row.isNull(6) ? null : Uri.parse(row.getString(6));
            favorite = row.getInt(7) == 1;
        }
    }

    static final class Folder {
        final String uri, name;
        Folder(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    static final class Snapshot {
        List<Game> recent = Collections.emptyList();
        List<Game> favorites = Collections.emptyList();
        List<Game> games = Collections.emptyList();
        List<GameSystem> systems = Collections.emptyList();
        int count;
        int folders;
    }

    private static final class Directory {
        final String id, artDirectory;
        final GameSystem system;
        final int depth;

        Directory(String id, GameSystem system, int depth, String artDirectory) {
            this.id = id;
            this.system = system;
            this.depth = depth;
            this.artDirectory = artDirectory;
        }
    }

    private final ContentResolver resolver;

    static synchronized Library get(Context context) {
        if (instance == null) instance = new Library(context.getApplicationContext());
        return instance;
    }

    private Library(Context context) {
        super(context, "nomad-library.db", null, 1);
        resolver = context.getContentResolver();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folders (uri TEXT PRIMARY KEY, name TEXT NOT NULL)");
        db.execSQL("CREATE TABLE games (uri TEXT NOT NULL, root TEXT NOT NULL, title TEXT NOT NULL, "
                + "filename TEXT NOT NULL, mime TEXT, system TEXT NOT NULL, art TEXT, PRIMARY KEY (root, uri))");
        db.execSQL("CREATE INDEX games_by_title ON games(title COLLATE NOCASE)");
        db.execSQL("CREATE TABLE marks (uri TEXT NOT NULL, root TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, "
                + "played INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (root, uri))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int before, int after) {
        throw new IllegalStateException("Missing library migration");
    }

    List<Folder> folders() {
        ArrayList<Folder> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT uri,name FROM folders ORDER BY name", null)) {
            while (row.moveToNext()) result.add(new Folder(row.getString(0), row.getString(1)));
        }
        return result;
    }

    Folder connect(Uri tree, int flags) throws IOException {
        List<Folder> existing = folders();
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Folder remembered = null;
        for (Folder folder : existing) {
            if (folder.uri.equals(tree.toString())) remembered = folder;
        }
        if (remembered == null && existing.size() >= MAX_FOLDERS) {
            throw new IOException("Up to " + MAX_FOLDERS + " folders can be connected.");
        }
        if ((flags & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            throw new IOException("The folder picker did not grant read access.");
        }
        String name = treeId;
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        try (Cursor row = resolver.query(root, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst() && row.getString(0) != null) name = row.getString(0);
        }
        resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ContentValues values = new ContentValues();
        values.put("uri", tree.toString());
        values.put("name", name);
        try {
            getWritableDatabase().replaceOrThrow("folders", null, values);
        } catch (SQLException error) {
            if (remembered == null) release(tree);
            throw error;
        }
        return new Folder(tree.toString(), name);
    }

    void disconnect(Folder folder) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] args = {folder.uri};
            db.delete("games", "root=?", args);
            db.delete("marks", "root=?", args);
            db.delete("folders", "uri=?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        release(Uri.parse(folder.uri));
    }

    private void release(Uri tree) {
        try {
            resolver.releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Android may already have revoked the grant.
        }
    }

    int scan(Folder folder) throws IOException {
        Uri tree = Uri.parse(folder.uri);
        boolean permitted = false;
        for (UriPermission grant : resolver.getPersistedUriPermissions()) {
            if (grant.isReadPermission() && tree.equals(grant.getUri())) permitted = true;
        }
        if (!permitted) throw new IOException("Reconnect " + folder.name + " to restore read access.");

        // Bound traversal before touching the existing index. A failed rescan keeps the last good snapshot.
        ArrayDeque<Directory> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<ContentValues> games = new ArrayList<>();
        Map<String, String> artwork = new HashMap<>();
        List<String> artworkKeys = new ArrayList<>();
        int entries = 0;
        pending.add(new Directory(DocumentsContract.getTreeDocumentId(tree), GameSystem.folder(folder.name), 0, null));
        while (!pending.isEmpty()) {
            Directory dir = pending.removeFirst();
            if (!visited.add(dir.id)) continue;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id);
            try (Cursor row = resolver.query(children, PROJECTION, null, null, null)) {
                if (row == null) throw new IOException("Could not read " + folder.name + ".");
                if (row.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    throw new IOException("The provider is still loading " + folder.name + ". Try scanning again.");
                }
                while (row.moveToNext()) {
                    if (++entries > MAX_ENTRIES) {
                        throw new IOException(folder.name + " exceeds " + MAX_ENTRIES + " entries. Connect a smaller folder.");
                    }
                    String id = row.getString(0);
                    String name = row.getString(1);
                    if (id == null || name == null || name.isEmpty()) continue;
                    String mime = row.getString(2);
                    if (Document.MIME_TYPE_DIR.equals(mime)) {
                        if (dir.depth == MAX_DEPTH) throw new IOException(folder.name + " exceeds " + MAX_DEPTH + " nested folders.");
                        GameSystem hint = GameSystem.folder(name);
                        boolean coverFolder = name.equalsIgnoreCase("artwork") || name.equalsIgnoreCase("covers");
                        pending.addLast(new Directory(id, hint == null ? dir.system : hint,
                                dir.depth + 1, coverFolder ? dir.id : null));
                        continue;
                    }
                    String title = GameSystem.title(name);
                    String key = artKey(dir.id, title);
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    if (GameSystem.isArtwork(name) && (row.isNull(3) || row.getLong(3) <= Artwork.MAX_BYTES)) {
                        String parent = dir.artDirectory;
                        if (parent != null) artwork.putIfAbsent(artKey(parent, title), uri.toString());
                        else artwork.put(key, uri.toString());
                        continue;
                    }
                    GameSystem system = GameSystem.detect(name, dir.system);
                    if (system == null || dir.artDirectory != null) continue;
                    ContentValues values = new ContentValues(7);
                    values.put("uri", uri.toString());
                    values.put("root", folder.uri);
                    values.put("title", title);
                    values.put("filename", name);
                    values.put("mime", mime);
                    values.put("system", system.name());
                    games.add(values);
                    artworkKeys.add(key);
                }
            }
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("games", "root=?", new String[]{folder.uri});
            for (int i = 0; i < games.size(); i++) {
                ContentValues game = games.get(i);
                game.put("art", artwork.get(artworkKeys.get(i)));
                db.replaceOrThrow("games", null, game);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return games.size();
    }

    private static String artKey(String directory, String title) {
        return directory + '\0' + title.toLowerCase(Locale.ROOT);
    }

    Snapshot snapshot(boolean home, Shelf shelf, GameSystem system, String text, int page) {
        Snapshot result = new Snapshot();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor row = db.rawQuery("SELECT (SELECT count(*) FROM games), (SELECT count(*) FROM folders)", null)) {
            if (row.moveToFirst()) {
                result.count = row.getInt(0);
                result.folders = row.getInt(1);
            }
        }
        try (Cursor row = db.rawQuery("SELECT DISTINCT system FROM games", null)) {
            Set<GameSystem> systems = new HashSet<>();
            while (row.moveToNext()) systems.add(GameSystem.valueOf(row.getString(0)));
            result.systems = new ArrayList<>(systems);
            Collections.sort(result.systems);
        }
        if (home) {
            result.recent = query(Shelf.RECENT, null, "", 0, 12);
            result.favorites = query(Shelf.FAVORITES, null, "", 0, 12);
            result.games = query(Shelf.ALL, null, "", 0, 16);
        } else {
            result.games = query(shelf, system, text, page * PAGE, PAGE + 1);
        }
        return result;
    }

    private List<Game> query(Shelf shelf, GameSystem system, String text, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT g.uri,g.root,g.title,g.filename,g.mime,g.system,g.art,"
                + "coalesce(m.favorite,0) FROM games g LEFT JOIN marks m ON m.root=g.root AND m.uri=g.uri WHERE 1=1");
        ArrayList<String> args = new ArrayList<>();
        if (shelf == Shelf.FAVORITES) sql.append(" AND m.favorite=1");
        if (shelf == Shelf.RECENT) sql.append(" AND m.played>0");
        if (system != null) {
            sql.append(" AND g.system=?");
            args.add(system.name());
        }
        if (!text.isEmpty()) {
            sql.append(" AND g.title LIKE ? ESCAPE '!'");
            args.add("%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        sql.append(shelf == Shelf.RECENT ? " ORDER BY m.played DESC,g.uri,g.root"
                : " ORDER BY g.title COLLATE NOCASE,g.uri,g.root");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(String.valueOf(limit));
        args.add(String.valueOf(offset));
        ArrayList<Game> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (row.moveToNext()) result.add(new Game(row));
        }
        return result;
    }

    void favorite(Game game) {
        updateMark(game, "favorite", game.favorite ? 0 : 1);
    }

    void launched(Game game) {
        updateMark(game, "played", System.currentTimeMillis());
    }

    private void updateMark(Game game, String column, long value) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues mark = new ContentValues();
            mark.put("uri", game.uri.toString());
            mark.put("root", game.root);
            db.insertWithOnConflict("marks", null, mark, SQLiteDatabase.CONFLICT_IGNORE);
            mark.clear();
            mark.put(column, value);
            db.update("marks", mark, "root=? AND uri=?", new String[]{game.root, game.uri.toString()});
            db.delete("marks", "root=? AND uri=? AND favorite=0 AND played=0",
                    new String[]{game.root, game.uri.toString()});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
