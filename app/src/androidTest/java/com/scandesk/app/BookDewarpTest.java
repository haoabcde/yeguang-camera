package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class BookDewarpTest {
    private static final Runnable CHECK = () -> { };

    @Test public void identityMapPreservesAxesColorsAndStripBoundaries() throws Exception {
        ScanImages.requireVision();
        Bitmap source = Bitmap.createBitmap(257, 253, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setPixel(x, y, Color.rgb(x % 256, y, (x + y) % 256));
            }
        }
        try {
            float[] grid = identity(13, 9);
            BookDewarp.GridStats stats = BookDewarp.validateGrid(grid, 13, 9, 257, 253);
            assertEquals(0, stats.displacementPixels, .0001);
            assertEquals(0, stats.nonlinearDisplacement, .0001);
            Bitmap result = BookDewarp.remap(source, grid, 13, 9, CHECK);
            try {
                assertNotSame(source, result);
                for (int y = 0; y < 253; y++) {
                    for (int x = 0; x < 257; x++) {
                        assertEquals("pixel " + x + "," + y, source.getPixel(x, y), result.getPixel(x, y));
                    }
                }
            } finally { result.recycle(); }
        } finally { source.recycle(); }
    }

    @Test public void preprocessingUsesBgrAndAlignCorners() {
        Bitmap source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        source.setPixel(0, 0, Color.RED);
        source.setPixel(1, 0, Color.BLUE);
        source.setPixel(0, 1, Color.GREEN);
        source.setPixel(1, 1, Color.WHITE);
        try {
            float[] tensor = BookDewarp.prepareInput(source, CHECK);
            int width = BookDewarp.INPUT_WIDTH, height = BookDewarp.INPUT_HEIGHT, plane = width * height;
            assertEquals(0, tensor[0], 0);
            assertEquals(1, tensor[plane * 2], 0);
            assertEquals(1, tensor[width - 1], 0);
            assertEquals(0, tensor[plane * 2 + width - 1], 0);
            int x = 173, y = 389, i = y * width + x;
            float u = x / (width - 1f), v = y / (height - 1f);
            assertEquals(u, tensor[i], .000001); // B: right corners.
            assertEquals(v, tensor[plane + i], .000001); // G: bottom corners.
            assertEquals((1 - u) * (1 - v) + u * v, tensor[2 * plane + i], .000001);
        } finally { source.recycle(); }
    }

    @Test public void rejectsFoldedNonFiniteCollapsedAndFarOutsideGrids() throws Exception {
        float[] fold = identity(3, 3);
        fold[4] = -2;
        assertInvalid(fold);
        float[] nan = identity(3, 3);
        nan[0] = Float.NaN;
        assertInvalid(nan);
        assertInvalid(new float[18]);
        float[] outside = identity(3, 3);
        for (int i = 0; i < outside.length; i++) outside[i] += .4f;
        assertInvalid(outside);
    }

    @Test public void cancelledRemapKeepsSourceAndStopsBetweenStrips() throws Exception {
        ScanImages.requireVision();
        Bitmap source = Bitmap.createBitmap(240, 420, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        int[] checks = {0};
        try {
            BookDewarp.remap(source, identity(3, 3), 3, 3, () -> {
                if (++checks[0] == 3) throw new CancellationException("test");
            });
            fail("Cancellation must be propagated");
        } catch (CancellationException expected) {
            assertFalse(source.isRecycled());
            assertEquals(Color.RED, source.getPixel(0, 0));
        } finally { source.recycle(); }
    }

    @Test public void refusesGridThatCutsReliableTableRules() throws Exception {
        ScanImages.requireVision();
        Bitmap source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
        try {
            source.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(source);
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(2);
            for (int y = 90; y <= 510; y += 42) canvas.drawLine(35, y, 365, y, paint);
            for (int x = 35; x <= 365; x += 55) canvas.drawLine(x, 90, x, 510, paint);
            float[] grid = identity(BookDewarp.GRID_WIDTH, BookDewarp.GRID_HEIGHT);
            BookDewarp.checkContentPreservation(source, grid, CHECK);
            for (int i = 0; i < grid.length; i++) grid[i] *= .70f;
            try {
                BookDewarp.checkContentPreservation(source, grid, CHECK);
                fail("A valid non-folding grid must still be rejected when it crops table rules");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("表格边线")); }
            assertFalse(source.isRecycled());
        } finally { source.recycle(); }
    }

    @Test public void protectsShortHeaderFolioAndFooterInsideOuterThreePercent() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888);
        try (BookDewarp dewarp = new BookDewarp(context.getAssets())) {
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(Color.BLACK);
            ink.setTextSize(22);
            float[] full = identity(BookDewarp.GRID_WIDTH, BookDewarp.GRID_HEIGHT);
            String[] labels = {"SHORT HEADER", "249", "END"};
            for (int sample = 0; sample < labels.length; sample++) {
                source.eraseColor(Color.WHITE);
                Canvas canvas = new Canvas(source);
                canvas.drawText(labels[sample], sample == 1 ? 535 : 55, sample == 2 ? 892 : 25, ink);
                canvas.setBitmap(null);
                // Test each label separately: a long title cannot mask a missed folio.
                dewarp.checkDetectedTextPreservation(source, full, CHECK);
                float[] crop = full.clone();
                int plane = BookDewarp.GRID_WIDTH * BookDewarp.GRID_HEIGHT;
                for (int i = plane; i < crop.length; i++) {
                    // A non-folding map loses only the top or only the bottom 5%.
                    crop[i] = crop[i] * .95f + (sample == 2 ? -.05f : .05f);
                }
                BookDewarp.validateGrid(crop, BookDewarp.GRID_WIDTH, BookDewarp.GRID_HEIGHT, 640, 900);
                try {
                    dewarp.checkDetectedTextPreservation(source, crop, CHECK);
                    fail("Short edge text must remain protected: " + labels[sample]);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("页眉、页脚或文字"));
                }
            }
            assertFalse(source.isRecycled());
        } finally { source.recycle(); }
    }

    @Test public void modelSurvivesNativeCancellationAndRejectsContentLoss() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("flat-ledger.png")) {
            source = BitmapFactory.decodeStream(input);
        }
        try (BookDewarp dewarp = new BookDewarp(context.getAssets())) {
            AtomicBoolean guardReached = new AtomicBoolean();
            try {
                dewarp.flatten(source, () -> {
                    if (Thread.currentThread().getName().equals("BookDewarp-cancellation")) {
                        guardReached.set(true);
                        throw new CancellationException("native inference test");
                    }
                });
                fail("Native inference cancellation must propagate");
            } catch (CancellationException expected) { assertTrue(guardReached.get()); }
            try {
                BookDewarp.Result unsafe = dewarp.flatten(source, CHECK);
                unsafe.image.recycle();
                fail("The pretrained grid crops the flat ledger's left border and must be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("表格边线"));
                Log.i("BookDewarpProbe", "flat-control rejected: " + expected.getMessage());
            }
            Bitmap blank = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.WHITE);
            try { verifyAndSave(dewarp, blank, context, "blank-runtime-control"); }
            finally { blank.recycle(); }
            // Optional private, independently sourced real photographs. Push into
            // app external-files/acceptance, then pass -e bookInput 'a.png;b.png'.
            // No research photograph or user sample is bundled in the APK.
            String inputs = InstrumentationRegistry.getArguments().getString("bookInput", "");
            for (String name : inputs.split(";")) {
                if (name.isEmpty()) continue;
                if (!name.matches("[A-Za-z0-9._-]+")) throw new IOException("Invalid probe name");
                Bitmap field = BitmapFactory.decodeFile(new File(context.getExternalFilesDir("acceptance"), name).toString());
                if (field == null) throw new IOException("Missing private book probe: " + name);
                try { verifyAndSave(dewarp, field, context, name); } finally { field.recycle(); }
            }
        } finally { source.recycle(); }
    }

    private static void verifyAndSave(BookDewarp dewarp, Bitmap source, Context context, String name)
            throws Exception {
        BookDewarp.Result result = dewarp.flatten(source, CHECK);
        try {
            assertNotSame(source, result.image);
            assertFalse(source.isRecycled());
            assertEquals(source.getWidth(), result.image.getWidth());
            assertEquals(source.getHeight(), result.image.getHeight());
            assertEquals(0, result.foldedFraction, 0);
            assertTrue(result.outsideFraction <= .08f);
            JSONObject metrics = new JSONObject().put("name", name).put("elapsed_ms", result.elapsedMillis)
                    .put("nonlinear_displacement", result.nonlinearDisplacement)
                    .put("displacement_px", result.displacementPixels)
                    .put("outside_fraction", result.outsideFraction)
                    .put("folded_fraction", result.foldedFraction).put("changed", result.changed());
            File directory = new File(context.getExternalFilesDir("acceptance"), "book-dewarp");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create probe output");
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-flattened.png"))) {
                if (!result.image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG failed");
            }
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-metrics.json"))) {
                output.write(metrics.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i("BookDewarpProbe", metrics.toString());
        } finally { result.image.recycle(); }
    }

    private static float[] identity(int cols, int rows) {
        float[] grid = new float[2 * cols * rows];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            grid[y * cols + x] = x * 2f / (cols - 1) - 1;
            grid[cols * rows + y * cols + x] = y * 2f / (rows - 1) - 1;
        }
        return grid;
    }

    private static void assertInvalid(float[] grid) {
        try {
            BookDewarp.validateGrid(grid, 3, 3, 900, 1200);
            fail("Invalid grid must be rejected");
        } catch (IOException expected) { }
    }
}
