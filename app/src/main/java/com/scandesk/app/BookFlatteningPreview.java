package com.scandesk.app;

import android.graphics.Bitmap;

import java.io.File;

/** Owns small comparison previews and a full-resolution temporary result until applied or dismissed. */
final class BookFlatteningPreview implements AutoCloseable {
    final Bitmap before;
    final Bitmap after;
    final File flattened;

    BookFlatteningPreview(Bitmap before, Bitmap after, File flattened) {
        this.before = before;
        this.after = after;
        this.flattened = flattened;
    }

    boolean changed() { return flattened != null; }

    static Bitmap thumbnail(Bitmap image) {
        float scale = Math.min(1, 1500f / Math.max(image.getWidth(), image.getHeight()));
        if (scale == 1) return image.copy(Bitmap.Config.ARGB_8888, false);
        return Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                Math.max(1, Math.round(image.getHeight() * scale)), true);
    }

    @Override public void close() {
        if (before != null && !before.isRecycled()) before.recycle();
        if (after != null && !after.isRecycled()) after.recycle();
        if (flattened != null) flattened.delete();
    }
}
