package com.dupfinder.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import java.io.InputStream;

/** Decodes images at reduced size so that large photos stay cheap to analyse. */
final class Decoder {
    private Decoder() {}

    /**
     * Decodes the image subsampled by a power of two, keeping its long side at or above
     * {@code minLongSide} when the source allows. {@code outDims} receives the full size.
     */
    static Bitmap decode(ContentResolver cr, Uri uri, final int minLongSide, final int[] outDims) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                if (outDims != null) { outDims[0] = w; outDims[1] = h; }
                decoder.setTargetSampleSize(sampleFor(w, h, minLongSide));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setOnPartialImageListener(e -> true);
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        if (outDims != null) { outDims[0] = o.outWidth; outDims[1] = o.outHeight; }
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sampleFor(o.outWidth, o.outHeight, minLongSide);
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = cr.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, d);
        }
    }

    private static int sampleFor(int w, int h, int minLongSide) {
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= minLongSide) sample *= 2;
        return sample;
    }
}
