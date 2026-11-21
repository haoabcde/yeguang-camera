package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public final class ScreenDescreenTest {
    @Test public void suppressesFineColorBandsWithoutBlurringTextOrRuiningSlideColors() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File dir = context.getExternalFilesDir("acceptance");
        ScanStore store = new ScanStore(context);
        ScanDocument document = store.newDocument();
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.SCREEN);
        document = document.withPages(List.of(page));
        store.save(document);
        int width = 1024, height = 640;
        Bitmap slide = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(slide);
        canvas.drawColor(0xffe7e7e7);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(0xff253957);
        ink.setTextSize(48);
        canvas.drawText("季度销售简报", 60, 112, ink);
        ink.setTextSize(25);
        ink.setColor(0xff494949);
        canvas.drawText("地区与销售额（万元）", 60, 179, ink);
        int[] bars = {0xff1e6897, 0xffbb632f, 0xff347d66};
        String[] names = {"东区", "中区", "西区"};
        for (int i = 0; i < bars.length; i++) {
            int y = 245 + i * 79;
            ink.setColor(0xff393939);
            canvas.drawText(names[i], 64, y + 25, ink);
            ink.setColor(bars[i]);
            canvas.drawRect(156, y, 742 - i * 106, y + 29, ink);
            ink.setColor(0xff777777);
            canvas.drawRect(60, y + 54, 959, y + 55, ink);
        }
        ink.setColor(0xff1f559e);
        ink.setTextSize(20);
        canvas.drawText("金额保留两位小数", 60, 543, ink);
        ink.setStrokeWidth(1);
        canvas.drawLine(61, 551, 344, 551, ink);
        canvas.setBitmap(null);

        Bitmap source = slide.copy(Bitmap.Config.ARGB_8888, true);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (row[x] != 0xffe7e7e7) continue;
                double phase = x / 12.0 + y / 23.0 + .3 * Math.sin(y / 95.0);
                int stripe = (int) Math.round(22 * Math.sin(2 * Math.PI * phase));
                row[x] = Color.rgb(231 + stripe, 231 - stripe / 2, 231);
            }
            source.setPixels(row, 0, width, 0, y, width, 1);
        }
        save(source, store.image(document, page));
        save(source, new File(dir, "screen-bands-before.png"));
        File export = new File(dir, "screen-bands-after.png");
        try (FileOutputStream out = new FileOutputStream(export)) {
            ScanExport.image(store, document, page, true, out);
        }
        Bitmap result = BitmapFactory.decodeFile(export.toString());
        Bitmap original = ScanImages.render(store.image(document, page), page.filter(ScanDocument.Filter.ORIGINAL),
                ScanImages.EXPORT_EDGE);
        try {
            assertTrue(source.sameAs(original));
            assertEquals(ScanDocument.Filter.SCREEN, store.load(document.id).pages.get(0).filter);
            assertEquals(width, result.getWidth());
            double before = 0, after = 0;
            int samples = 0, mostLumaChange = 0;
            for (int y = 0; y < height; y += 2) {
                for (int x = 0; x < width; x += 2) {
                    int in = source.getPixel(x, y), out = result.getPixel(x, y);
                    mostLumaChange = Math.max(mostLumaChange, Math.abs(luma(in) - luma(out)));
                    if (slide.getPixel(x, y) == 0xffe7e7e7) {
                        before += Math.abs(Color.red(in) - Color.green(in));
                        after += Math.abs(Color.red(out) - Color.green(out));
                        samples++;
                    }
                }
            }
            assertTrue("remaining synthetic chroma " + after / before, after < before * .42);
            assertTrue("luminance structure changed " + mostLumaChange, mostLumaChange <= 1);
            for (int i = 0; i < bars.length; i++) {
                int top = 245 + i * 79, right = 742 - i * 106;
                for (int y = top; y < top + 29; y++) {
                    result.getPixels(row, 0, width, 0, y, width, 1);
                    for (int x = 156; x < right; x++) assertEquals(bars[i], row[x]);
                }
            }
            assertEquals(slide.getPixel(415, 299), result.getPixel(415, 299));
            Log.i("ScreenAcceptance", String.format(Locale.ROOT, "neutral stripe |R-G| %.2f -> %.2f, max luma delta %d",
                    before / samples, after / samples, mostLumaChange));
        } finally {
            result.recycle();
            original.recycle();
            source.recycle();
            slide.recycle();
            store.delete(document);
        }
    }

    @Test public void leavesSinglePastelBoundaryAndMonochromeRulingsIntact() {
        Bitmap clean = Bitmap.createBitmap(512, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(clean);
        Paint p = new Paint();
        canvas.drawColor(0xffadc5b5);
        p.setColor(0xffc9b3be);
        canvas.drawRect(256, 0, 512, 160, p);
        p.setColor(Color.GRAY);
        for (int y = 14; y < 160; y += 17) canvas.drawRect(20, y, 491, y + 1, p);
        canvas.setBitmap(null);
        Bitmap result = clean.copy(Bitmap.Config.ARGB_8888, true);
        try {
            ScreenDescreen.apply(result);
            assertTrue("isolated pastel edge or neutral line changed", clean.sameAs(result));
        } finally {
            clean.recycle();
            result.recycle();
        }
    }

    private static int luma(int c) {
        return (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c) + 128) >> 8;
    }

    private static void save(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }
}
