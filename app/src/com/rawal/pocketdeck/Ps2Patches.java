package com.rawal.pocketdeck;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * PS2 widescreen patches. NetherSX2 ships the PCSX2 community widescreen database
 * (assets/cheats_ws.zip, one <CRC>.pnach per game) behind a single on/off setting. Nomad reads a
 * disc's SYSTEM.CNF to find its boot executable, computes PCSX2's game CRC from it (the XOR of
 * the ELF's 32-bit words), checks the emulator's own archive for a patch, and flips the setting
 * per launch through root, the way the PSP 60 FPS patches work.
 */
final class Ps2Patches {
    static final String PREFS = "/data/data/xyz.aethersx2.android/shared_prefs/xyz.aethersx2.android_preferences.xml";
    static final String KEY = "EmuCore/EnableWideScreenPatches";
    private static final Pattern BOOT = Pattern.compile("BOOT2\\s*=\\s*cdrom0?:\\\\?([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static Set<String> available;
    private static String availableFrom;

    /** {serial "SLUS-20896", crc "29396A53"} of a PS2 ISO. */
    static String[] identify(FileChannel ch) throws IOException {
        byte[] volume = GameArt.read(ch, 32768, 2048);
        if (!new String(volume, 1, 5, StandardCharsets.US_ASCII).equals("CD001")) throw new IOException("Not an ISO 9660 image");
        Map<String, long[]> root = GameArt.directory(ch, GameArt.u32(volume, 158), (int) GameArt.u32(volume, 166));
        long[] cnf = root.get("SYSTEM.CNF");
        if (cnf == null || cnf[1] > 4096) throw new IOException("No SYSTEM.CNF");
        Matcher m = BOOT.matcher(new String(GameArt.read(ch, cnf[0] * 2048, (int) cnf[1]), StandardCharsets.US_ASCII));
        if (!m.find()) throw new IOException("No BOOT2 line");
        String elfName = m.group(1).replace("\\", "/");
        elfName = elfName.substring(elfName.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        long[] elf = root.get(elfName);
        if (elf == null) throw new IOException("Boot file " + elfName + " not in the root folder");
        return new String[] { serial(elfName), String.format(Locale.US, "%08X", crc(ch, elf[0] * 2048, elf[1])) };
    }

    /** "SLUS_208.96" -> "SLUS-20896". */
    static String serial(String elfName) { return elfName.replace('_', '-').replace(".", ""); }

    /** PCSX2's game CRC: every little-endian 32-bit word of the boot ELF XORed together. */
    static int crc(FileChannel ch, long offset, long length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
        int crc = 0;
        long pos = offset, end = offset + (length & ~3L);
        while (pos < end) {
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), end - pos));
            int n = ch.read(buf, pos);
            if (n <= 0) throw new IOException("Short read");
            if ((n & 3) != 0) { buf.limit(n & ~3); n &= ~3; }
            buf.flip();
            while (buf.remaining() >= 4) crc ^= buf.getInt();
            pos += n;
        }
        return crc;
    }

    static int crc(byte[] elf) {
        ByteBuffer b = ByteBuffer.wrap(elf, 0, elf.length & ~3).order(ByteOrder.LITTLE_ENDIAN);
        int crc = 0;
        while (b.remaining() >= 4) crc ^= b.getInt();
        return crc;
    }

    /** CRCs with a widescreen patch in the installed NetherSX2's archive (empty when not installed). */
    static synchronized Set<String> available(Context ctx) {
        String apk;
        try { apk = ctx.getPackageManager().getApplicationInfo(Systems.NETHERSX2.pkgs[0], 0).sourceDir; }
        catch (Exception e) { return Collections.emptySet(); }
        if (apk.equals(availableFrom)) return available;
        Set<String> crcs = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry inner = zip.getEntry("assets/cheats_ws.zip");
            if (inner != null) {
                try (InputStream raw = zip.getInputStream(inner); ZipInputStream patches = new ZipInputStream(raw)) {
                    for (ZipEntry e; (e = patches.getNextEntry()) != null; ) {
                        String name = e.getName().toUpperCase(Locale.ROOT);
                        if (name.matches("[0-9A-F]{8}\\.PNACH")) crcs.add(name.substring(0, 8));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PocketDeck", "NetherSX2 patches", e);
        }
        available = crcs;
        availableFrom = apk;
        return crcs;
    }

    /** The last computed set, for callers on the UI thread; {@link #available} fills it off-thread. */
    static synchronized Set<String> cached() { return available == null ? Collections.<String>emptySet() : available; }

    /**
     * Root script: stop NetherSX2 so it re-reads its settings, then set the widescreen switch. The
     * file is rewritten in place (not sed -i) so it keeps NetherSX2's owner and SELinux label.
     */
    static String applyScript(boolean on) {
        return "am force-stop xyz.aethersx2.android; f=" + PREFS + "; [ -f $f ] || exit 3; "
            + "sed 's|name=\"" + KEY + "\" value=\"[a-z]*\"|name=\"" + KEY + "\" value=\"" + on + "\"|' $f > $f.nomad && cat $f.nomad > $f; rm -f $f.nomad";
    }
}
