package com.rawal.pocketdeck;

import java.util.Arrays;
import java.util.HashSet;

public final class LibraryTest {
    private static void eq(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        // ASCII titles keep the 0.4.0 key, so saved version choices still apply.
        eq(Library.groupKey("n64", "Mario Kart 64"), "n64|mariokart64");
        eq(Library.groupKey("psp", "Crisis Core - Final Fantasy VII"), "psp|crisiscorefinalfantasyvii");
        // Different non-Latin titles no longer collapse into one group.
        eq(Library.groupKey("ps1", "ドラゴンクエストVII"), "ps1|ドラゴンクエストvii");
        if (Library.groupKey("ps1", "ドラゴンクエスト").equals(Library.groupKey("ps1", "ファイナルファンタジー"))) throw new AssertionError("Japanese titles grouped");
        if (Library.groupKey("ps1", "Тетрис").equals(Library.groupKey("ps1", "Танки"))) throw new AssertionError("Cyrillic titles grouped");
        // Kana voicing marks are part of the title; Latin accents and full-width forms are not.
        if (Library.groupKey("ps1", "がっこう").equals(Library.groupKey("ps1", "かっこう"))) throw new AssertionError("dakuten dropped");
        eq(Library.groupKey("gba", "Pokémon Emerald"), Library.groupKey("gba", "Pokemon Emerald"));
        eq(Library.groupKey("snes", "ＳＵＰＥＲ ＭＡＲＩＯ"), "snes|supermario");
        eq(Library.groupKey("snes", "???"), "");

        eq(Library.playlistEntry("# comment"), null);
        eq(Library.playlistEntry("   "), null);
        eq(Library.playlistEntry("\uFEFFGame (Disc 1).chd"), "game (disc 1).chd");
        eq(Library.playlistEntry("./Discs/Game (Disc 2).chd"), "discs/game (disc 2).chd");
        eq(Library.playlistEntry(".hidden\\Game (Disc 1).cue"), ".hidden/game (disc 1).cue");
        eq(Library.playlistEntry("../other/Game.chd"), "game.chd");
        eq(Library.playlistEntry("/storage/emulated/0/ROMs/Game.chd"), "game.chd");
        eq(Library.playlistEntry("C:\\ROMs\\Game.chd"), "game.chd");
        eq(Library.inside(new HashSet<>(Arrays.asList("discs/a.chd", "discs/sub/b.chd", "other/c.chd")), "Discs"),
            new HashSet<>(Arrays.asList("a.chd", "sub/b.chd")));

        eq(MediaImport.treeRelative("primary:ROMs", "/storage/emulated/0/ROMs/psx/media/a.png"), "psx/media/a.png");
        eq(MediaImport.treeRelative("primary:ROMs", "/sdcard/roms/psx/a.png"), "psx/a.png");
        eq(MediaImport.treeRelative("primary:ROMs", "/storage/emulated/0/ROMsExtra/a.png"), null);
        eq(MediaImport.treeRelative("primary:", "/storage/emulated/0/ES-DE/a.png"), "ES-DE/a.png");
        eq(MediaImport.treeRelative("1234-ABCD:Games", "/storage/1234-ABCD/Games/snes/a.png"), "snes/a.png");
        eq(MediaImport.treeRelative("1234-ABCD:Games", "/storage/emulated/0/Games/a.png"), null);
        eq(MediaImport.treeRelative("primary:ROMs", "./media/a.png"), null);
        System.out.println("LibraryTest passed");
    }
}
