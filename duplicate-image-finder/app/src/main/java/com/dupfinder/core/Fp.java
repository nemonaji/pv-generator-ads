package com.dupfinder.core;

/** Compact per-image fingerprint: a small grayscale thumbnail plus a soft colour histogram. */
public final class Fp {
    /** Long side of the stored grayscale thumbnail. */
    public static final int SIDE = 64;
    public static final int BINS = 64;

    public int w, h;        // original pixel size
    public long size;       // file size in bytes
    public long hash;       // content hash of the file bytes, 0 = not computed
    public int tw, th;      // thumbnail size
    public byte[] g;        // grayscale thumbnail, tw*th
    public float[] hist;    // 4x4x4 RGB histogram, sums to 1
    public float std;       // grayscale standard deviation (texture amount)

    /**
     * @param px    decoded ARGB pixels (may already be subsampled)
     * @param pw    width of px
     * @param ph    height of px
     * @param origW true width of the image file
     * @param origH true height of the image file
     */
    public static Fp fromArgb(int[] px, int pw, int ph, int origW, int origH) {
        Fp f = new Fp();
        f.w = origW;
        f.h = origH;
        double s = (double) SIDE / Math.max(origW, origH);
        int tw = (int) Math.round(origW * s), th = (int) Math.round(origH * s);
        tw = Math.max(4, Math.min(SIDE, Math.min(tw, pw)));
        th = Math.max(4, Math.min(SIDE, Math.min(th, ph)));
        tw = Math.min(tw, pw);
        th = Math.min(th, ph);
        f.tw = tw;
        f.th = th;
        f.g = new byte[tw * th];
        f.hist = new float[BINS];
        double sum = 0, sum2 = 0;
        for (int y = 0; y < th; y++) {
            int y0 = (int) ((long) y * ph / th), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * ph / th));
            for (int x = 0; x < tw; x++) {
                int x0 = (int) ((long) x * pw / tw), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * pw / tw));
                long r = 0, g = 0, b = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int o = yy * pw;
                    for (int xx = x0; xx < x1; xx++) {
                        int c = px[o + xx];
                        int a = c >>> 24;
                        int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                        if (a != 255) { // composite on white so transparent areas are stable
                            cr = (cr * a + 255 * (255 - a)) / 255;
                            cg = (cg * a + 255 * (255 - a)) / 255;
                            cb = (cb * a + 255 * (255 - a)) / 255;
                        }
                        r += cr; g += cg; b += cb;
                    }
                }
                int n = (y1 - y0) * (x1 - x0);
                float fr = (float) r / n, fg = (float) g / n, fb = (float) b / n;
                int gray = Math.round(fr * 0.299f + fg * 0.587f + fb * 0.114f);
                f.g[y * tw + x] = (byte) gray;
                sum += gray;
                sum2 += gray * gray;
                addSoft(f.hist, fr, fg, fb);
            }
        }
        int n = tw * th;
        for (int i = 0; i < BINS; i++) f.hist[i] /= n;
        double mean = sum / n;
        f.std = (float) Math.sqrt(Math.max(0, sum2 / n - mean * mean));
        return f;
    }

    /** Trilinear soft binning so that colours near a bin edge do not flip between bins. */
    private static void addSoft(float[] hist, float r, float g, float b) {
        float pr = r / 255f * 3f, pg = g / 255f * 3f, pb = b / 255f * 3f;
        int r0 = Math.min(2, (int) pr), g0 = Math.min(2, (int) pg), b0 = Math.min(2, (int) pb);
        float fr = pr - r0, fg = pg - g0, fb = pb - b0;
        for (int i = 0; i < 2; i++) {
            float wr = i == 0 ? 1 - fr : fr;
            for (int j = 0; j < 2; j++) {
                float wg = j == 0 ? 1 - fg : fg;
                for (int k = 0; k < 2; k++) {
                    float wb = k == 0 ? 1 - fb : fb;
                    hist[(r0 + i) * 16 + (g0 + j) * 4 + (b0 + k)] += wr * wg * wb;
                }
            }
        }
    }
}
