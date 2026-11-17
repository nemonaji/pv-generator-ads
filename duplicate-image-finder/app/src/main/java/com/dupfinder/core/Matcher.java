package com.dupfinder.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Decides whether two fingerprints show the same picture, and in which way they differ. */
public final class Matcher {
    public static final int NONE = 0;
    // Match types are bit flags: a pair can be e.g. RESIZED | EDITED or CROPPED | EDITED.
    /** Byte-identical files. */
    public static final int EXACT = 1;
    /** Same picture, only resolution / compression differs. */
    public static final int RESIZED = 2;
    /** Same picture with a local change (text, sticker, watermark...). */
    public static final int EDITED = 4;
    /** One picture is a cropped part of the other. */
    public static final int CROPPED = 8;
    public static final int ALL = EXACT | RESIZED | EDITED | CROPPED;

    // --- tuning ---
    /** Max L1 distance between colour histograms for a pair to be examined at all. */
    static final float HIST_MAX = 0.50f;
    /** Stricter histogram limit before the (costly) crop search. */
    static final float HIST_MAX_CROP = 0.40f;
    /** Aspect ratios closer than this are compared frame-to-frame. */
    static final double ASPECT_TOL = 0.03;
    /** A crop must keep at least this fraction of each side of the original. */
    static final double MIN_CROP_SIDE = 0.4;
    static final int CELL = 4;

    public static final class Result {
        public int type;
        public float mad, goodMad, badFrac, ncc, cover = 1f;
        /** For CROPPED: true when the first image is the uncropped one. */
        public boolean firstIsWhole;
        @Override public String toString() {
            return String.format("t=%d mad=%.1f good=%.1f bad=%.2f ncc=%.3f cover=%.2f", type, mad, goodMad, badFrac, ncc, cover);
        }
    }

    public static final class Edge {
        public final int a, b, type;
        /** For CROPPED pairs: index of the uncropped image, otherwise -1. */
        public final int whole;
        public Edge(int a, int b, int type, int whole) { this.a = a; this.b = b; this.type = type; this.whole = whole; }
    }

    public interface Progress { void onProgress(int done, int total); }

    public static float histDist(float[] a, float[] b, float limit) {
        float d = 0;
        for (int i = 0; i < a.length; i++) {
            d += Math.abs(a[i] - b[i]);
            if (d > limit) return d;
        }
        return d;
    }

    public static boolean sameBytes(Fp a, Fp b) {
        return a.size == b.size && a.hash != 0 && a.hash == b.hash;
    }

    public static Result compare(Fp a, Fp b) {
        Result r = new Result();
        if (sameBytes(a, b)) { r.type = EXACT; r.ncc = 1; return r; }
        if (histDist(a.hist, b.hist, HIST_MAX) > HIST_MAX) return r;
        double ra = (double) a.w / a.h, rb = (double) b.w / b.h;
        if (Math.abs(ra / rb - 1) < ASPECT_TOL) {
            // same shape: compare whole frames (use the larger thumbnail as the grid)
            Fp p = a.tw * a.th >= b.tw * b.th ? a : b, q = p == a ? b : a;
            int[] x = toInt(p.g);
            int[] y = resize(toInt(q.g), q.tw, q.th, p.tw, p.th);
            analyze(x, y, p.tw, p.th, 8f, 32, r);
            if (r.badFrac == 0 && r.mad <= 4f) { r.type = RESIZED; return r; }
            if (r.badFrac <= 0.25f && r.goodMad <= 4f && r.ncc >= 0.90f) {
                boolean sameSize = Math.abs((double) a.w / b.w - 1) < 0.02;
                r.type = sameSize ? EDITED : EDITED | RESIZED;
                return r;
            }
        }
        if (histDist(a.hist, b.hist, HIST_MAX_CROP) > HIST_MAX_CROP) return r;
        if (a.std < 6 || b.std < 6) return r; // flat images would "match" anywhere
        Result best = null;
        Result c1 = cropSearch(a, b), c2 = cropSearch(b, a);
        if (c1 != null) { best = c1; best.firstIsWhole = true; }
        if (c2 != null && (best == null || c2.goodMad < best.goodMad)) best = c2;
        if (best != null) {
            if (best.cover < 0.97f && best.badFrac <= 0.30f && best.goodMad <= 6.5f && best.ncc >= 0.90f)
                best.type = best.badFrac > 0.015f ? CROPPED | EDITED : CROPPED;
            return best;
        }
        return r;
    }

