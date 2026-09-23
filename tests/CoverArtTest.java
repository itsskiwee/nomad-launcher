package com.rawal.pocketdeck;

import java.util.Arrays;
import java.util.List;

public final class CoverArtTest {
    private static void eq(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        List<String> names = Arrays.asList(
            "Legend of Zelda, The - Ocarina of Time (Europe) (En,Fr,De).png",
            "Legend of Zelda, The - Ocarina of Time (USA).png",
            "Legend of Zelda, The - Ocarina of Time (USA) (Beta).png",
            "Legend of Zelda, The - Ocarina of Time (USA) (GameCube).png",
            "Crisis Core - Final Fantasy VII (Japan).png",
            "Crisis Core - Final Fantasy VII (USA).png",
            "Crisis Core - Final Fantasy VII (Europe) (En,Fr,De,Es,It).png",
            "Fast and the Furious, The - Tokyo Drift (Europe) (En,Fr,De,Es,It,Nl).png",
            "Moto GP (USA).png",
            "Mario Kart 64 (Japan).png",
            "Mario Kart 64 (USA).png");
        eq(CoverArt.normalize("Legend of Zelda, The - Ocarina of Time (U) (V1.2) [!].z64"), "legendofzeldaocarinaoftime");
        eq(CoverArt.normalize("The Legend of Zelda: Ocarina of Time"), "legendofzeldaocarinaoftime");
        // Loose file names prefer USA art; a regional file keeps its own region.
        eq(CoverArt.match(names, "Legend of Zelda, The - Ocarina of Time (U) (V1.2) [!].z64", ""), "Legend of Zelda, The - Ocarina of Time (USA).png");
        eq(CoverArt.match(names, "Crisis Core - Final Fantasy VII (Europe).iso", ""), "Crisis Core - Final Fantasy VII (Europe) (En,Fr,De,Es,It).png");
        // A PSP title read from PARAM.SFO matches even when the file name is unhelpful.
        eq(CoverArt.match(names, "ULUS10336.iso", "CRISIS CORE -FINAL FANTASY VII-"), "Crisis Core - Final Fantasy VII (USA).png");
        // Exact No-Intro/Redump names win outright; numbered dump prefixes are ignored.
        eq(CoverArt.match(names, "Mario Kart 64 (Japan).z64", ""), "Mario Kart 64 (Japan).png");
        eq(CoverArt.match(names, "0627 - Moto GP (USA) (v1.02).iso", "MotoGP"), "Moto GP (USA).png");
        eq(CoverArt.match(names, "1378 - Fast and The Furious, The - Tokyo Drift (Europe).iso", ""), "Fast and the Furious, The - Tokyo Drift (Europe) (En,Fr,De,Es,It,Nl).png");
        eq(CoverArt.match(names, "Unknown Homebrew.iso", "Unknown Homebrew"), null);
        eq(CoverArt.dirFor("gb", "Tetris DX.gbc"), "Nintendo - Game Boy Color");
        eq(CoverArt.dirFor("windows", "Game.desktop"), null);
        System.out.println("CoverArtTest passed");
    }
}
