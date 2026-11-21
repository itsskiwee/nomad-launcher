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
import android.graphics.RadialGradient;
import android.graphics.Rect;
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
    private final Path diamond = new Path();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::refreshVisibleArtwork;
    private Uri uri;
    private String cacheKey;
    private int accent = 0xff4e97e5;
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
        int nextColor = game == null ? 0xff4e97e5 : game.system.color;
        if (java.util.Objects.equals(uri, next) && accent == nextColor) return;
        uri = next;
        cacheKey = uri == null ? null : uri + "#" + thumbnailSize();
        accent = nextColor;
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
        background = new RadialGradient(width * (featured ? .78f : .55f), height * .46f,
                Math.max(width, height) * (featured ? .65f : .80f),
                new int[]{muted(accent), 0xff10233d, 0xff0b1527}, null, Shader.TileMode.CLAMP);
    }

    private static int muted(int color) {
        return Color.rgb(Color.red(color) * 2 / 5, Color.green(color) * 2 / 5, Color.blue(color) / 2);
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
        float ratio = Math.max((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        int cropWidth = Math.round(width / ratio);
        int cropHeight = Math.round(height / ratio);
        int x = (bitmap.getWidth() - cropWidth) / 2;
        int y = (bitmap.getHeight() - cropHeight) / 2;
        source.set(x, y, x + cropWidth, y + cropHeight);
        destination.set(0, 0, width, height);
        paint.setAlpha(255);
        canvas.drawBitmap(bitmap, source, destination, paint);
    }

    private void drawPlaceholder(Canvas canvas, int width, int height) {
        paint.setAlpha(255);
        paint.setShader(background);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        float cx = width * (featured ? .78f : .51f);
        float cy = height * .50f;
        float unit = Math.min(width, height);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, unit * .004f));
        for (int i = 0; i < 6; i++) {
            float radius = unit * (.15f + .095f * i);
            paint.setColor((accent & 0xffffff) | ((86 - 12 * i) << 24));
            diamond.rewind();
            diamond.moveTo(cx, cy - radius);
            diamond.lineTo(cx + radius * 1.27f, cy);
            diamond.lineTo(cx, cy + radius);
            diamond.lineTo(cx - radius * 1.27f, cy);
            diamond.close();
            canvas.drawPath(diamond, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((accent & 0xffffff) | 0x93000000);
        canvas.drawCircle(cx, cy, unit * .022f, paint);
    }

    static final class Shade extends View {
        private final Paint paint = new Paint();

        Shade(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            int[] colors = {0xff0d192d, 0xe30d192d, 0x550d192d, 0x050d192d};
            if (width > 0) paint.setShader(new LinearGradient(0, 0, width, 0, colors,
                    new float[]{0f, .40f, .76f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
