package com.scandesk.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Size;
import androidx.annotation.OptIn;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = TransformExperimental.class)
public final class CaptureGuidanceTest {
    private static final CropQuad PAGE = CropQuad.from(new float[]{.09f,.08f,.91f,.08f,.91f,.93f,.09f,.93f});

    @Test public void darkOrBlankFramesNeverReportReady() {
        CaptureGuidance guidance = new CaptureGuidance();
        Bitmap dark = Bitmap.createBitmap(320, 440, Bitmap.Config.ARGB_8888);
        dark.eraseColor(Color.rgb(20,20,20));
        Bitmap blank = paper(235, false, 0);
        try {
            assertEquals(CaptureGuidance.Issue.DARK, guidance.evaluate(dark, null, 0).issue);
            for (long now : new long[]{350,700,1050,1400}) {
                CaptureGuidance.Result result = guidance.evaluate(blank, PAGE, now);
                assertEquals(CaptureGuidance.Issue.SEARCHING, result.issue);
            }
        } finally { dark.recycle();blank.recycle(); }
    }

    @Test public void whitePaperIsNotGlareButAnIsolatedClippedHotspotIsFlagged() {
        CaptureGuidance guidance = new CaptureGuidance();
        Bitmap white = paper(255, true, 0);
        Bitmap hot = paper(190, true, 255);
        Bitmap videoRangeHot = paper(180, true, 235);
        try {
            assertNotEquals(CaptureGuidance.Issue.GLARE, guidance.evaluate(white, PAGE, 0).issue);
            assertEquals(CaptureGuidance.Issue.GLARE, guidance.evaluate(hot, PAGE, 350).issue);
            assertEquals(CaptureGuidance.Issue.GLARE, guidance.evaluate(videoRangeHot, PAGE, 700).issue);
        } finally { white.recycle();hot.recycle();videoRangeHot.recycle(); }
    }