    /**
     * Looks for {@code part} as a (possibly rescaled) sub-rectangle of {@code whole}.
     * Coarse-to-fine: exhaustive search over scale and position on a tiny pyramid level,
     * then refinement on finer levels and finally a sub-pixel adjustment.
     */
    static Result cropSearch(Fp whole, Fp part) {
        int aw = whole.tw, ah = whole.th;
        // width/height ratio of the searched window, measured in thumbnail pixels of "whole"
        double bar = ((double) part.w / part.h) * (((double) aw / whole.w) / ((double) ah / whole.h));
        double maxW = Math.min(aw, ah * bar);
        double minW = Math.max(MIN_CROP_SIDE * aw, MIN_CROP_SIDE * ah * bar);
        if (minW > maxW) return null;
        int[] a1 = toInt(whole.g);
        Pyr pyr = new Pyr(toInt(part.g), part.tw, part.th);
        int d = 4;
        while (d > 1 && (Math.min(aw, ah) / d < 5 || minW / d / Math.max(1, bar) < 3)) d /= 2;

        Top top = new Top(4);
        int lw = aw / d, lh = ah / d;
        int[] la = resize(a1, aw, ah, lw, lh);
        int wmax = Math.min(lw, (int) Math.floor(maxW / d + 0.5));
        for (int ww = Math.max(2, (int) Math.ceil(minW / d)); ww <= wmax; ww++) {
            int wh = Math.max(2, Math.min(lh, (int) Math.round(ww / bar)));
            search(la, lw, lh, pyr.to(ww, wh), ww, wh, 0, lw - ww, 0, lh - wh, top);
        }
        if (top.n == 0) return null;
        while (d > 1) {
            d /= 2;
            lw = aw / d; lh = ah / d;
            la = d == 1 ? a1 : resize(a1, aw, ah, lw, lh);
            wmax = Math.min(lw, (int) Math.floor(maxW / d + 0.5));
            Top nt = new Top(d == 1 ? 1 : 2);
            for (int k = 0; k < top.n; k++) {
                for (int ww = Math.max(2, top.w[k] * 2 - 1); ww <= Math.min(wmax, top.w[k] * 2 + 1); ww++) {
                    int wh = Math.max(2, Math.min(lh, (int) Math.round(ww / bar)));
                    search(la, lw, lh, pyr.to(ww, wh), ww, wh,
                            top.x[k] * 2 - 1, top.x[k] * 2 + 2, top.y[k] * 2 - 1, top.y[k] * 2 + 2, nt);
                }
            }
            if (nt.n == 0) return null;
            top = nt;
        }
        if (top.s[0] > 16f) return null; // clearly not the same content; skip the costly refinement
        int bw = top.w[0], bh = Math.max(2, Math.min(ah, (int) Math.round(bw / bar)));
        int[] bp = pyr.to(bw, bh);

        // cheap gate before the costly sub-pixel step: the integer placement must already correlate
        if (ncc(a1, aw, top.x[0], top.y[0], bp, bw, bh) < 0.7) return null;

        // sub-pixel refinement of position and scale by coordinate descent
        double[] q = {top.x[0], top.y[0], bw};
        int[] win = new int[bw * bh];
        double best = sampleMad(a1, aw, ah, q[0], q[1], q[2], q[2] / bar, bp, bw, bh, win);
        for (double step = 0.5; step >= 0.24; step /= 2) {
            boolean improved = true;
            for (int it = 0; it < 3 && improved; it++) {
                improved = false;
                for (int pi = 0; pi < 3; pi++) {
                    for (int sgn = -1; sgn <= 1; sgn += 2) {
                        double old = q[pi];
                        q[pi] = old + sgn * step;
                        double fh = q[2] / bar;
                        double m = (q[0] < -0.5 || q[1] < -0.5 || q[0] + q[2] > aw + 0.5 || q[1] + fh > ah + 0.5 || q[2] < 2)
                                ? Double.MAX_VALUE : sampleMad(a1, aw, ah, q[0], q[1], q[2], fh, bp, bw, bh, win);
                        if (m < best) { best = m; improved = true; } else q[pi] = old;
                    }
                }
            }
        }
        sampleMad(a1, aw, ah, q[0], q[1], q[2], q[2] / bar, bp, bw, bh, win);
        Result r = new Result();
        analyze(win, bp, bw, bh, 16f, 255, r);
        r.cover = (float) Math.min(1.0, (q[2] * q[2] / bar) / (aw * ah));
        return r;
    }

