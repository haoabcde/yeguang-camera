package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/** Optional, offline probe for the publication's externally supplied screen photograph. */
@RunWith(AndroidJUnit4.class)
public final class UhdmScreenProbeTest {
    private static final int LEFT = 510, TOP = 1760, RIGHT = 1165, BOTTOM = 1920;
    private static final String SHA256 = "e8e6aecd2801a54cf83d53b07dd891aa42cd9c2a35b1da14493a2c0c8000d000";

    @Test public void measuresBothExportedLooksOnUhdm0241() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = context.getExternalFilesDir("acceptance");
        assertNotNull(directory);
        File source = new File(directory, "uhdm-0241.jpg");
        assumeTrue("UHDM 0241 field sample not installed", source.isFile());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[32 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        assertEquals("unexpected field sample", SHA256, hash.toString());

        ScanStore store = new ScanStore(context);
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.ORIGINAL);
        ScanDocument document = store.newDocument().withPages(List.of(page));
        store.save(document);
        try {
            store.importImage(context.getContentResolver(), Uri.fromFile(source), document, page);
            File plainPng = export(store, document, page, directory, "original");
            File cleanPng = export(store, document, page.filter(ScanDocument.Filter.SCREEN), directory, "screen");
            Bitmap plain = BitmapFactory.decodeFile(plainPng.toString());
            Bitmap clean = BitmapFactory.decodeFile(cleanPng.toString());
            assertNotNull(plain);
            assertNotNull(clean);
            try {
                assertEquals(2800, plain.getWidth());
                assertEquals(2100, plain.getHeight());
                assertEquals(plain.getWidth(), clean.getWidth());
                assertEquals(plain.getHeight(), clean.getHeight());
                saveCrop(directory, plain, "original");
                saveCrop(directory, clean, "screen");
                Statistics original = new Statistics(), filtered = new Statistics();
                int maxLuma = 0;
                int[] before = new int[plain.getWidth()], after = new int[clean.getWidth()];
                for (int y = 0; y < plain.getHeight(); y++) {
                    plain.getPixels(before, 0, before.length, 0, y, before.length, 1);
                    clean.getPixels(after, 0, after.length, 0, y, after.length, 1);
                    for (int x = 0; x < before.length; x++) {
                        maxLuma = Math.max(maxLuma, Math.abs(luma(before[x]) - luma(after[x])));
                        if (x >= LEFT && x < RIGHT && y >= TOP && y < BOTTOM) {
                            original.add(before[x]);
                            filtered.add(after[x]);
                        }
                    }
                }
                JSONObject metrics = new JSONObject()
                        .put("source", "https://xinyu-andy.github.io/uhdm-page/images/uhdm/moire/0241_moire.jpg")
                        .put("source_sha256", SHA256)
                        .put("orientation_quarter_turns", 0)
                        .put("crop", "FULL")
                        .put("export_size", new JSONArray().put(plain.getWidth()).put(plain.getHeight()))
                        .put("roi_xyxy_exclusive", new JSONArray().put(LEFT).put(TOP).put(RIGHT).put(BOTTOM))
                        .put("roi_pixel_count", original.count)
                        .put("original", original.json())
                        .put("screen", filtered.json())
                        .put("max_abs_luma_delta_full_frame", maxLuma)
                        .put("luma", "(77*R+150*G+29*B+128)>>8");
                try (FileOutputStream out = new FileOutputStream(new File(directory, "uhdm-0241-metrics.json"))) {
                    out.write(metrics.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Log.i("ScreenAcceptance", metrics.toString());
                assertTrue("R-G residual color variation", filtered.stdRedGreen() < .85 * original.stdRedGreen());
                assertTrue("B-G residual color variation", filtered.stdBlueGreen() < .85 * original.stdBlueGreen());
                assertTrue("luminance structure", maxLuma <= 2);
            } finally {
                clean.recycle();
                plain.recycle();
            }
        } finally {
            store.delete(document);
        }
    }

    private static int luma(int color) {
        return (77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color) + 128) >> 8;
    }

    private static File export(ScanStore store, ScanDocument doc, ScanDocument.Page page, File dir, String name)
            throws Exception {
        File png = new File(dir, "uhdm-0241-" + name + ".png");
        try (FileOutputStream out = new FileOutputStream(png)) {
            ScanExport.image(store, doc, page, true, out);
        }
        return png;
    }

    private static void saveCrop(File directory, Bitmap full, String name) throws Exception {
        Bitmap crop = Bitmap.createBitmap(full, LEFT, TOP, RIGHT - LEFT, BOTTOM - TOP);
        try (FileOutputStream out = new FileOutputStream(new File(directory, "uhdm-0241-" + name + "-roi.png"))) {
            assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            crop.recycle();
        }
    }

    private static final class Statistics {
        long count, sumRG, sumBG, squareRG, squareBG;
        void add(int c) {
            int rg = Color.red(c) - Color.green(c), bg = Color.blue(c) - Color.green(c);
            count++;
            sumRG += rg;
            sumBG += bg;
            squareRG += rg * rg;
            squareBG += bg * bg;
        }
        double stdRedGreen() { return std(sumRG, squareRG); }
        double stdBlueGreen() { return std(sumBG, squareBG); }
        private double std(long sum, long squares) {
            double mean = (double) sum / count;
            return Math.sqrt(Math.max(0, (double) squares / count - mean * mean));
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("population_std_R_minus_G", stdRedGreen())
                    .put("population_std_B_minus_G", stdBlueGreen());
        }
    }
}
