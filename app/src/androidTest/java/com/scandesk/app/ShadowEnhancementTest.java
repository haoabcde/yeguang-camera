package com.scandesk.app;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

@RunWith(AndroidJUnit4.class)
public final class ShadowEnhancementTest {
    private static final int WIDTH = 1120, HEIGHT = 1480;
    private static final int PAPER = 0xfff3f3f3;

    @Test public void recoversPaperAlongSharpShadowWithoutErasingPrint() throws Exception {
        checkShadow("sharp-shadow", y -> 346 + Math.round(.23f * y));
    }

    @Test public void preservesCurvedShadowEdgesInPreviewAndExport() throws Exception {
        checkShadow("curved-shadow", y -> 512 + (int) Math.round(170 * Math.sin(y / 250.0)));
    }

    private void checkShadow(String name, IntUnaryOperator boundary) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ScanStore store = new ScanStore(context);
        ScanDocument doc = store.newDocument();
        store.save(doc);
        File dir = context.getExternalFilesDir("acceptance");
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.AUTO);
        Bitmap clean = document();
        Bitmap shaded = shade(clean, boundary);
        try {
            save(shaded, store.image(doc, page));
            save(shaded, new File(dir, name + "-source.png"));
            File export = new File(dir, name + "-result.png");
            try (FileOutputStream out = new FileOutputStream(export)) {
                ScanExport.image(store, doc, page, true, out);
            }
            Bitmap result = BitmapFactory.decodeFile(export.toString());
            try {
                double borderError = borderDeviation(clean, result, boundary);
                int deep = green(result, 190, 1380), lit = green(result, 948, 1380);
                int pencil = green(result, 193, 1214) - green(result, 193, 1221);
                int darkLabel = green(result, 940, 160);
                int stamp = result.getPixel(873, 1190);
                Log.i("ShadowAcceptance", String.format(Locale.ROOT,
                        "%s border=%.1f deep=%d lit=%d pencil=%d label=%d stampR-G=%d",
                        name, borderError, deep, lit, pencil, darkLabel, Color.red(stamp) - Color.green(stamp)));
                assertTrue("dark-side boundary differs from nearby paper: " + borderError, borderError < 12);
                assertTrue("deep shadow differs from lit paper: " + (lit - deep), Math.abs(lit - deep) < 18);
                assertTrue("faint pencil contrast: " + pencil, pencil >= 34);
                assertTrue("solid printed label stays dark: " + darkLabel, darkLabel < 80);
                for (int x = 829; x < 1023; x++) {
                    int shade = green(result, x, 138);
                    assertTrue("halo near reversed lettering", Math.abs(shade - darkLabel) < 8);
                }
                assertTrue("stamp retains color", Color.red(stamp) - Color.green(stamp) > 65);
                Bitmap preview = ScanImages.render(store.image(doc, page), page, 720);
                try {
                    int x = boundary.applyAsInt(1115) - 8;
                    int patch = green(preview, x * preview.getWidth() / WIDTH, 1115 * preview.getHeight() / HEIGHT);
                    assertTrue("shadow border in preview/export: " + patch,
                            Math.abs(patch - green(result, x, 1115)) < 12);
                } finally {
                    preview.recycle();
                }
            } finally {
                result.recycle();
            }
        } finally {
            clean.recycle();
            shaded.recycle();
            store.delete(doc);
        }
    }

    private static Bitmap document() {
        Bitmap clean = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(clean);
        canvas.drawColor(PAPER);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setColor(0xff252525);
        p.setTextSize(50);
        canvas.drawText("项目费用核对单", 85, 176, p);
        canvas.drawRect(820, 127, 1032, 187, p);
        p.setColor(Color.WHITE);
        p.setTextSize(22);
        canvas.drawText("存档联", 843, 165, p);
        p.setColor(0xff333333);
        p.setTextSize(26);
        canvas.drawText("采购项目    办公用品与耗材", 85, 270, p);
        canvas.drawText("单据编号    FY-2026-1120", 85, 319, p);
        p.setTextSize(22);
        String[] items = {"中性笔（黑色）", "采购登记簿", "复印纸 A4", "文件袋（透明）", "彩色长尾夹"};
        int[] columns = {85, 215, 574, 784, 1033};
        for (int i = 0; i <= 12; i++) {
            int y = 397 + 54 * i;
            p.setColor(0xff888888);
            canvas.drawRect(columns[0], y, columns[4], y + 2, p);
            if (i == 12) break;
            p.setColor(0xff343434);
            String[] row = i == 0 ? new String[]{"编号", "物品名称", "数量", "金额（元）"}
                    : new String[]{String.format(Locale.ROOT, "%03d", i), items[(i - 1) % items.length],
                          Integer.toString(2 * i), String.format(Locale.ROOT, "%d.50", 27 * i)};
            for (int col = 0; col < row.length; col++) canvas.drawText(row[col], columns[col]+18, y+35, p);
        }
        p.setColor(0xff888888);
        for (int x : columns) canvas.drawRect(x, 397, x+2, 1047, p);
        p.setColor(0xffbbbbbb);
        p.setTextSize(23);
        canvas.drawText("已核对数量，第二联随货留存。", 85, 1195, p);
        p.setColor(0xffcdcdcd);
        canvas.drawRect(85, 1220, 535, 1223, p);
        p.setColor(0xffb34d47);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5);
        canvas.drawCircle(937, 1190, 64, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(23);
        canvas.drawText("已核对", 901, 1199, p);
        canvas.setBitmap(null);
        return clean;
    }

    private static Bitmap shade(Bitmap clean, IntUnaryOperator boundary) {
        Bitmap photo = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        int[] row = new int[WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            clean.getPixels(row, 0, WIDTH, 0, y, WIDTH, 1);
            double dark = .48 - .25 * y / HEIGHT;
            int edge = boundary.applyAsInt(y);
            for (int x = 0; x < WIDTH; x++) {
                double t = Math.max(0, Math.min(1, (x - edge + 4) / 8.0));
                double exposure = dark + (.94 - dark) * t * t * (3 - 2 * t);
                int c = row[x];
                row[x] = Color.rgb((int) Math.round(Color.red(c) * exposure),
                        (int) Math.round(Color.green(c) * exposure), (int) Math.round(Color.blue(c) * exposure));
            }
            photo.setPixels(row, 0, WIDTH, 0, y, WIDTH, 1);
        }
        return photo;
    }

    private static double borderDeviation(Bitmap clean, Bitmap output, IntUnaryOperator boundary) {
        long sum = 0;
        int count = 0;
        for (int y = 45; y < HEIGHT-45; y += 3) {
            int edge = boundary.applyAsInt(y), reference = edge - 52;
            for (int x = edge - 11; x <= edge - 4; x++) {
                if (clean.getPixel(reference, y) == PAPER && clean.getPixel(x, y) == PAPER) {
                    sum += Math.abs(green(output, reference, y) - green(output, x, y));
                    count++;
                }
            }
        }
        return (double) sum / count;
    }

    private static int green(Bitmap bitmap, int x, int y) {
        return Color.green(bitmap.getPixel(x, y));
    }

    private static void save(Bitmap image, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }
}
