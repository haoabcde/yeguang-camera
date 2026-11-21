package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class CaptureQualityTest {
    @Test public void flagsSevereDefocusOnPrintedTypeButAcceptsSharpSource() throws Exception {
        Bitmap source;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("printed-table.jpg")) {
            source = BitmapFactory.decodeStream(input);
        }
        Bitmap sample = Bitmap.createScaledBitmap(source, source.getWidth() * 960 / source.getHeight(), 960, true);
        Bitmap blurred = Bitmap.createBitmap(sample.getWidth(), 960, Bitmap.Config.ARGB_8888);
        ScanImages.requireVision();
        Mat pixels = new Mat();
        try {
            assertEquals(CaptureQuality.Issue.NONE, CaptureQuality.inspect(sample));
            Utils.bitmapToMat(sample, pixels);
            Imgproc.GaussianBlur(pixels, pixels, new Size(0, 0), 4);
            Utils.matToBitmap(pixels, blurred);
            assertEquals(CaptureQuality.Issue.BLUR, CaptureQuality.inspect(blurred));
        } finally {
            pixels.release();
            blurred.recycle();
            sample.recycle();
            source.recycle();
        }
    }

    @Test public void flagsDarkFrameAndAcceptsBlankOrShadedPaper() {
        Bitmap sample = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
        try {
            sample.eraseColor(Color.rgb(28,32,28));
            assertEquals(CaptureQuality.Issue.DARK, CaptureQuality.inspect(sample));
            sample.eraseColor(Color.WHITE);
            assertEquals(CaptureQuality.Issue.NONE, CaptureQuality.inspect(sample));
            int[] row = new int[sample.getWidth()];
            for (int x = 0; x < row.length; x++) {
                int value = 75 + x / 3;
                row[x] = Color.rgb(value, value, value);
            }
            for (int y = 0; y < sample.getHeight(); y++) sample.setPixels(row, 0,row.length,0,y,row.length,1);
            assertEquals(CaptureQuality.Issue.NONE, CaptureQuality.inspect(sample));
        } finally {
            sample.recycle();
        }
    }

    @Test public void retakingOnlyDiscardsTheUnacceptedShot() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File accepted = File.createTempFile("accepted-page", ".jpg", context.getCacheDir());
        File candidate = File.createTempFile("candidate-page", ".jpg", context.getCacheDir());
        Instrumentation device = InstrumentationRegistry.getInstrumentation();
        CaptureReview review = new CaptureReview();
        try {
            for (File shot : new File[]{accepted, candidate}) {
                Bitmap dark = Bitmap.createBitmap(140,180, Bitmap.Config.ARGB_8888);
                dark.eraseColor(Color.rgb(21,21,21));
                try (FileOutputStream out = new FileOutputStream(shot)) {
                    assertTrue(dark.compress(Bitmap.CompressFormat.JPEG, 90, out));
                } finally {
                    dark.recycle();
                }
                CountDownLatch ready = new CountDownLatch(1);
                Observer<CaptureReview.Stage> listener = stage -> {
                    if (stage == CaptureReview.Stage.READY) ready.countDown();
                };
                device.runOnMainSync(() -> {
                    review.stage.observeForever(listener);
                    review.begin(shot);
                    review.inspect(shot);
                });
                assertTrue("completed quality check", ready.await(10, TimeUnit.SECONDS));
                device.runOnMainSync(() -> {
                    review.stage.removeObserver(listener);
                    assertEquals(CaptureQuality.Issue.DARK, review.issue);
                    assertNotNull(review.preview);
                    review.clear(shot.equals(candidate));
                });
            }
            assertTrue(accepted.isFile());
            assertFalse(candidate.exists());
        } finally {
            device.runOnMainSync(review::onCleared);
            accepted.delete();
            candidate.delete();
        }
    }
}
