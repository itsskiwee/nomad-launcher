package com.rawal.pocketdeck;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public final class RetroAchievementsTest {
    static void eq(Object a, Object e, String what) { if (!e.equals(a)) throw new AssertionError(what + ": expected " + e + " but got " + a); }
    static String md5(byte[] b, int from) throws Exception { MessageDigest m = MessageDigest.getInstance("MD5"); m.update(b, from, b.length - from); return RetroAchievements.hex(m.digest()); }

    public static void main(String[] args) throws Exception {
        // N64: .v64 (byte-swapped) and .n64 (little-endian) hash like the .z64 they came from.
        byte[] z64 = { (byte) 0x80, 0x37, 0x12, 0x40, 1, 2, 3, 4, 5, 6, 7, 8 };
        byte[] v64 = { 0x37, (byte) 0x80, 0x40, 0x12, 2, 1, 4, 3, 6, 5, 8, 7 };
        byte[] n64 = { 0x40, 0x12, 0x37, (byte) 0x80, 4, 3, 2, 1, 8, 7, 6, 5 };
        String expected = md5(z64.clone(), 0);
        eq(RetroAchievements.hashRom(2, z64.clone()), expected, "z64");
        eq(RetroAchievements.hashRom(2, v64), expected, "v64");
        eq(RetroAchievements.hashRom(2, n64), expected, "n64");
        // NES: the 16-byte iNES header is not part of the hash.
        byte[] nes = new byte[16 + 32];
        nes[0] = 'N'; nes[1] = 'E'; nes[2] = 'S'; nes[3] = 0x1A;
        for (int i = 16; i < nes.length; i++) nes[i] = (byte) i;
        eq(RetroAchievements.hashRom(7, nes.clone()), md5(nes, 16), "iNES header skipped");
        // SNES: a 512-byte copier header (size % 8 KB == 512) is skipped; a clean ROM is hashed whole.
        byte[] snes = new byte[512 + 0x2000];
        Arrays.fill(snes, 512, snes.length, (byte) 7);
        eq(RetroAchievements.hashRom(3, snes.clone()), md5(snes, 512), "SNES copier header");
        byte[] clean = Arrays.copyOfRange(snes, 512, snes.length);
        eq(RetroAchievements.hashRom(3, clean.clone()), md5(clean, 0), "SNES clean");
        // Arcade: the set name without its extension.
        eq(RetroAchievements.hashName("sf2.zip"), md5("sf2".getBytes(StandardCharsets.UTF_8), 0), "arcade name");
        eq(RetroAchievements.console("gb", "Tetris DX.gbc"), 6, "GBC console");
        eq(RetroAchievements.console("psp", "Game.cso"), 0, "CSO unsupported");
        System.out.println("RetroAchievementsTest passed");
    }
}
