package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class LocalImagesTest {
    @Test public void fitsDimensionsAndCropsFixedPassportSizes() throws Exception {
        int background = 0xff5499d2;
        int subject = 0xff713a17;
        Bitmap source = solid(600, 700, background);
        rectangle(source, 230, 160, 370, 700, subject);
        rectangle(source, 250, 495, 350, 700, background);
        Bitmap resized = LocalImages.resize(source, 120, 400);
        Bitmap oneInch = LocalImages.passport(source, false);
        Bitmap twoInch = LocalImages.passport(source, true, Color.WHITE);
        try {
            assertNotSame(source, resized);
            assertEquals(120, resized.getWidth());
            assertEquals(140, resized.getHeight());
            assertEquals(295, oneInch.getWidth());
            assertEquals(413, oneInch.getHeight());
            assertEquals(background, oneInch.getPixel(24, 20));
            assertEquals(413, twoInch.getWidth());
            assertEquals(579, twoInch.getHeight());
            assertEquals(Color.WHITE, twoInch.getPixel(24, 20));
            assertEquals(subject, twoInch.getPixel(206, 288));
            assertEquals(background, twoInch.getPixel(206, 550));
            assertEquals(background, source.getPixel(0, 0));
            try {
                LocalImages.resize(source, LocalImages.RESIZE_EDGE_LIMIT + 1, 64);
                fail("Accepted an oversized image");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("4096"));
            }
        } finally {
            recycle(source, resized, oneInch, twoInch);
        }
    }

    @Test public void splitsBookWithoutDroppingColumnsAndOrdersTheIdSides() throws Exception {
        Bitmap spread = solid(301, 200, Color.RED);
        rectangle(spread, 150, 0, 301, 200, Color.BLUE);
        Bitmap left = LocalImages.splitBook(spread, false);
        Bitmap right = LocalImages.splitBook(spread, true);
        Bitmap sheet = LocalImages.idSheet(left, right);
        try {
            assertEquals(150, left.getWidth());
            assertEquals(151, right.getWidth());
            assertEquals(Color.RED, left.getPixel(149, 10));
            assertEquals(Color.BLUE, right.getPixel(0, 10));
            assertEquals(1240, sheet.getWidth());
            assertEquals(1754, sheet.getHeight());
            assertEquals(Color.RED, sheet.getPixel(620, 575));
            assertEquals(Color.BLUE, sheet.getPixel(620, 1120));
            assertEquals(Color.WHITE, sheet.getPixel(120, 1120));
        } finally {
            recycle(spread, left, right, sheet);
        }
    }

    @Test public void keepsTransparentMarksAndPlacesThemWithoutChangingOriginal() throws Exception {
        Bitmap original = solid(500, 600, Color.WHITE);
        Bitmap watermark = LocalImages.watermark("仅供验收使用");
        Bitmap signature = solid(32, 16, Color.BLACK);
        Bitmap marked = LocalImages.stamp(original, watermark, false);
        Bitmap signed = LocalImages.stamp(original, signature, true);
        try {
            assertEquals(Color.TRANSPARENT, watermark.getPixel(0, 0));
            assertTrue(hasNonWhite(marked, 100, 250, 410, 350));
            assertEquals(Color.WHITE, marked.getPixel(8, 8));
            assertEquals(Color.BLACK, signed.getPixel(428, 523));
            assertEquals(Color.WHITE, signed.getPixel(100, 300));
            assertEquals(Color.WHITE, original.getPixel(428, 523));
        } finally {
            recycle(original, watermark, signature, marked, signed);
        }
    }

    @Test public void stitchesInOrderAndReleasesAllSuppliedBitmaps() throws Exception {
        Bitmap[] loaded = new Bitmap[4];
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger lastProgress = new AtomicInteger();
        Bitmap combined = LocalImages.stitch(2, (index, edge) -> {
            Bitmap page = solid(160, index == 0 ? 80 : 160, index == 0 ? Color.RED : Color.BLUE);
            loaded[loads.getAndIncrement()] = page;
            return page;
        }, (done, total) -> {
            assertEquals(4, total);
            assertEquals(lastProgress.incrementAndGet(), done);
        }, () -> {});
        try {
            assertEquals(1440, combined.getWidth());
            assertEquals(2160, combined.getHeight());
            assertEquals(Color.RED, combined.getPixel(720, 360));
            assertEquals(Color.BLUE, combined.getPixel(720, 1000));
            assertEquals(4, lastProgress.get());
            for (Bitmap page : loaded) assertTrue(page.isRecycled());
        } finally {
            combined.recycle();
        }
    }

    @Test public void cancelsLongImageAfterReleasingItsCurrentPage() throws Exception {
        Bitmap[] loaded = new Bitmap[3];
        AtomicInteger loads = new AtomicInteger();
        try {
            LocalImages.stitch(2, (index, edge) -> {
                Bitmap page = solid(120, 120, Color.WHITE);
                loaded[loads.getAndIncrement()] = page;
                return page;
            }, (done, total) -> {
                if (done == 3) throw new CancellationException("已取消");
            }, () -> {});
            fail("Long image ignored cancellation");
        } catch (CancellationException expected) {
            assertEquals(3, loads.get());
            for (Bitmap page : loaded) assertTrue(page.isRecycled());
        }
    }

    @Test public void createsActualBlackContoursFromABitmap() throws Exception {
        Bitmap flat = solid(120, 120, Color.WHITE);
        rectangle(flat, 30, 30, 90, 90, Color.BLACK);
        Bitmap outlines = LocalImages.lineArt(flat);
        try {
            assertEquals(Color.WHITE, outlines.getPixel(60, 60));
            assertEquals(Color.WHITE, outlines.getPixel(10, 10));
            assertTrue(hasNonWhite(outlines, 26, 50, 34, 70));
        } finally {
            recycle(flat, outlines);
        }
    }

    @Test public void fillsOnlyTheSmallUserPaintedArea() throws Exception {
        int paper = 0xffd9d3c5;
        Bitmap source = solid(180, 180, paper);
        rectangle(source, 82, 82, 98, 98, Color.BLACK);
        rectangle(source, 120, 75, 148, 100, Color.BLUE);
        Bitmap mask = solid(90, 90, Color.TRANSPARENT);
        rectangle(mask, 39, 39, 51, 51, Color.WHITE);
        Bitmap fixed = LocalImages.repair(source, mask);
        try {
            int restored = fixed.getPixel(90, 90);
            assertTrue(Math.abs(Color.red(restored) - Color.red(paper)) <= 8);
            assertTrue(Math.abs(Color.green(restored) - Color.green(paper)) <= 8);
            assertTrue(Math.abs(Color.blue(restored) - Color.blue(paper)) <= 8);
            assertEquals(Color.BLUE, fixed.getPixel(134, 88));
            assertEquals(paper, fixed.getPixel(15, 20));
            assertEquals(Color.BLACK, source.getPixel(90, 90));
        } finally {
            recycle(source, mask, fixed);
        }
    }

    @Test public void decodesOrdinaryAndInvertedQrWithoutOpeningTheirPayload() throws Exception {
        String content = "扫描工作台\nhttps://example.invalid/id/00056";
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300,
                Collections.singletonMap(EncodeHintType.CHARACTER_SET, "UTF-8"));
        Bitmap normal = qrPicture(matrix, false);
        Bitmap inverted = qrPicture(matrix, true);
        Bitmap blank = solid(160, 160, Color.WHITE);
        try {
            assertEquals(content, LocalImages.qr(normal));
            assertEquals(content, LocalImages.qr(inverted));
            try {
                LocalImages.qr(blank);
                fail("Found a code on a blank page");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("未识别到二维码"));
            }
        } finally {
            recycle(normal, inverted, blank);
        }
    }

    private static Bitmap qrPicture(BitMatrix matrix, boolean inverted) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix.get(x, y) != inverted ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap solid(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static void rectangle(Bitmap bitmap, int left, int top, int right, int bottom, int color) {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(color);
        canvas.drawRect(left, top, right, bottom, paint);
        canvas.setBitmap(null);
    }

    private static boolean hasNonWhite(Bitmap image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (image.getPixel(x, y) != Color.WHITE) return true;
            }
        }
        return false;
    }

    private static void recycle(Bitmap... bitmaps) {
        for (Bitmap bitmap : bitmaps) bitmap.recycle();
    }
}