    /** Normalised cross-correlation between the window of a at (ox,oy) and p. */
    private static double ncc(int[] a, int aw, int ox, int oy, int[] p, int pw, int ph) {
        long sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int y = 0; y < ph; y++) {
            int ao = (oy + y) * aw + ox, po = y * pw;
            for (int x = 0; x < pw; x++) {
                int u = a[ao + x], v = p[po + x];
                sx += u; sy += v; sxx += u * u; syy += v * v; sxy += u * v;
            }
        }
        double n = pw * ph;
        double vx = sxx - sx * (double) sx / n, vy = syy - sy * (double) sy / n, cv = sxy - sx * (double) sy / n;
        if (vx < n * 4 || vy < n * 4) return 0;
        return cv / Math.sqrt(vx * vy);
    }

    /** Small sorted list of the best (lowest score) window placements. */
    private static final class Top {
        final float[] s; final int[] w, x, y; int n;
        Top(int k) { s = new float[k]; w = new int[k]; x = new int[k]; y = new int[k]; }
        float worst() { return n < s.length ? Float.MAX_VALUE : s[n - 1]; }
        void add(float sc, int ww, int ox, int oy) {
            if (sc >= worst()) return;
            int i = n < s.length ? n++ : n - 1;
            while (i > 0 && s[i - 1] > sc) { s[i] = s[i - 1]; w[i] = w[i - 1]; x[i] = x[i - 1]; y[i] = y[i - 1]; i--; }
            s[i] = sc; w[i] = ww; x[i] = ox; y[i] = oy;
        }
    }

    /** Slides p over a within the given offset ranges and records the best placement in top. */
    private static void search(int[] a, int aw, int ah, int[] p, int pw, int ph,
                               int x0, int x1, int y0, int y1, Top top) {
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(aw - pw, x1); y1 = Math.min(ah - ph, y1);
        int n = pw * ph;
        float bs = Float.MAX_VALUE; int bx = 0, by = 0;
        for (int oy = y0; oy <= y1; oy++) {
            for (int ox = x0; ox <= x1; ox++) {
                float lim = Math.min(bs, top.worst());
                long s = sad(a, aw, ox, oy, p, pw, ph, lim == Float.MAX_VALUE ? Long.MAX_VALUE : (long) (lim * n));
                if (s < 0) continue;
                float m = (float) s / n;
                if (m < bs) { bs = m; bx = ox; by = oy; }
            }
        }
        if (bs != Float.MAX_VALUE) top.add(bs, pw, bx, by);
    }

    /** Bilinearly samples the window (fx,fy,fw,fh) of a into out (bw x bh) and returns its MAD against p. */
    private static double sampleMad(int[] a, int aw, int ah, double fx, double fy, double fw, double fh,
                                    int[] p, int bw, int bh, int[] out) {
        long sd = 0;
        double kx = fw / bw, ky = fh / bh;
        for (int j = 0; j < bh; j++) {
            double sy = fy + (j + 0.5) * ky - 0.5;
            if (sy < 0) sy = 0; else if (sy > ah - 1) sy = ah - 1;
            int yi = Math.min(ah - 2, (int) sy);
            if (yi < 0) yi = 0;
            double ty = sy - yi;
            int r0 = yi * aw, r1 = Math.min(ah - 1, yi + 1) * aw;
            for (int i = 0; i < bw; i++) {
                double sx = fx + (i + 0.5) * kx - 0.5;
                if (sx < 0) sx = 0; else if (sx > aw - 1) sx = aw - 1;
                int xi = Math.min(aw - 2, (int) sx);
                if (xi < 0) xi = 0;
                double tx = sx - xi;
                int xj = Math.min(aw - 1, xi + 1);
                double v = (a[r0 + xi] * (1 - tx) + a[r0 + xj] * tx) * (1 - ty) + (a[r1 + xi] * (1 - tx) + a[r1 + xj] * tx) * ty;
                int iv = (int) (v + 0.5);
                out[j * bw + i] = iv;
                sd += Math.abs(iv - p[j * bw + i]);
            }
        }
        return (double) sd / (bw * bh);
    }

    /** Sum of absolute differences; returns -1 as soon as it exceeds {@code limit}. */
    private static long sad(int[] a, int aw, int ox, int oy, int[] p, int pw, int ph, long limit) {
        long s = 0;
        for (int y = 0; y < ph; y++) {
            int ao = (oy + y) * aw + ox, po = y * pw;
            for (int x = 0; x < pw; x++) s += Math.abs(a[ao + x] - p[po + x]);
            if (s > limit) return -1;
        }
        return s;
    }

    /**
     * Block-wise comparison of two aligned grayscale images. A block is "bad" (locally different)
     * when its mean difference exceeds {@code blockMad} or 2+ pixels differ by more than {@code pixT}.
     */
    static void analyze(int[] x, int[] y, int w, int h, float blockMad, int pixT, Result r) {
        int bxn = Math.max(1, w / CELL), byn = Math.max(1, h / CELL);
        int bad = 0, good = 0, n = 0;
        double goodSum = 0;
        long sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0, sd = 0;
        for (int by = 0; by < byn; by++) {
            int y0 = by * CELL, y1 = by == byn - 1 ? h : y0 + CELL;
            for (int bx = 0; bx < bxn; bx++) {
                int x0 = bx * CELL, x1 = bx == bxn - 1 ? w : x0 + CELL;
                int s = 0, big = 0;
                long bsx = 0, bsy = 0, bsxx = 0, bsyy = 0, bsxy = 0;
                for (int yy = y0; yy < y1; yy++)
                    for (int xx = x0; xx < x1; xx++) {
                        int a = x[yy * w + xx], b = y[yy * w + xx];
                        int d = Math.abs(a - b);
                        s += d;
                        if (d > pixT) big++;
                        bsx += a; bsy += b; bsxx += a * a; bsyy += b * b; bsxy += a * b;
                    }
                int cnt = (y1 - y0) * (x1 - x0);
                float m = (float) s / cnt;
                sd += s;
                if (m > blockMad || big >= 2) bad++;
                else {
                    good++; goodSum += m; n += cnt;
                    sx += bsx; sy += bsy; sxx += bsxx; syy += bsyy; sxy += bsxy;
                }
            }
        }
        r.mad = (float) sd / (w * h);
        r.badFrac = (float) bad / (bad + good);
        r.goodMad = good == 0 ? 255f : (float) (goodSum / good);
        if (n == 0) { r.ncc = 0; return; }
        // correlation over the matching blocks only, so a local edit does not hide the agreement elsewhere
        double vx = sxx - (double) sx * sx / n, vy = syy - (double) sy * sy / n, cv = sxy - (double) sx * sy / n;
        r.ncc = (vx < n * 4.0 && vy < n * 4.0) ? (r.goodMad < 3 ? 1f : 0f)   // both flat
                : (float) (cv / Math.sqrt(Math.max(1e-9, vx * vy)));
    }

    static int[] toInt(byte[] b) {
        int[] r = new int[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[i] & 255;
        return r;
    }

    /** Area-weighted (box) resampling; exact for the down-scaling used here. */
    static int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src;
        float[] tmp = new float[dw * sh];
        float[] wgt = new float[Math.max(sw / dw, sh / dh) + 3];
        double rx = (double) sw / dw;
        for (int x = 0; x < dw; x++) {
            double a = x * rx, b = (x + 1) * rx;
            int i0 = (int) a, i1 = Math.min(sw - 1, (int) Math.ceil(b) - 1), k = i1 - i0 + 1;
            for (int i = 0; i < k; i++) wgt[i] = (float) ((Math.min(b, i0 + i + 1) - Math.max(a, i0 + i)) / rx);
            for (int y = 0; y < sh; y++) {
                int o = y * sw + i0;
                float acc = 0;
                for (int i = 0; i < k; i++) acc += src[o + i] * wgt[i];
                tmp[y * dw + x] = acc;
            }
        }
        int[] out = new int[dw * dh];
        double ry = (double) sh / dh;
        for (int y = 0; y < dh; y++) {
            double a = y * ry, b = (y + 1) * ry;
            int i0 = (int) a, i1 = Math.min(sh - 1, (int) Math.ceil(b) - 1), k = i1 - i0 + 1;
            for (int i = 0; i < k; i++) wgt[i] = (float) ((Math.min(b, i0 + i + 1) - Math.max(a, i0 + i)) / ry);
            int oo = y * dw;
            for (int x = 0; x < dw; x++) {
                float acc = 0.5f;
                for (int i = 0; i < k; i++) acc += tmp[(i0 + i) * dw + x] * wgt[i];
                out[oo + x] = (int) acc;
            }
        }
        return out;
    }

    /** Half-resolution pyramid of the "part" image, so coarse levels resample from a small source. */
    private static final class Pyr {
        final int[][] img = new int[3][]; final int[] w = new int[3], h = new int[3];
        Pyr(int[] g, int gw, int gh) {
            img[0] = g; w[0] = gw; h[0] = gh;
            for (int i = 1; i < 3; i++) {
                w[i] = Math.max(1, w[i - 1] / 2); h[i] = Math.max(1, h[i - 1] / 2);
                img[i] = resize(img[i - 1], w[i - 1], h[i - 1], w[i], h[i]);
            }
        }
        int[] to(int dw, int dh) {
            int l = 2;
            while (l > 0 && (w[l] < dw || h[l] < dh)) l--;
            return resize(img[l], w[l], h[l], dw, dh);
        }
    }

    /** Compares every pair, in parallel. Returns all matching pairs with their match type. */
    public static List<Edge> findPairs(final Fp[] fps, int threads, final Progress progress, final AtomicBoolean cancel) {
        final List<Edge> edges = new ArrayList<>();
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        final int n = fps.length;
        Thread[] ts = new Thread[Math.max(1, threads)];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(new Runnable() {
                @Override public void run() {
                    int i;
                    while ((i = next.getAndIncrement()) < n) {
                        if (cancel != null && cancel.get()) return;
                        Fp a = fps[i];
                        if (a != null) {
                            for (int j = i + 1; j < n; j++) {
                                Fp b = fps[j];
                                if (b == null) continue;
                                Result r = compare(a, b);
                                if (r.type != NONE) {
                                    int whole = (r.type & CROPPED) == 0 ? -1 : r.firstIsWhole ? i : j;
                                    synchronized (edges) { edges.add(new Edge(i, j, r.type, whole)); }
                                }
                            }
                        }
                        int d = done.incrementAndGet();
                        if (progress != null && (d % 16 == 0 || d == n)) progress.onProgress(d, n);
                    }
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return edges;
    }
}
