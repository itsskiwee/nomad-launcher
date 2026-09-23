package com.rawal.pocketdeck;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class GameArt {

    static class Result {
        String title = "";
        String discId = "";
        String icon = "";
        String background = "";

        Result() {
        }
    }

    GameArt() {
    }

    static byte[] read(FileChannel fileChannel, long j, int i) throws IOException {
        if (j < 0 || i < 0 || i > 8388608 || ((long) i) + j > fileChannel.size()) {
            throw new IOException("Invalid metadata extent");
        }
        ByteBuffer byteBufferAllocate = ByteBuffer.allocate(i);
        int i2 = 0;
        while (byteBufferAllocate.hasRemaining()) {
            int i3 = fileChannel.read(byteBufferAllocate, ((long) byteBufferAllocate.position()) + j);
            if (i3 < 0) {
                throw new EOFException();
            }
            if (i3 == 0 && (i2 = i2 + 1) > 3) {
                throw new IOException("Unreadable game metadata");
            }
        }
        return byteBufferAllocate.array();
    }

    static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }

    static Map<String, long[]> directory(FileChannel fileChannel, long j, int i) throws IOException {
        int i2;
        byte[] bArr = read(fileChannel, j * 2048, Math.min(i, 2097152));
        Map<String, long[]> map = new HashMap<>();
        int i3 = 0;
        while (i3 < bArr.length) {
            int i4 = bArr[i3] & 255;
            if (i4 == 0) {
                i3 = ((i3 / 2048) + 1) * 2048;
            } else {
                if (i4 < 34 || (i2 = i3 + i4) > bArr.length) {
                    break;
                }
                int i5 = bArr[i3 + 32] & 255;
                if (i5 > 0 && i5 + 33 <= i4) {
                    map.put(new String(bArr, i3 + 33, i5, StandardCharsets.US_ASCII).replace(";1", ""), new long[]{u32(bArr, i3 + 2), u32(bArr, i3 + 10)});
                }
                i3 = i2;
            }
        }
        return map;
    }

    static String title(byte[] bArr) { return sfoString(bArr, "TITLE"); }

    /** PPSSPP names its per-game files after DISC_ID ("ULUS10336"). */
    static String discId(byte[] bArr) { return sfoString(bArr, "DISC_ID"); }

    static String sfoString(byte[] data, String key) {
        if (data.length < 20 || data[0] != 0 || data[1] != 'P'
                || data[2] != 'S' || data[3] != 'F') return "";
        long keys = u32(data, 8), values = u32(data, 12);
        int count = (int) Math.min(u32(data, 16), Math.min(512, (data.length - 20) / 16));
        byte[] wanted = key.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < count; i++) {
            int entry = 20 + i * 16;
            long name = keys + ((data[entry] & 255) | ((data[entry + 1] & 255) << 8));
            if (name + wanted.length >= data.length) continue;
            int start = (int) name, matched = 0;
            while (matched < wanted.length && data[start + matched] == wanted[matched]) matched++;
            if (matched != wanted.length || data[start + matched] != 0) continue;
            long value = values + u32(data, entry + 12), length = u32(data, entry + 4);
            if (length == 0 || value + length > data.length) return "";
            return new String(data, (int) value, (int) length, StandardCharsets.UTF_8)
                    .replace("\u0000", "").trim();
        }
        return "";
    }

    static String image(byte[] bArr, File file, String str) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bArr, 0, bArr.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 8192 || options.outHeight > 8192) {
            return "";
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 1024);
        Bitmap bitmapDecodeByteArray = BitmapFactory.decodeByteArray(bArr, 0, bArr.length, options);
        if (bitmapDecodeByteArray == null) {
            return "";
        }
        try (FileOutputStream output = new FileOutputStream(new File(file, str))) {
            bitmapDecodeByteArray.compress(Bitmap.CompressFormat.JPEG, 90, output);
            return str;
        } finally {
            bitmapDecodeByteArray.recycle();
        }
    }

    static Result extract(ContentResolver resolver, Uri uri, File outputDir, String key) {
        Result result = new Result();
        try {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor == null) return result;
            // AutoCloseInputStream owns the descriptor, including on early returns.
            try (ParcelFileDescriptor.AutoCloseInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                FileChannel channel = input.getChannel();
                byte[] header = read(channel, 0, 40);
                String[] names = {"PARAM.SFO", "ICON0.PNG", "PIC1.PNG"};
                Map<String, long[]> entries;
                boolean pbp = header[0] == 0 && header[1] == 'P'
                        && header[2] == 'B' && header[3] == 'P';
                if (pbp) {
                    entries = new HashMap<>();
                    int[] indices = {0, 1, 4};
                    for (int i = 0; i < indices.length; i++) {
                        int offset = 8 + indices[i] * 4;
                        long start = u32(header, offset);
                        entries.put(names[i], new long[]{start, u32(header, offset + 4) - start});
                    }
                } else {
                    byte[] volume = read(channel, 32768, 2048);
                    if (!new String(volume, 1, 5, StandardCharsets.US_ASCII).equals("CD001")) return result;
                    long[] game = directory(channel, u32(volume, 158), (int) u32(volume, 166)).get("PSP_GAME");
                    if (game == null) return result;
                    entries = directory(channel, game[0], (int) game[1]);
                }
                // Keep only one asset's compressed bytes alive at a time.
                for (String name : names) {
                    long[] entry = entries.get(name);
                    if (entry == null || entry[1] <= 0 || entry[1] >= 8388608) continue;
                    byte[] data = read(channel, entry[0] * (pbp ? 1 : 2048), (int) entry[1]);
                    if (name.equals("PARAM.SFO")) {
                        result.title = title(data);
                        result.discId = discId(data);
                    } else if (name.equals("ICON0.PNG")) {
                        result.icon = image(data, outputDir, key + "-icon.jpg");
                    } else {
                        result.background = image(data, outputDir, key + "-bg.jpg");
                    }
                }
            }
        } catch (Exception ignored) {
            // Missing or malformed embedded artwork must not prevent importing a game.
        }
        return result;
    }
}
