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
import java.nio.ByteOrder;
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

    static long u32(byte[] bArr, int i) {
        return ((long) ByteBuffer.wrap(bArr, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()) & 4294967295L;
    }

    static Map<String, long[]> directory(FileChannel fileChannel, long j, int i) throws IOException {
        int i2;
        byte[] bArr = read(fileChannel, j * 2048, Math.min(i, 2097152));
        HashMap map = new HashMap();
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

    static String sfoString(byte[] bArr, String key) {
        try {
            int i = 20;
            if (bArr.length >= 20) {
                int i2 = 0;
                if (bArr[0] == 0 && bArr[1] == 80 && bArr[2] == 83 && bArr[3] == 70) {
                    int i3 = 8;
                    long jU32 = u32(bArr, 8);
                    long jU33 = u32(bArr, 12);
                    long jU34 = u32(bArr, 16);
                    while (i2 < Math.min(jU34, 512L)) {
                        int i4 = (i2 * 16) + i;
                        if (i4 + 16 > bArr.length) {
                            break;
                        }
                        int i5 = ((int) jU32) + ((bArr[i4] & 255) | ((bArr[i4 + 1] & 255) << i3));
                        int i6 = i5;
                        while (i6 < bArr.length && bArr[i6] != 0) {
                            i6++;
                        }
                        if (i5 >= 0 && i5 < bArr.length && new String(bArr, i5, i6 - i5, "UTF-8").equals(key)) {
                            long jU35 = u32(bArr, i4 + 12) + jU33;
                            long jU36 = u32(bArr, i4 + 4);
                            if (jU35 >= 0 && jU36 > 0) {
                                if (jU35 + jU36 <= bArr.length) {
                                    return new String(bArr, (int) jU35, (int) jU36, "UTF-8").replace("\u0000", "").trim();
                                }
                            }
                        }
                        i2++;
                    }
                    return "";
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
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
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(new File(file, str));
            try {
                bitmapDecodeByteArray.compress(Bitmap.CompressFormat.JPEG, 90, fileOutputStream);
                fileOutputStream.close();
                bitmapDecodeByteArray.recycle();
                return str;
            } catch (Throwable th) {
                try {
                    fileOutputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            bitmapDecodeByteArray.recycle();
            throw th3;
        }
    }

    static Result extract(ContentResolver contentResolver, Uri uri, File file, String str) {
        String str2;
        String str3;
        String str4;
        File file2;
        String str5;
        byte[] bArr;
        String str6;
        Result result = new Result();
        try {
            ParcelFileDescriptor parcelFileDescriptorOpenFileDescriptor = contentResolver.openFileDescriptor(uri, "r");
            try {
                ParcelFileDescriptor.AutoCloseInputStream autoCloseInputStream = new ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptorOpenFileDescriptor);
                try {
                    FileChannel channel = autoCloseInputStream.getChannel();
                    byte[] bArr2 = read(channel, 0L, 40);
                    HashMap map = new HashMap();
                    String str7 = "PIC1.PNG";
                    String str8 = "ICON0.PNG";
                    String str9 = "PARAM.SFO";
                    if (bArr2[0] == 0 && bArr2[1] == 80 && bArr2[2] == 66 && bArr2[3] == 80) {
                        String[] strArr = {"PARAM.SFO", "ICON0.PNG", "ICON1.PMF", "PIC0.PNG", "PIC1.PNG"};
                        int[] iArr = {0, 1, 4};
                        int i = 0;
                        for (int i2 = 3; i < i2; i2 = 3) {
                            int i3 = iArr[i];
                            int i4 = i3 * 4;
                            long jU32 = u32(bArr2, i4 + 8);
                            long jU33 = u32(bArr2, i4 + 12);
                            if (jU33 > jU32) {
                                bArr = bArr2;
                                str6 = str8;
                                long j = jU33 - jU32;
                                if (j < 8388608) {
                                    map.put(strArr[i3], read(channel, jU32, (int) j));
                                }
                            } else {
                                bArr = bArr2;
                                str6 = str8;
                            }
                            i++;
                            bArr2 = bArr;
                            str8 = str6;
                        }
                        str3 = "PARAM.SFO";
                        str4 = "PIC1.PNG";
                        str2 = str8;
                    } else {
                        byte[] bArr3 = read(channel, 32768L, 2048);
                        if (!new String(bArr3, 1, 5, "US-ASCII").equals("CD001")) {
                            autoCloseInputStream.close();
                            if (parcelFileDescriptorOpenFileDescriptor != null) {
                                parcelFileDescriptorOpenFileDescriptor.close();
                            }
                            return result;
                        }
                        long[] jArr = directory(channel, u32(bArr3, 158), (int) u32(bArr3, 166)).get("PSP_GAME");
                        if (jArr == null) {
                            autoCloseInputStream.close();
                            if (parcelFileDescriptorOpenFileDescriptor != null) {
                                parcelFileDescriptorOpenFileDescriptor.close();
                            }
                            return result;
                        }
                        Map<String, long[]> mapDirectory = directory(channel, jArr[0], (int) jArr[1]);
                        str2 = "ICON0.PNG";
                        String[] strArr2 = {"PARAM.SFO", str2, "PIC1.PNG"};
                        int i5 = 0;
                        while (i5 < 3) {
                            String str10 = strArr2[i5];
                            long[] jArr2 = mapDirectory.get(str10);
                            if (jArr2 != null && jArr2[1] > 0 && jArr2[1] < 8388608) {
                                map.put(str10, read(channel, jArr2[0] * 2048, (int) jArr2[1]));
                            }
                            i5++;
                            str7 = str7;
                            str9 = str9;
                        }
                        str3 = str9;
                        str4 = str7;
                    }
                    String str11 = str3;
                    if (map.containsKey(str11)) {
                        result.title = title((byte[]) map.get(str11));
                        result.discId = discId((byte[]) map.get(str11));
                    }
                    if (map.containsKey(str2)) {
                        str5 = str;
                        file2 = file;
                        result.icon = image((byte[]) map.get(str2), file2, str5 + "-icon.jpg");
                    } else {
                        file2 = file;
                        str5 = str;
                    }
                    String str12 = str4;
                    if (map.containsKey(str12)) {
                        result.background = image((byte[]) map.get(str12), file2, str5 + "-bg.jpg");
                    }
                    autoCloseInputStream.close();
                    if (parcelFileDescriptorOpenFileDescriptor != null) {
                        parcelFileDescriptorOpenFileDescriptor.close();
                    }
                } catch (Throwable th) {
                    try {
                        autoCloseInputStream.close();
                        throw th;
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                        throw th;
                    }
                }
            } catch (Throwable th3) {
                if (parcelFileDescriptorOpenFileDescriptor == null) {
                    throw th3;
                }
                try {
                    parcelFileDescriptorOpenFileDescriptor.close();
                    throw th3;
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                    throw th3;
                }
            }
        } catch (Exception e) {
        }
        return result;
    }
}
