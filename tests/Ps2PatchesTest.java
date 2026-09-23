package com.rawal.pocketdeck;

public final class Ps2PatchesTest {
    static void eq(Object a, Object e, String what) { if (!e.equals(a)) throw new AssertionError(what + ": expected " + e + " but got " + a); }

    public static void main(String[] args) {
        eq(Ps2Patches.serial("SLUS_208.96"), "SLUS-20896", "serial from boot file");
        // XOR of little-endian words; a trailing partial word is ignored, as in PCSX2.
        byte[] elf = { 0x01, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, (byte) 0x80, 0x7F };
        eq(Ps2Patches.crc(elf), 0x80000011, "crc");
        String script = Ps2Patches.applyScript(true);
        if (script.contains("sed -i")) throw new AssertionError("prefs must be rewritten in place to keep owner and label");
        if (!script.contains("value=\"true\"")) throw new AssertionError("script sets the requested value");
        System.out.println("Ps2PatchesTest passed");
    }
}
