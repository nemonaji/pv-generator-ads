package com.dupfinder.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads list thumbnails off the main thread with a small memory cache. */
final class ThumbLoader {
    private final ContentResolver cr;
    private final int px;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(Long key, Bitmap value) { return value.getByteCount() / 1024; }
    };

    ThumbLoader(ContentResolver cr, int px) {
        this.cr = cr;
        this.px = px;
    }

    void load(final ImageItem it, final ImageView view) {
        final Long key = it.id;
        view.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) { view.setImageBitmap(b); return; }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) bm = cr.loadThumbnail(it.uri, new Size(px, px), null);
            } catch (Throwable ignored) { }
            if (bm == null) {
                try { bm = Decoder.decode(cr, it.uri, px, null); } catch (Throwable ignored) { }
            }
            if (bm == null) return;
            cache.put(key, bm);
            final Bitmap res = bm;
            main.post(() -> { if (key.equals(view.getTag())) view.setImageBitmap(res); });
        });
    }

    void shutdown() { pool.shutdownNow(); }
}
