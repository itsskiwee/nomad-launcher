package com.rawal.pocketdeck;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;

public final class GameArtTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        byte[] integers = {0, 0x78, 0x56, 0x34, 0x12, -1, -1, -1, -1};
        check(GameArt.u32(integers, 1) == 0x12345678L, "unaligned little endian");
        check(GameArt.u32(integers, 5) == 0xffffffffL, "unsigned integer");

        byte[] sfo = new byte[128];
        ByteBuffer b = ByteBuffer.wrap(sfo).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x46535000).putInt(8, 52).putInt(12, 80).putInt(16, 2);
        b.putShort(20, (short) 0).putInt(24, 5).putInt(32, 0);
        b.putShort(36, (short) 6).putInt(40, 10).putInt(48, 8);
        System.arraycopy("TITLE\0DISC_ID\0".getBytes(StandardCharsets.UTF_8), 0, sfo, 52, 14);
        System.arraycopy("Game\0".getBytes(StandardCharsets.UTF_8), 0, sfo, 80, 5);
        System.arraycopy("ULUS10336\0".getBytes(StandardCharsets.UTF_8), 0, sfo, 88, 10);
        check(GameArt.title(sfo).equals("Game"), "SFO title");
        check(GameArt.discId(sfo).equals("ULUS10336"), "SFO disc ID");
        check(GameArt.title(Arrays.copyOf(sfo, 30)).isEmpty(), "truncated SFO");
        b.putInt(32, -1);
        check(GameArt.title(sfo).isEmpty(), "invalid SFO extent");

        b.putInt(32, 0).putInt(8, -1);
        check(GameArt.title(sfo).isEmpty(), "unsigned key table offset");
        b.putInt(8, 52).putInt(12, -1);
        check(GameArt.discId(sfo).isEmpty(), "unsigned value table offset");
        b.putInt(12, 80).putInt(16, -1);
        check(GameArt.discId(sfo).equals("ULUS10336"), "entry count bounded by buffer");
        check(GameArt.sfoString(sfo, "TIT").isEmpty(), "key prefix must not match");

        byte[] sector = new byte[4096];
        // A zero-length record skips to the next sector.
        int offset = 2048;
        byte[] name = "PARAM.SFO;1".getBytes(StandardCharsets.US_ASCII);
        sector[offset] = (byte) (33 + name.length);
        sector[offset + 32] = (byte) name.length;
        System.arraycopy(name, 0, sector, offset + 33, name.length);
        ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(offset + 2, 123).putInt(offset + 10, 456);
        Path file = Files.createTempFile("nomad-art-", ".bin");
        try {
            Files.write(file, sector);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                Map<String, long[]> entries = GameArt.directory(channel, 0, sector.length);
                check(Arrays.equals(entries.get("PARAM.SFO"), new long[]{123, 456}), "ISO directory");
                check(Arrays.equals(GameArt.read(channel, 2048, 4), Arrays.copyOfRange(sector, 2048, 2052)), "positional read");
                for (long start : new long[]{-1, sector.length}) {
                    try {
                        GameArt.read(channel, start, 1);
                        throw new AssertionError("invalid read accepted");
                    } catch (IOException expected) { }
                }
            }
        } finally {
            Files.delete(file);
        }
        System.out.println("GameArt metadata tests passed");
    }
}
