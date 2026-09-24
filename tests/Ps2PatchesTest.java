package com.rawal.pocketdeck;

public final class Ps2PatchesTest {
    static void eq(Object a, Object e, String what) { if (!e.equals(a)) throw new AssertionError(what + ": expected " + e + " but got " + a); }

    public static void main(String[] args) throws Exception {
        eq(Ps2Patches.serial("SLUS_208.96"), "SLUS-20896", "serial from boot file");
        // XOR of little-endian words; a trailing partial word is ignored, as in PCSX2.
        byte[] elf = { 0x01, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, (byte) 0x80, 0x7F };
        eq(Ps2Patches.crc(elf), 0x80000011, "crc");
        String script = Ps2Patches.applyScript(true);
        if (script.contains("sed -i")) throw new AssertionError("prefs must be rewritten in place to keep owner and label");
        if (!script.contains("value=\"true\"")) throw new AssertionError("script sets the requested value");
        // The script sets an existing value, adds a missing one, and fails when there is no file.
        for (String[] c : new String[][] {
                { "<map>\n    <boolean name=\"EmuCore/EnableWideScreenPatches\" value=\"false\" />\n</map>\n", "0" },
                { "<map>\n    <boolean name=\"EmuCore/EnableCheats\" value=\"false\" />\n</map>\n", "0" },
                { null, "3" } }) {
            java.nio.file.Path f = java.nio.file.Files.createTempFile("nsx2", ".xml");
            if (c[0] == null) java.nio.file.Files.delete(f); else java.nio.file.Files.write(f, c[0].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String run = Ps2Patches.applyScript(true).replace("am force-stop xyz.aethersx2.android; ", "").replace(Ps2Patches.PREFS, f.toString());
            Process p = new ProcessBuilder("sh", "-c", run).redirectErrorStream(true).start();
            eq(String.valueOf(p.waitFor()), c[1], "exit code");
            if (c[0] != null) {
                String out = new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8);
                eq(out.split("EnableWideScreenPatches", -1).length, 2, "one widescreen entry");
                if (!out.contains("name=\"EmuCore/EnableWideScreenPatches\" value=\"true\"")) throw new AssertionError("value set: " + out);
                if (!out.trim().endsWith("</map>")) throw new AssertionError("map still closed");
                java.nio.file.Files.delete(f);
            }
        }
        System.out.println("Ps2PatchesTest passed");
    }
}