    @Test public void onlySeveralStableDetailedFramesCanBecomeReadyAndMovementResetsThem() {
        CaptureGuidance guidance = new CaptureGuidance();
        Bitmap text = paper(228, true, 0);
        CropQuad moved = CropQuad.from(new float[]{.12f,.08f,.94f,.08f,.94f,.93f,.12f,.93f});
        try {
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, PAGE, 0).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, PAGE, 350).issue);
            assertEquals(CaptureGuidance.Issue.READY, guidance.evaluate(text, PAGE, 700).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, moved, 1050).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, moved, 1400).issue);
            assertEquals(CaptureGuidance.Issue.READY, guidance.evaluate(text, moved, 1750).issue);
            assertEquals(CaptureGuidance.Issue.SEARCHING, guidance.evaluate(text, null, 1900).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, moved, 2000).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, moved, 2100).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(text, moved, 2200).issue);
            assertEquals(CaptureGuidance.Issue.READY, guidance.evaluate(text, moved, 2700).issue);
        } finally { text.recycle(); }
    }

    @Test public void clippedPaperAndDistantPaperGivePositionHints() {
        CaptureGuidance guidance = new CaptureGuidance();
        Bitmap text = paper(228, true, 0);
        CropQuad distant = CropQuad.from(new float[]{.30f,.28f,.70f,.28f,.70f,.72f,.30f,.72f});
        try {
            assertEquals(CaptureGuidance.Issue.EDGE, guidance.evaluate(text, CropQuad.FULL, 0).issue);
            assertEquals(CaptureGuidance.Issue.TOO_FAR, guidance.evaluate(text, distant, 350).issue);
        } finally { text.recycle(); }
    }

    @Test public void movingImageContentAlsoResetsStabilityWhenPaperCornersRemainTheSame() {
        CaptureGuidance guidance = new CaptureGuidance();
        Bitmap first = paper(228, true, 0), second = paper(228, true, 0);
        Paint ink = new Paint();
        ink.setColor(Color.rgb(35,35,35));
        new Canvas(first).drawRect(40,100,130,310,ink);
        new Canvas(second).drawRect(188,100,278,310,ink);
        try {
            guidance.evaluate(first, PAGE, 0);
            guidance.evaluate(first, PAGE, 350);
            assertEquals(CaptureGuidance.Issue.READY, guidance.evaluate(first, PAGE, 700).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(second, PAGE, 1050).issue);
            assertEquals(CaptureGuidance.Issue.MOVING, guidance.evaluate(second, PAGE, 1400).issue);
            assertEquals(CaptureGuidance.Issue.READY, guidance.evaluate(second, PAGE, 1750).issue);
        } finally { first.recycle();second.recycle(); }
    }

    @Test public void realDetectorDoesNotInventAReadyOutlineForAnEmptyScene() throws Exception {
        Bitmap empty = Bitmap.createBitmap(320,440,Bitmap.Config.ARGB_8888);
        empty.eraseColor(Color.rgb(228,228,228));
        CaptureGuidance guidance = new CaptureGuidance();
        try {
            for (long now : new long[]{0,350,700,1050}) {
                CaptureGuidance.Result result = guidance.analyze(empty, now);
                assertEquals(CaptureGuidance.Issue.SEARCHING, result.issue);
                assertNull(result.paper);
            }
        } finally { empty.recycle(); }
    }

    @Test public void lumaCopyHonorsCropPaddingPixelStrideAndBufferPosition() {
        int width = 8, height = 6, stride = 30, start = 3;
        ByteBuffer plane = ByteBuffer.allocate(start + stride * height);
        for (int i = 0; i < plane.capacity(); i++) plane.put(i, (byte) 222);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            plane.put(start + y * stride + x * 3, (byte)(y * 16 + x));
        plane.position(start);
        Bitmap result = CaptureGuidance.lumaFrame(plane, width, height, stride, 3, new Rect(2,1,6,5), 4);
        try {
            assertEquals(4, result.getWidth());assertEquals(4, result.getHeight());
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
                assertEquals((y + 1) * 16 + x + 2, Color.red(result.getPixel(x,y)));
            assertEquals(start, plane.position());
        } finally { result.recycle(); }
    }

    @Test public void cameraXTransformPreservesCropRotationLetterboxAndFrontMirror() {
        Rect crop = new Rect(10,20,110,220);
        ImageProxy proxy = (ImageProxy) Proxy.newProxyInstance(ImageProxy.class.getClassLoader(),
                new Class<?>[]{ImageProxy.class}, (object, method, args) -> {
                    switch (method.getName()) {
                        case "getWidth":return 140;
                        case "getHeight":return 260;
                        case "getCropRect":return crop;
                        default:return null;
                    }
                });
        ImageProxyTransformFactory factory = new ImageProxyTransformFactory();
        factory.setUsingCropRect(false);
        factory.setUsingRotationDegrees(false);
        OutputTransform source = factory.getOutputTransform(proxy);
        float[] normalized = {-1,-1,1,-1,1,1,-1,1};
        // A clockwise quarter-turn in a 300x400 FIT_CENTER preview leaves vertical letterbox bars.
        float[] expected = {300,125,300,275,0,275,0,125};
        Matrix targetMatrix = new Matrix();
        assertTrue(targetMatrix.setPolyToPoly(normalized,0,expected,0,4));
        Matrix mapping = new Matrix();
        new CoordinateTransform(source, new OutputTransform(targetMatrix, new Size(100,200))).transform(mapping);
        assertArrayEquals(expected, CaptureGuidance.previewPoints(CropQuad.FULL, crop, mapping), .01f);
        targetMatrix.postScale(-1,1,150,200);
        new CoordinateTransform(source, new OutputTransform(targetMatrix, new Size(100,200))).transform(mapping);
        assertArrayEquals(new float[]{0,125,0,275,300,275,300,125},
                CaptureGuidance.previewPoints(CropQuad.FULL,crop,mapping), .01f);
    }

    private static Bitmap paper(int brightness, boolean writing, int hotspot) {
        Bitmap bitmap = Bitmap.createBitmap(320,440, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(35,35,35));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(brightness,brightness,brightness));
        canvas.drawRect(28,34,292,411,paint);
        if (writing) {
            paint.setColor(Color.rgb(35,35,35));paint.setTextSize(18);
            for (int row = 0; row < 12; row++) canvas.drawText("Document 01234",44,68+row*27,paint);
        }
        if (hotspot > 0) {
            paint.setColor(Color.rgb(hotspot,hotspot,hotspot));
            canvas.drawCircle(175,205,36,paint);
        }
        return bitmap;
    }
}
