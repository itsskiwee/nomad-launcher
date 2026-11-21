package com.rawal.nomadlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.util.LruCache;
import android.view.View;
import android.view.ViewTreeObserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Artwork extends View {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(5 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private static int epoch;

    private final boolean featured;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private final Rect visible = new Rect();
    private final RectF photoBounds = new RectF();
    private final Path outline = new Path();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::refreshVisibleArtwork;
    private Uri uri;
    private String cacheKey;
    private GameSystem system;
    private int accent = 0xffa0e4e3;
    private Shader background;
    private boolean loading, unreadable;
    private int requestEpoch, requestId;

    Artwork(Context context, boolean featured) {
        super(context);
        this.featured = featured;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(Library.Game game) {
        Uri next = game == null ? null : game.art;
        GameSystem nextSystem = game == null ? null : game.system;
        if (java.util.Objects.equals(uri, next) && system == nextSystem) return;
        uri = next;
        cacheKey = uri == null ? null : uri + "#" + thumbnailSize();
        system = nextSystem;
        accent = system == null ? 0xffa0e4e3 : system.color;
        requestId++;
        loading = false;
        unreadable = false;
        makeBackground(getWidth(), getHeight());
        invalidate();
    }

    static void clear() {
        epoch++;
        CACHE.evictAll();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        makeBackground(width, height);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        requestId++;
        loading = false;
        super.onDetachedFromWindow();
    }

    private void refreshVisibleArtwork() {
        if (cacheKey != null && canLoad() && isShown() && getGlobalVisibleRect(visible)
                && CACHE.get(cacheKey) == null) invalidate();
    }

    private boolean canLoad() {
        return requestEpoch != epoch || (!loading && !unreadable);
    }

    private int thumbnailSize() { return featured ? 720 : 288; }

    private void makeBackground(int width, int height) {
        if (width <= 0 || height <= 0) return;
        int tone = Color.rgb(12 + Color.red(accent) / 14,
                16 + Color.green(accent) / 11, 24 + Color.blue(accent) / 10);
        background = new LinearGradient(0, 0, width, height,
                tone, 0xff0e151e, Shader.TileMode.CLAMP);
    }

    private int tint(int alpha) {
        return (accent & 0xffffff) | alpha << 24;
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (height == 0 || width == 0) return;
        if (cacheKey != null) {
            Bitmap bitmap = CACHE.get(cacheKey);
            if (bitmap != null) {
                drawPhoto(canvas, bitmap, width, height);
                return;
            }
            if (canLoad() && isShown() && getGlobalVisibleRect(visible)) {
                load(uri, cacheKey, thumbnailSize());
            }
        }
        drawPlaceholder(canvas, width, height);
    }

    private void load(Uri document, String key, int maxSide) {
        loading = true;
        requestEpoch = epoch;
        int cacheVersion = requestEpoch;
        int ticket = ++requestId;
        ContentResolver resolver = getContext().getApplicationContext().getContentResolver();
        // A queued request for a card that left the screen can be discarded before decoding.
        java.lang.ref.WeakReference<Artwork> view = new java.lang.ref.WeakReference<>(this);
        IO.execute(() -> {
            Artwork target = view.get();
            if (target == null || !target.isAttachedToWindow()) return;
            Bitmap bitmap = decode(resolver, document, maxSide);
            target.post(() -> {
                if (cacheVersion != epoch) return;
                if (bitmap != null) CACHE.put(key, bitmap);
                if (ticket == target.requestId) {
                    target.loading = false;
                    target.unreadable = bitmap == null;
                    target.invalidate();
                }
            });
        });
    }

    private static Bitmap decode(ContentResolver resolver, Uri uri, int maxSide) {
        try (InputStream stream = resolver.openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (stream == null) return null;
            byte[] chunk = new byte[16384];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (bytes.size() + read > MAX_BYTES) return null;
                bytes.write(chunk, 0, read);
            }
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes.toByteArray())),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        double ratio = Math.min(1.0, (double) maxSide / Math.max(width, height));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        decoder.setTargetSize(Math.max(1, (int) (width * ratio)), Math.max(1, (int) (height * ratio)));
                    });
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void drawPhoto(Canvas canvas, Bitmap bitmap, int width, int height) {
        paint.setAlpha(255);
        if (featured) {
            canvas.drawColor(0xff121c25);
            float scale = Math.min((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
            float scaledWidth = bitmap.getWidth() * scale;
            float scaledHeight = bitmap.getHeight() * scale;
            float x = (width - scaledWidth) / 2;
            float y = (height - scaledHeight) / 2;
            photoBounds.set(x, y, x + scaledWidth, y + scaledHeight);
            canvas.drawBitmap(bitmap, null, photoBounds, paint);
            return;
        }
        float ratio = Math.max((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        int cropWidth = Math.round(width / ratio);
        int cropHeight = Math.round(height / ratio);
        int x = (bitmap.getWidth() - cropWidth) / 2;
        int y = (bitmap.getHeight() - cropHeight) / 2;
        source.set(x, y, x + cropWidth, y + cropHeight);
        destination.set(0, 0, width, height);
        canvas.drawBitmap(bitmap, source, destination, paint);
    }

    private void drawPlaceholder(Canvas canvas, int width, int height) {
        paint.setAlpha(255);
        paint.setShader(background);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        float scale = Math.min(width / 320f, height / 212f);
        canvas.save();
        canvas.translate((width - 320f * scale) / 2, (height - 212f * scale) / 2);
        canvas.scale(scale, scale);
        if (featured) drawGrid(canvas, width / scale, height / scale);
        if (system == null || system == GameSystem.PSP || system == GameSystem.DS
                || system == GameSystem.GB || system == GameSystem.GBC || system == GameSystem.GBA
                || system == GameSystem.GG) {
            drawHandheld(canvas);
        } else if (system == GameSystem.PS1 || system == GameSystem.DC) {
            drawDisc(canvas);
        } else {
            drawCartridge(canvas);
        }
        canvas.restore();
    }

    private void drawGrid(Canvas canvas, float width, float height) {
        float horizontal = (width - 320f) / 2;
        float vertical = (height - 212f) / 2;
        paint.setColor(tint(20));
        for (float y = 10; y < 212 + vertical; y += 16) {
            canvas.drawLine(-horizontal, y, 320 + horizontal, y, paint);
        }
        for (float y = -6; y > -vertical; y -= 16) {
            canvas.drawLine(-horizontal, y, 320 + horizontal, y, paint);
        }
        for (float x = 8; x < 320 + horizontal; x += 16) {
            canvas.drawLine(x, -vertical, x, 212 + vertical, paint);
        }
        for (float x = -8; x > -horizontal; x -= 16) {
            canvas.drawLine(x, -vertical, x, 212 + vertical, paint);
        }
    }

    private void drawHandheld(Canvas canvas) {
        canvas.save();
        canvas.rotate(-12, 160, 106);
        paint.setColor(0xff0a0f15);
        canvas.drawRoundRect(42, 63, 282, 163, 19, 19, paint);
        face(canvas, 38, 51, 278, 150, 18);
        paint.setColor(0xff0c151e);
        canvas.drawRoundRect(89, 63, 226, 137, 7, 7, paint);
        paint.setColor(tint(135));
        canvas.drawRoundRect(97, 70, 218, 130, 3, 3, paint);
        paint.setColor(0xff263e4b);
        canvas.drawRect(102, 75, 213, 125, paint);
        paint.setColor(tint(110));
        if (system == GameSystem.DS) {
            canvas.drawRect(126, 80, 189, 97, paint);
            canvas.drawRect(126, 103, 189, 120, paint);
        } else {
            outline.rewind();
            outline.moveTo(102, 125);
            outline.lineTo(144, 89);
            outline.lineTo(165, 108);
            outline.lineTo(185, 95);
            outline.lineTo(213, 120);
            outline.lineTo(213, 125);
            outline.close();
            canvas.drawPath(outline, paint);
            canvas.drawCircle(192, 85, 5, paint);
        }
        paint.setColor(0xffb5c4c7);
        canvas.drawRoundRect(59, 88, 67, 113, 2, 2, paint);
        canvas.drawRoundRect(51, 96, 75, 104, 2, 2, paint);
        paint.setColor(tint(220));
        canvas.drawCircle(251, 93, 6, paint);
        canvas.drawCircle(241, 106, 6, paint);
        paint.setColor(tint(110));
        canvas.drawRoundRect(126, 144, 190, 147, 1, 1, paint);
        canvas.restore();
    }

    private void drawDisc(Canvas canvas) {
        canvas.save();
        canvas.rotate(-18, 160, 106);
        paint.setColor(0xff0b141b);
        canvas.drawCircle(164, 113, 63, paint);
        paint.setColor(tint(105));
        canvas.drawCircle(160, 105, 62, paint);
        paint.setColor(0xff24313b);
        canvas.drawCircle(160, 105, 59, paint);
        paint.setColor(tint(120));
        canvas.drawArc(104, 49, 216, 161, 203, 91, true, paint);
        paint.setColor(tint(200));
        canvas.drawArc(106, 51, 214, 159, 219, 12, true, paint);
        paint.setColor(0xff17232e);
        canvas.drawCircle(160, 105, 44, paint);
        paint.setColor(tint(132));
        canvas.drawCircle(160, 105, 20, paint);
        paint.setColor(0xff0c141c);
        canvas.drawCircle(160, 105, 10, paint);
        canvas.restore();
    }

    private void drawCartridge(Canvas canvas) {
        canvas.save();
        canvas.rotate(-12, 160, 106);
        paint.setColor(0xff091018);
        canvas.drawRoundRect(99, 51, 229, 172, 11, 11, paint);
        face(canvas, 95, 41, 225, 161, 11);
        paint.setColor(0xff0e1921);
        canvas.drawRoundRect(109, 58, 211, 115, 3, 3, paint);
        paint.setColor(tint(185));
        canvas.drawRoundRect(113, 62, 207, 111, 2, 2, paint);
        paint.setColor(tint(90));
        canvas.drawRect(113, 91, 207, 111, paint);
        paint.setColor(0xff212d36);
        canvas.drawRoundRect(113, 135, 207, 157, 3, 3, paint);
        paint.setColor(tint(140));
        for (int x = 120; x < 200; x += 8) {
            canvas.drawRect(x, 145, x + 4, 153, paint);
        }
        canvas.restore();
    }

    private void face(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        paint.setColor(tint(145));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setColor(0xff273640);
        canvas.drawRoundRect(left + 2, top + 2, right - 2, bottom - 2, radius - 2, radius - 2, paint);
    }
}
