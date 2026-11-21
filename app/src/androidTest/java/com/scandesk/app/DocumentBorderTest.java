package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

@RunWith(AndroidJUnit4.class)
public final class DocumentBorderTest {
    @Test public void aGridOnContinuousPaperKeepsTheHeadingNotesAndStamp() throws Exception {
        Bitmap original = fixture("flat-ledger.png");
        try {
            for (int height : new int[]{720, 1000, original.getHeight()}) {
                int width = Math.round(original.getWidth() * height / (float) original.getHeight());
                Bitmap probe = Bitmap.createScaledBitmap(original, width, height, true);
                try {
                    CropQuad crop = ScanImages.detect(probe);
                    // A detector may abstain or select the whole image. The outside margin is
                    // already the paper on all four sides of this input.
                    if (crop != null) {
                        for (int corner = 0; corner < 4; corner++) {
                            assertEquals("page width at " + height, CropQuad.FULL.x(corner), crop.x(corner), .03f);
                            assertEquals("page height at " + height, CropQuad.FULL.y(corner), crop.y(corner), .03f);
                        }
                    }
                } finally {
                    if (probe != original) probe.recycle();
                }
            }
        } finally {
            original.recycle();
        }
    }

    @Test public void picksTheOuterSlantedSheetWithAnotherGridInside() throws Exception {
        Bitmap photo = fixture("printed-table.jpg");
        try {
            CropQuad paper = ScanImages.detect(photo);
            assertNotNull("physical paper against the table", paper);
            float[] expected = {.084f,.039f, .967f,.066f, .919f,.961f, .033f,.935f};
            for (int corner = 0; corner < 4; corner++) {
                assertEquals(expected[corner * 2], paper.x(corner), .035f);
                assertEquals(expected[corner * 2 + 1], paper.y(corner), .035f);
            }
            assertTrue(paper.y(1) > paper.y(0));
        } finally {
            photo.recycle();
        }
    }

    @Test public void tintedPanelDoesNotCutOffTextOnAUniformOuterPage() throws Exception {
        Bitmap page = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(page);
            canvas.drawColor(Color.WHITE);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(0xffadc8d4);
            canvas.drawRect(60, 200, 580, 705, ink);
            ink.setColor(0xff292d2f);
            ink.setTextSize(32);
            canvas.drawText("Quarterly report", 72, 108, ink);
            canvas.drawText("Prepared for review", 72, 782, ink);
            canvas.setBitmap(null);
            CropQuad found = ScanImages.detect(page);
            if (found != null) {
                for (int corner = 0; corner < 4; corner++) {
                    assertEquals(CropQuad.FULL.y(corner), found.y(corner), .03f);
                }
            }
        } finally {
            page.recycle();
        }
    }

    @Test public void curvedOuterPaperKeepsItsHeaderAndDoesNotFallBackToAnInnerGrid() throws Exception {
        for (int topOffset : new int[]{0, -90}) {
            Bitmap photo = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(photo);
                canvas.drawColor(0xff625245);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xfff3e7cf);
                Path paper = new Path();
                paper.moveTo(80, 150 + topOffset);
                paper.quadTo(400, 30 + topOffset, 720, 150 + topOffset);
                paper.lineTo(750, 920 + topOffset);
                paper.lineTo(50, 900 + topOffset);
                paper.close();
                canvas.drawPath(paper, paint);
                paint.setColor(0xff272727);
                paint.setTextSize(26);
                canvas.drawText("CURVED PAPER", 230, 130 + topOffset, paint);
                canvas.drawText("27", 585, 145 + topOffset, paint);
                paint.setStrokeWidth(3);
                for (int x = 135; x <= 665; x += 106) {
                    canvas.drawLine(x, 250 + topOffset, x, 820 + topOffset, paint);
                }
                for (int y = 250; y <= 820; y += 57) {
                    canvas.drawLine(135, y + topOffset, 665, y + topOffset, paint);
                }
                canvas.setBitmap(null);
                for (int height : new int[]{1000, 720}) {
                    Bitmap probe = Bitmap.createScaledBitmap(photo, height * 4 / 5, height, true);
                    try {
                        CropQuad found = ScanImages.detect(probe);
                        // Abstention keeps the photograph. A proposal must enclose
                        // the bowed top and short header, not a straight chord or
                        // the stronger inner table outline.
                        if (found != null) {
                            assertInside(found, 400 / 800f, (90 + topOffset) / 1000f, height);
                            assertInside(found, 230 / 800f, (110 + topOffset) / 1000f, height);
                            assertInside(found, 615 / 800f, (145 + topOffset) / 1000f, height);
                            assertInside(found, 50 / 800f, (900 + topOffset) / 1000f, height);
                            assertInside(found, 750 / 800f, (920 + topOffset) / 1000f, height);
                        }
                    } finally { if (probe != photo) probe.recycle(); }
                }
            } finally { photo.recycle(); }
        }
    }

    private static void assertInside(CropQuad crop, float x, float y, int height) {
        for (int edge = 0; edge < 4; edge++) {
            int next = (edge + 1) % 4;
            double dx = crop.x(next) - crop.x(edge), dy = crop.y(next) - crop.y(edge);
            double cross = dx * (y - crop.y(edge)) - dy * (x - crop.x(edge));
            assertTrue("automatic crop cuts curved paper at " + x + "," + y + " height=" + height,
                    cross >= -.006 * Math.hypot(dx, dy));
        }
    }

    private static Bitmap fixture(String name) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            Bitmap image = BitmapFactory.decodeStream(input);
            assertNotNull(name, image);
            return image;
        }
    }
}
