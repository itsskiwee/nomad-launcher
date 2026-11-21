package com.rawal.nomadlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
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
    private static volatile Bitmap wallpaper;

    private final boolean featured;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private final Rect visible = new Rect();
    private final RectF photoBounds = new RectF();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::refreshVisibleArtwork;
    private Uri uri;
    private String cacheKey;
    private GameSystem system;
    private int accent = 0xffa0e4e3;
    private Shader verticalShade, horizontalShade;
    private boolean loading, unreadable, wallpaperRequested;
    private int requestEpoch, requestId;

    Artwork(Context context, boolean featured) {
        super(context);
        this.featured = featured;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(Library.Game game) {
        Uri next = game == null ? null : featured && game.backdrop != null ? game.backdrop : game.art;
        GameSystem nextSystem = game == null ? null : game.system;
        if (java.util.Objects.equals(uri, next) && system == nextSystem) return;
        uri = next;
        cacheKey = uri == null ? null : uri + "#" + thumbnailSize();
        system = nextSystem;
        accent = system == null ? 0xffa0e4e3 : system.color;
        requestId++;
        loading = false;
        unreadable = false;
        invalidate();
    }

    static void clear() {
        epoch++;
        CACHE.evictAll();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (!featured || width <= 0 || height <= 0) return;
        verticalShade = new LinearGradient(0, 0, 0, height,
                new int[]{0x85040b15, 0x3c040b15, 0x06040b15, 0xcf040b15},
                new float[]{0f, .24f, .53f, 1f}, Shader.TileMode.CLAMP);
        horizontalShade = new LinearGradient(0, 0, width, 0, 0x66040b15, 0x00040b15,
                Shader.TileMode.CLAMP);
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

    private int thumbnailSize() { return featured ? 1024 : 288; }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (height == 0 || width == 0) return;
        Bitmap bitmap = cacheKey == null ? null : CACHE.get(cacheKey);
        if (cacheKey != null && bitmap == null && canLoad() && isShown() && getGlobalVisibleRect(visible)) {
            load(uri, cacheKey, thumbnailSize());
        }
        if (featured) drawBackdrop(canvas, bitmap, width, height);
        else if (bitmap != null) drawCrop(canvas, bitmap, width, height, .5f);
        else drawPlaceholder(canvas, width, height);
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

    private void drawBackdrop(Canvas canvas, Bitmap art, int width, int height) {
        boolean landscapeArt = art != null && art.getWidth() >= art.getHeight() * 1.35f;
        float cropBias = width < height ? .76f : .5f;
        if (landscapeArt) {
            drawCrop(canvas, art, width, height, cropBias);
        } else {
            Bitmap background = getWallpaper();
            if (background == null) canvas.drawColor(0xff101d2c);
            else drawCrop(canvas, background, width, height, cropBias);
            if (art != null) drawCover(canvas, art, width, height);
        }
        paint.setAlpha(255);
        paint.setShader(horizontalShade);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(verticalShade);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
    }

    private Bitmap getWallpaper() {
        Bitmap existing = wallpaper;
        if (existing != null || wallpaperRequested) return existing;
        wallpaperRequested = true;
        android.content.res.Resources resources = getResources();
        java.lang.ref.WeakReference<Artwork> view = new java.lang.ref.WeakReference<>(this);
        IO.execute(() -> {
            if (wallpaper == null) wallpaper = BitmapFactory.decodeResource(resources, R.drawable.coast);
            Artwork target = view.get();
            if (target != null) target.postInvalidate();
        });
        return null;
    }

    private void drawCover(Canvas canvas, Bitmap art, int width, int height) {
        boolean landscape = width >= height;
        float maxWidth = width * (landscape ? .31f : .43f);
        float maxHeight = height * (landscape ? .52f : .38f);
        float scale = Math.min(maxWidth / art.getWidth(), maxHeight / art.getHeight());
        float coverWidth = art.getWidth() * scale;
        float coverHeight = art.getHeight() * scale;
        float x = width * (landscape ? .73f : .5f) - coverWidth / 2;
        float y = height * (landscape ? .59f : .56f) - coverHeight / 2;
        photoBounds.set(x, y, x + coverWidth, y + coverHeight);
        paint.setAlpha(255);
        canvas.drawBitmap(art, null, photoBounds, paint);
    }

    private void drawCrop(Canvas canvas, Bitmap bitmap, int width, int height, float horizontalBias) {
        float ratio = Math.max((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        int cropWidth = Math.max(1, Math.round(width / ratio));
        int cropHeight = Math.max(1, Math.round(height / ratio));
        int x = Math.round((bitmap.getWidth() - cropWidth) * horizontalBias);
        int y = (bitmap.getHeight() - cropHeight) / 2;
        source.set(x, y, x + cropWidth, y + cropHeight);
        destination.set(0, 0, width, height);
        paint.setAlpha(255);
        canvas.drawBitmap(bitmap, source, destination, paint);
    }

    private void drawPlaceholder(Canvas canvas, int width, int height) {
        int tone = Color.rgb(20 + Color.red(accent) / 9,
                25 + Color.green(accent) / 9, 31 + Color.blue(accent) / 9);
        canvas.drawColor(tone);
        if (system == null) return;
        String name = system == GameSystem.GENESIS ? "MD" : system.name();
        paint.setColor(0xffd2dce7);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.min(width, height) * .19f);
        canvas.drawText(name, width * .5f, height * .5f - (paint.ascent() + paint.descent()) / 2, paint);
    }
}
