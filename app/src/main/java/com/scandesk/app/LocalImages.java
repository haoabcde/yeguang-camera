package com.scandesk.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

final class LocalImages {
    static final int RESIZE_EDGE_LIMIT = 4096;
    static final int WATERMARK_TEXT_LIMIT = 30;
    static final int KEEP_BACKGROUND = 0;
    private static final int LONG_WIDTH = 1440;
    private static final int LONG_HEIGHT_LIMIT = 16_000;
    private static final int LONG_PIXEL_LIMIT = 12_000_000;
    private static final int LONG_MIN_WIDTH = 240;
    private static final int QR_EDGE_LIMIT = 2400;
    private static final int IMAGE_FLAGS = Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG;

    private LocalImages() {}

    interface BitmapSource {
        /** Returns a new bitmap owned by the stitch operation. */
        Bitmap load(int index, int maxEdge) throws IOException;
    }

    static Bitmap resize(Bitmap source, int width, int height) throws IOException {
        if (width < 1 || width > RESIZE_EDGE_LIMIT || height < 1 || height > RESIZE_EDGE_LIMIT) {
            throw new IOException("宽度和高度须在 1–4096 像素之间");
        }
        double scale = Math.min(width / (double) source.getWidth(), height / (double) source.getHeight());
        int outputWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int outputHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        return copy(source, null, outputWidth, outputHeight, false);
    }

    static Bitmap passport(Bitmap source, boolean twoInch) throws IOException {
        return passport(source, twoInch, KEEP_BACKGROUND);
    }

    static Bitmap passport(Bitmap source, boolean twoInch, int backgroundColor) throws IOException {
        int width = twoInch ? 413 : 295;
        int height = twoInch ? 579 : 413;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int cropWidth = Math.max(1, Math.min(sourceWidth, (int) Math.round(sourceHeight * (double) width / height)));
        int cropHeight = Math.max(1, Math.min(sourceHeight, (int) Math.round(sourceWidth * (double) height / width)));
        int left = (sourceWidth - cropWidth) / 2;
        int top = (sourceHeight - cropHeight) / 2;
        Bitmap portrait = copy(source, new Rect(left, top, left + cropWidth, top + cropHeight),
                width, height, true);
        boolean complete = false;
        try {
            if (backgroundColor != KEEP_BACKGROUND) recolorBackdrop(portrait, backgroundColor);
            complete = true;
            return portrait;
        } finally {
            if (!complete) portrait.recycle();
        }
    }

