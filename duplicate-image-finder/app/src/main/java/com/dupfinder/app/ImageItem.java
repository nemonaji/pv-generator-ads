package com.dupfinder.app;

import android.net.Uri;

/** One image known to the media store. */
final class ImageItem {
    long id;
    Uri uri;
    String name;
    String folder;
    long size;
    long date;
}
