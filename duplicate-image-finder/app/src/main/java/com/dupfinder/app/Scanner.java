package com.dupfinder.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import com.dupfinder.R;
import com.dupfinder.core.Fp;
import com.dupfinder.core.Matcher;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Background pipeline: list images, fingerprint them, hash same-size files, compare all pairs. */
final class Scanner {
    interface Listener {
        void onStatus(String text, int permille);
        void onDone(Result result);
        void onCancelled();
        void onError(Throwable t);
    }

    static final class Result {
        ImageItem[] items;
        Fp[] fps;
        long[] dates;
        List<Matcher.Edge> edges;
        double seconds;
    }

    /** Images are decoded to at least this long side before being averaged down to the fingerprint. */
    private static final int DECODE_SIDE = 512;

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile long lastPost;

    Scanner(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(() -> {
            try {
                final Result r = run();
                main.post(() -> { if (r == null) listener.onCancelled(); else listener.onDone(r); });
            } catch (final Throwable e) {
                main.post(() -> listener.onError(e));
            }
        }, "scanner");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    void cancel() { cancel.set(true); }

    private void status(final String text, final int permille, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastPost < 80) return;
        lastPost = now;
        main.post(() -> { if (!cancel.get()) listener.onStatus(text, permille); });
    }

    private Result run() throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        final ContentResolver cr = app.getContentResolver();
        status(app.getString(R.string.status_listing), 0, true);
        final ImageItem[] items = listImages(cr);
        final int n = items.length;
        final Fp[] fps = new Fp[n];
        final File cacheFile = new File(app.getFilesDir(), "fingerprints.bin");
        final Map<Long, FpCache.Entry> cache = FpCache.load(cacheFile);
        final int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

        // 1) fingerprints (decode is the slow part, so it runs on all cores and is cached)
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                int i;
                while (!cancel.get() && (i = next.getAndIncrement()) < n) {
                    ImageItem it = items[i];
                    FpCache.Entry e = cache.get(it.id);
                    if (e != null && e.size == it.size && e.date == it.date) fps[i] = e.fp;
                    else fps[i] = fingerprint(cr, it);
                    int d = done.incrementAndGet();
                    status(app.getString(R.string.status_analyze, d, n), (int) (700L * d / Math.max(1, n)), d == n);
                }
            }, "fp-" + t);
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        if (cancel.get()) { FpCache.save(cacheFile, items, fps); return null; }

        // 2) content hash, only for files that share their byte size with another file
        status(app.getString(R.string.status_hash), 700, true);
        Map<Long, List<Integer>> bySize = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (fps[i] == null) continue;
            List<Integer> l = bySize.get(items[i].size);
            if (l == null) bySize.put(items[i].size, l = new ArrayList<>());
            l.add(i);
        }
        for (List<Integer> l : bySize.values()) {
            if (l.size() < 2) continue;
            for (int i : l) {
                if (cancel.get()) break;
                if (fps[i].hash == 0) fps[i].hash = contentHash(cr, items[i].uri);
            }
        }
        FpCache.save(cacheFile, items, fps);
        if (cancel.get()) return null;

        // 3) compare every pair
        List<Matcher.Edge> edges = Matcher.findPairs(fps, threads,
                (d, total) -> {
                    // row i compares against n-i later images, so progress is quadratic in rows done
                    double f = (double) d / Math.max(1, total);
                    double frac = 1 - (1 - f) * (1 - f);
                    status(app.getString(R.string.status_compare, (int) (frac * 100)), 720 + (int) (280 * frac), false);
                }, cancel);
        if (cancel.get()) return null;

        Result r = new Result();
        r.items = items;
        r.fps = fps;
        r.dates = new long[n];
        for (int i = 0; i < n; i++) r.dates[i] = items[i].date;
        r.edges = edges;
        r.seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0;
        return r;
    }

    private static ImageItem[] listImages(ContentResolver cr) {
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
        List<ImageItem> out = new ArrayList<>();
        try (Cursor c = cr.query(base, proj, null, null, MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            if (c == null) return new ImageItem[0];
            while (c.moveToNext()) {
                ImageItem it = new ImageItem();
                it.id = c.getLong(0);
                it.uri = ContentUris.withAppendedId(base, it.id);
                it.name = c.getString(1);
                it.size = c.getLong(2);
                it.date = c.getLong(3);
                it.folder = c.getString(4);
                if (it.name == null) it.name = String.valueOf(it.id);
                if (it.size > 0) out.add(it);
            }
        }
        return out.toArray(new ImageItem[0]);
    }

    private static Fp fingerprint(ContentResolver cr, ImageItem it) {
        Bitmap bmp = null;
        try {
            int[] dims = new int[2];
            bmp = Decoder.decode(cr, it.uri, DECODE_SIDE, dims);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w < 8 || h < 8) return null;
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, false);
                bmp.recycle();
                bmp = c;
                if (bmp == null) return null;
            }
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            // the decoder may have applied the EXIF rotation; keep full-size dims in the same orientation
            int ow = dims[0], oh = dims[1];
            if (ow <= 0 || oh <= 0) { ow = w; oh = h; }
            if ((w > h) != (ow > oh)) { int t = ow; ow = oh; oh = t; }
            Fp p = Fp.fromArgb(px, w, h, ow, oh);
            p.size = it.size;
            return p;
        } catch (Throwable e) { // unreadable or unsupported file, or out of memory on a huge image
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static long contentHash(ContentResolver cr, Uri uri) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return 0;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            long h = ByteBuffer.wrap(md.digest()).getLong();
            return h == 0 ? 1 : h;
        } catch (Exception e) {
            return 0;
        }
    }
}