    private static void recolorBackdrop(Bitmap portrait, int replacement) throws IOException {
        int width = portrait.getWidth();
        int height = portrait.getHeight();
        int[] pixels = new int[width * height];
        portrait.getPixels(pixels, 0, width, 0, 0, width, height);
        int top = height / 40;
        int left = width / 40;
        int right = width - 1 - left;
        int[] samples = {
                pixels[top * width + left], pixels[top * width + width / 2], pixels[top * width + right],
                pixels[height / 8 * width + left], pixels[height / 8 * width + right],
                pixels[height / 4 * width + left], pixels[height / 4 * width + right]
        };
        int backdrop = samples[0];
        int matches = 0;
        for (int sample : samples) {
            int similar = 0;
            for (int other : samples) if (nearColor(sample, other)) similar++;
            if (similar > matches) {
                backdrop = sample;
                matches = similar;
            }
        }
        if (matches < 5) throw new IOException("请选用背景均匀的纯色底证件照");

        BackgroundFill fill = new BackgroundFill(pixels, backdrop);
        // Seed from the backdrop beside and above the head, away from clothing along the lower edge.
        for (int x = 0; x < width; x++) fill.add(x);
        for (int y = 1; y < height / 3; y++) {
            fill.add(y * width);
            fill.add(y * width + width - 1);
        }
        for (int head = 0; head < fill.size; head++) {
            int index = fill.queue[head];
            int x = index % width;
            int y = index / width;
            if (x > 0) fill.add(index - 1);
            if (x + 1 < width) fill.add(index + 1);
            if (y > 0) fill.add(index - width);
            if (y + 1 < height) fill.add(index + width);
        }
        if (fill.size < pixels.length / 10 || fill.size > pixels.length * .94f) {
            throw new IOException("无法分辨人物与背景，请选用纯色底证件照");
        }
        int applied = replacement | 0xff000000;
        for (int i = 0; i < fill.size; i++) pixels[fill.queue[i]] = applied;
        portrait.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static boolean nearColor(int a, int b) {
        int red = Color.red(a) - Color.red(b);
        int green = Color.green(a) - Color.green(b);
        int blue = Color.blue(a) - Color.blue(b);
        return red * red + green * green + blue * blue <= 36 * 36;
    }

    private static final class BackgroundFill {
        final int[] pixels;
        final int[] queue;
        final boolean[] accepted;
        final int backdrop;
        int size;

        BackgroundFill(int[] pixels, int backdrop) {
            this.pixels = pixels;
            this.backdrop = backdrop;
            queue = new int[pixels.length];
            accepted = new boolean[pixels.length];
        }

        void add(int index) {
            if (accepted[index] || !nearColor(pixels[index], backdrop)) return;
            accepted[index] = true;
            queue[size++] = index;
        }
    }

    static Bitmap watermark(String rawText) throws IOException {
        String text = rawText.trim();
        if (text.isEmpty()) throw new IOException("请输入水印文字");
        if (text.codePointCount(0, text.length()) > WATERMARK_TEXT_LIMIT
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("请输入不超过 30 字的单行水印");
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(72);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        if (ink.isEmpty()) throw new IOException("请输入可见的水印文字");
        int padding = 12;
        Bitmap result = Bitmap.createBitmap(ink.width() + 2 * padding, ink.height() + 2 * padding,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        try {
            // A soft light outline keeps the translucent lettering readable over dark photos.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(0x88ffffff);
            canvas.drawText(text, padding - ink.left, padding - ink.top, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xa0334358);
            canvas.drawText(text, padding - ink.left, padding - ink.top, paint);
        } finally {
            canvas.setBitmap(null);
        }
        return result;
    }

    static Bitmap stamp(Bitmap source, Bitmap mark, boolean signature) {
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        int width = result.getWidth();
        int height = result.getHeight();
        Paint paint = new Paint(IMAGE_FLAGS);
        try {
            canvas.drawBitmap(source, null, new RectF(0, 0, width, height), paint);
            float scale = signature
                    ? Math.min(width * .32f / mark.getWidth(), height * .16f / mark.getHeight())
                    : Math.min(width * .66f / mark.getWidth(), height * .18f / mark.getHeight());
            float markWidth = mark.getWidth() * scale;
            float markHeight = mark.getHeight() * scale;
            float left = signature ? width * .95f - markWidth : (width - markWidth) / 2;
            float top = signature ? height * .95f - markHeight : (height - markHeight) / 2;
            canvas.drawBitmap(mark, null, new RectF(left, top, left + markWidth, top + markHeight), paint);
        } finally {
            canvas.setBitmap(null);
        }
        return result;
    }

    static Bitmap stitch(int count, BitmapSource source, ScanExport.Progress progress, Runnable check)
            throws IOException {
        if (count < 1) throw new IOException("请至少选择一页");
        double[] ratios = new double[count];
        double sum = 0;
        for (int i = 0; i < count; i++) {
            check.run();
            Bitmap page = source.load(i, 320);
            try {
                ratios[i] = page.getHeight() / (double) page.getWidth();
                sum += ratios[i];
            } finally {
                page.recycle();
            }
            progress.page(i + 1, 2 * count);
        }
        // Reserve rounding room for each row before choosing a common width.
        int width = Math.min(LONG_WIDTH, (int) ((LONG_HEIGHT_LIMIT - count) / sum));
        width = Math.min(width, (int) Math.sqrt((LONG_PIXEL_LIMIT - (long) width * count) / sum));
        if (width < LONG_MIN_WIDTH) throw new IOException("长图超出清晰输出范围，请减少所选页面");
        int[] heights = new int[count];
        int height = 0;
        for (int i = 0; i < count; i++) {
            heights[i] = Math.max(1, (int) Math.round(width * ratios[i]));
            height += heights[i];
        }
        check.run();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(IMAGE_FLAGS);
        boolean complete = false;
        try {
            canvas.drawColor(Color.WHITE);
            int offset = 0;
            for (int i = 0; i < count; i++) {
                check.run();
                int detail = Math.min(ScanImages.EXPORT_EDGE, Math.max(width, heights[i]));
                Bitmap page = source.load(i, detail);
                try {
                    drawFit(canvas, page, new RectF(0, offset, width, offset + heights[i]), paint);
                } finally {
                    page.recycle();
                }
                offset += heights[i];
                progress.page(count + i + 1, 2 * count);
            }
            check.run();
            complete = true;
            return result;
        } finally {
            canvas.setBitmap(null);
            if (!complete) result.recycle();
        }
    }

    static Bitmap lineArt(Bitmap source) throws IOException {
        ScanImages.requireVision();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat edges = new Mat();
        Bitmap result = null;
        boolean complete = false;
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
            Imgproc.Canny(gray, edges, 32, 85);
            Core.bitwise_not(edges, edges);
            result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(edges, result);
            complete = true;
            return result;
        } finally {
            ScanImages.release(rgba, gray, edges);
            if (!complete && result != null) result.recycle();
        }
    }

    static Bitmap repair(Bitmap source, Bitmap mask) throws IOException {
        ScanImages.requireVision();
        int width = source.getWidth();
        int height = source.getHeight();
        Mat maskRgba = new Mat();
        Mat alpha = new Mat();
        Mat selected = new Mat();
        try {
            Utils.bitmapToMat(mask, maskRgba);
            Core.extractChannel(maskRgba, alpha, 3);
            Imgproc.resize(alpha, selected, new Size(width, height), 0, 0, Imgproc.INTER_LINEAR);
            Imgproc.threshold(selected, selected, 96, 255, Imgproc.THRESH_BINARY);
            int pixels = Core.countNonZero(selected);
            if (pixels == 0) throw new IOException("请涂抹要修补的位置");
            if (pixels > width * (long) height * .15) {
                throw new IOException("涂抹范围较大，请分次修补小面积区域");
            }
            org.opencv.core.Rect bounds = Imgproc.boundingRect(selected);
            double radius = Math.max(3, Math.min(8, Math.max(width, height) / 360.0));
            int padding = (int) Math.ceil(radius * 4);
            int left = Math.max(0, bounds.x - padding);
            int top = Math.max(0, bounds.y - padding);
            int right = Math.min(width, bounds.x + bounds.width + padding);
            int bottom = Math.min(height, bounds.y + bounds.height + padding);
            if ((right - left) * (long) (bottom - top) > 4_000_000) {
                throw new IOException("涂抹位置相距较远，请分次修补");
            }
            org.opencv.core.Rect patch = new org.opencv.core.Rect(left, top, right - left, bottom - top);
            return repairPatch(source, selected, patch, radius);
        } finally {
            ScanImages.release(maskRgba, alpha, selected);
        }
    }

    private static Bitmap repairPatch(Bitmap source, Mat selected, org.opencv.core.Rect bounds, double radius)
            throws IOException {
        Bitmap crop = null;
        Bitmap patch = null;
        Bitmap result = null;
        Mat mask = selected.submat(bounds);
        Mat original = new Mat();
        Mat rgb = new Mat();
        Mat patched = new Mat();
        Mat rgba = new Mat();
        boolean complete = false;
        try {
            crop = Bitmap.createBitmap(source, bounds.x, bounds.y, bounds.width, bounds.height);
            patch = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888);
            Utils.bitmapToMat(crop, original);
            Imgproc.cvtColor(original, rgb, Imgproc.COLOR_RGBA2RGB);
            Photo.inpaint(rgb, mask, patched, radius, Photo.INPAINT_TELEA);
            Imgproc.cvtColor(patched, rgba, Imgproc.COLOR_RGB2RGBA);
            rgba.copyTo(original, mask);
            Utils.matToBitmap(original, patch);
            result = source.copy(Bitmap.Config.ARGB_8888, true);
            if (result == null) throw new IOException("无法创建修补后的图片");
            Canvas canvas = new Canvas(result);
            try {
                Rect destination = new Rect(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height);
                Paint replace = new Paint();
                replace.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
                canvas.drawBitmap(patch, null, destination, replace);
            } finally {
                canvas.setBitmap(null);
            }
            complete = true;
            return result;
        } finally {
            ScanImages.release(mask, original, rgb, patched, rgba);
            if (!complete && result != null) result.recycle();
            if (patch != null) patch.recycle();
            if (crop != null && crop != source) crop.recycle();
        }
    }

    static String qr(Bitmap source) throws IOException {
        Bitmap sample = source;
        try {
            if (Math.max(source.getWidth(), source.getHeight()) > QR_EDGE_LIMIT) {
                sample = resize(source, QR_EDGE_LIMIT, QR_EDGE_LIMIT);
            }
            int width = sample.getWidth();
            int height = sample.getHeight();
            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            BinaryBitmap code = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
            return new MultiFormatReader().decode(code, hints).getText();
        } catch (ReaderException absent) {
            throw new IOException("未识别到二维码，请靠近拍摄或选用清晰图片", absent);
        } finally {
            if (sample != source) sample.recycle();
        }
    }

    static Bitmap splitBook(Bitmap source, boolean rightPage) throws IOException {
        int width = source.getWidth();
        if (width < 2) throw new IOException("图片太窄，无法拆分为左右页");
        int middle = width / 2;
        int left = rightPage ? middle : 0;
        int right = rightPage ? width : middle;
        int height = source.getHeight();
        return copy(source, new Rect(left, 0, right, height), right - left, height, false);
    }

    static Bitmap idSheet(Bitmap front, Bitmap back) {
        Bitmap sheet = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        Paint paint = new Paint(IMAGE_FLAGS);
        try {
            canvas.drawColor(Color.WHITE);
            drawFit(canvas, front, cardSlot(back == null ? 877 : 575), paint);
            if (back != null) drawFit(canvas, back, cardSlot(1120), paint);
        } finally {
            canvas.setBitmap(null);
        }
        return sheet;
    }

    private static RectF cardSlot(int centerY) {
        return new RectF(367, centerY - 159, 873, centerY + 159);
    }

    private static void drawFit(Canvas canvas, Bitmap source, RectF box, Paint paint) {
        float scale = Math.min(box.width() / source.getWidth(), box.height() / source.getHeight());
        float halfWidth = source.getWidth() * scale / 2;
        float halfHeight = source.getHeight() * scale / 2;
        canvas.drawBitmap(source, null, new RectF(box.centerX() - halfWidth, box.centerY() - halfHeight,
                box.centerX() + halfWidth, box.centerY() + halfHeight), paint);
    }

    private static Bitmap copy(Bitmap source, Rect region, int width, int height, boolean whiteBackground) {
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        try {
            if (whiteBackground) canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, region, new Rect(0, 0, width, height), new Paint(IMAGE_FLAGS));
        } finally {
            canvas.setBitmap(null);
        }
        return result;
    }
}
