package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

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

    private static Bitmap fixture(String name) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            Bitmap image = BitmapFactory.decodeStream(input);
            assertNotNull(name, image);
            return image;
        }
    }
}
