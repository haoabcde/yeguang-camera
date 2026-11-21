package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

@RunWith(AndroidJUnit4.class)
public final class ScanAcceptanceTest {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 1600;
    private static final int[] X = {80, 337, 586, 810, 1115};
    private static final int[] Y = {310, 410, 510, 610, 710, 810};
    private static final String TAG = "ScanAcceptance";

    private ScanStore store;
    private AssetManager assets;
    private File artifacts;
    private final List<ScanDocument> temporary = new ArrayList<>();

    @Before public void setup() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        store = new ScanStore(context);
        assets = context.getAssets();
        artifacts = context.getExternalFilesDir("acceptance");
        if (artifacts == null) artifacts = new File(context.getFilesDir(), "acceptance");
        ScanStore.ensureDirectory(artifacts);
    }

    @After public void cleanup() throws IOException {
        for (ScanDocument doc : temporary) store.delete(doc);
    }

    @Test public void detectsTrapezoidAndKeepsColorInEnhancedPage() throws Exception {
        int shadow = Color.rgb(119, 113, 102);
        Bitmap paper = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888);
        Canvas paperCanvas = new Canvas(paper);
        Paint color = paint(28, Color.BLACK);
        color.setShader(new LinearGradient(0, 0, 900, 160, shadow, 0xffede9dd, Shader.TileMode.CLAMP));
        paperCanvas.drawRect(0, 0, 900, 1200, color);
        color.setShader(null);
        paperCanvas.drawText("Document 001", 120, 220, color);
        color.setColor(0xffaf241e);
        paperCanvas.drawCircle(650, 900, 43, color);
        paperCanvas.setBitmap(null);

        Bitmap photo = Bitmap.createBitmap(1200, 1500, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(photo);
        canvas.drawColor(0xff514c45);
        float[] target = {172, 142, 991, 202, 1079, 1300, 113, 1357};
        Matrix perspective = new Matrix();
        assertTrue(perspective.setPolyToPoly(new float[]{0, 0, 900, 0, 900, 1200, 0, 1200},
                0, target, 0, 4));
        canvas.drawBitmap(paper, perspective, new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.setBitmap(null);
        paper.recycle();
        CropQuad crop = ScanImages.detect(photo);
        assertNotNull("detect slanted sheet", crop);
        for (int i = 0; i < 4; i++) {
            assertEquals(target[i * 2] / 1200f, crop.x(i), .04f);
            assertEquals(target[i * 2 + 1] / 1500f, crop.y(i), .04f);
        }
        ScanDocument doc = blank();
        ScanDocument.Page page = add(doc, photo).crop(crop).filter(ScanDocument.Filter.COLOR);
        photo.recycle();
        doc = order(doc, Arrays.asList(page));
        Bitmap corrected = ScanImages.render(store.image(doc, page), page, ScanImages.EXPORT_EDGE);
        try {
            assertTrue(corrected.getHeight() > corrected.getWidth());
            int enhancedBackground = corrected.getPixel(corrected.getWidth() / 12, corrected.getHeight() / 2);
            assertTrue("shadow is reduced", Color.green(enhancedBackground) > 205);
            int stamp = corrected.getPixel(Math.round(corrected.getWidth() * 650 / 900f),
                    Math.round(corrected.getHeight() * 900 / 1200f));
            assertTrue("red stamp preserved", Color.red(stamp) - Color.green(stamp) > 55);
            try (FileOutputStream out = new FileOutputStream(new File(artifacts, "corrected.png"))) {
                assertTrue(corrected.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        } finally {
            corrected.recycle();
        }
    }

    @Test public void normalizesExifAndPersistsManualEditing() throws Exception {
        ScanDocument doc = blank();
        Bitmap landscape = Bitmap.createBitmap(640, 420, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(landscape);
        canvas.drawColor(Color.WHITE);
        canvas.drawRect(0, 0, 320, 420, paint(18, 0xffca302c));
        canvas.setBitmap(null);
        ScanDocument.Page page = add(doc, landscape);
        File source = store.image(doc, page);
        try (FileOutputStream out = new FileOutputStream(source)) {
            assertTrue(landscape.compress(Bitmap.CompressFormat.JPEG, 95, out));
        }
        landscape.recycle();
        ExifInterface exif = new ExifInterface(source.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        Bitmap normalized = ScanImages.source(source, 0, 1000);
        try {
            assertEquals(420, normalized.getWidth());
            assertEquals(640, normalized.getHeight());
            assertTrue(Color.red(normalized.getPixel(215, 100)) > 175);
            assertTrue(Color.green(normalized.getPixel(215, 100)) < 100);
        } finally {
            normalized.recycle();
        }
        CropQuad draft = CropQuad.FULL.move(0, .1f, .09f);
        page = page.crop(draft).rotate().filter(ScanDocument.Filter.GRAY);
        doc = order(doc, Arrays.asList(page));
        ScanDocument restored = new ScanStore(ApplicationProvider.getApplicationContext()).load(doc.id);
        assertEquals(1, restored.pages.get(0).rotation);
        assertEquals(draft.clockwise(), restored.pages.get(0).crop);
        assertEquals(ScanDocument.Filter.GRAY, restored.pages.get(0).filter);
        ScanRecognition.WordDraft words = new ScanRecognition.WordDraft(Arrays.asList("校对存档"));
        store.saveReview(doc, ScanRecognition.WORD, words.json());
        assertEquals("校对存档", ScanRecognition.WordDraft.from(
                store.loadReview(restored, ScanRecognition.WORD)).pages.get(0));
        ScanDocument changed = doc.withPages(Arrays.asList(page.filter(ScanDocument.Filter.MONO)));
        store.save(changed);
        assertNull(store.loadReview(changed, ScanRecognition.WORD));
    }

    @Test public void wordCropPreservesNativeDetailAcrossExifAndEditorRotations() throws Exception {
        File jpeg = new File(artifacts, "word-roi-orientation.jpg");
        Bitmap source = Bitmap.createBitmap(3200, 2100, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(source);
        c.drawColor(Color.WHITE);
        c.drawRect(0, 0, 1600, 1050, paint(12, Color.RED));
        c.drawRect(1600, 0, 3200, 1050, paint(12, Color.GREEN));
        c.drawRect(0, 1050, 1600, 2100, paint(12, Color.BLUE));
        c.drawRect(1600, 1050, 3200, 2100, paint(12, Color.YELLOW));
        c.setBitmap(null);
        try (FileOutputStream out = new FileOutputStream(jpeg)) {
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 96, out));
        } finally {
            source.recycle();
        }
        CropQuad crop = CropQuad.from(new float[]{.13f,.21f, .68f,.23f, .72f,.68f, .16f,.73f});
        for (int orientation : new int[]{ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE}) {
            ExifInterface exif = new ExifInterface(jpeg.getPath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            exif.saveAttributes();
            ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, crop, ScanDocument.Filter.ORIGINAL);
            for (int rotation = 0; rotation < 4; rotation++) {
                Bitmap expected = ScanImages.render(jpeg, page, 3200);
                Bitmap actual = ScanImages.renderWord(jpeg, page);
                try {
                    assertEquals(expected.getWidth(), actual.getWidth(), 1);
                    assertEquals(expected.getHeight(), actual.getHeight(), 1);
                    for (float x : new float[]{.15f, .85f}) {
                        for (float y : new float[]{.15f, .85f}) {
                            int color = expected.getPixel((int) (expected.getWidth()*x), (int) (expected.getHeight()*y));
                            int out = actual.getPixel((int) (actual.getWidth()*x), (int) (actual.getHeight()*y));
                            assertEquals(Color.red(color), Color.red(out), 3);
                            assertEquals(Color.green(color), Color.green(out), 3);
                            assertEquals(Color.blue(color), Color.blue(out), 3);
                        }
                    }
                } finally {
                    expected.recycle();
                    actual.recycle();
                }
                page = page.rotate();
            }
        }
    }

    @Test public void autoEnhancesShadedChinesePrintAndExportsTheSelectedLook() throws Exception {
        Bitmap fixture;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("printed-table.jpg")) {
            fixture = BitmapFactory.decodeStream(input);
        }
        CropQuad paper = ScanImages.detect(fixture);
        assertNotNull(paper);
        Bitmap photo = ScanImages.rectify(fixture, paper);
        fixture.recycle();
        Canvas canvas = new Canvas(photo);
        Paint shadow = new Paint();
        shadow.setShader(new LinearGradient(photo.getWidth() * .22f, photo.getHeight() * .32f,
                photo.getWidth() * .73f, photo.getHeight() * .40f, 0x80000000, 0x00000000,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, photo.getWidth(), photo.getHeight(), shadow);
        canvas.setBitmap(null);
        ScanDocument doc = blank();
        ScanDocument.Page original = add(doc, photo);
        ScanDocument.Page page = original.filter(ScanDocument.Filter.AUTO);
        doc = order(doc, List.of(page));
        File pdfFile = new File(artifacts, "auto-document.pdf");
        File png = new File(artifacts, "auto-document.png");
        try (FileOutputStream out = new FileOutputStream(png)) {
            ScanExport.image(store, doc, page, true, out);
        }
        try (FileOutputStream out = new FileOutputStream(pdfFile)) {
            ScanExport.pdf(store, doc, false, out, (done, total) -> {});
        }
        try (FileOutputStream out = new FileOutputStream(new File(artifacts, "auto-original.png"))) {
            ScanExport.image(store, doc, original, true, out);
        }
        Bitmap restored = ScanImages.render(store.image(doc, page), original, ScanImages.EXPORT_EDGE);
        Bitmap enhanced = BitmapFactory.decodeFile(png.getPath());
        Bitmap preview = ScanImages.render(store.image(doc, page), page, 720);
        try {
            assertTrue("original image survives export", photo.sameAs(restored));
            assertEquals(ScanDocument.Filter.AUTO, store.load(doc.id).pages.get(0).filter);
            int darkBefore = greenAt(photo, .12, .94), brightBefore = greenAt(photo, .88, .94);
            int darkAfter = greenAt(enhanced, .12, .94), brightAfter = greenAt(enhanced, .88, .94);
            assertTrue("shadow lifted", darkAfter - darkBefore > 70);
            assertTrue("paper is lit more evenly", Math.abs(brightAfter - darkAfter)
                    < (brightBefore - darkBefore) * .45);
            assertTrue("paper retains headroom", darkAfter < 252 && brightAfter < 252);
            int inkBefore = printContrast(photo), inkAfter = printContrast(enhanced);
            assertTrue("small type separates from paper", inkAfter > inkBefore + 45);
            assertEquals(darkAfter, greenAt(preview, .12, .94), 5);
            assertEquals(brightAfter, greenAt(preview, .88, .94), 5);
            int redMark = 0;
            for (int y = enhanced.getHeight() * 3 / 4; y < enhanced.getHeight() * .95; y += 2) {
                for (int x = enhanced.getWidth() * 2 / 3; x < enhanced.getWidth() * .95; x += 2) {
                    int color = enhanced.getPixel(x, y);
                    redMark = Math.max(redMark, Color.red(color) - Color.green(color));
                }
            }
            assertTrue("red stamp retained", redMark > 65);
            try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(pdfFile,
                    ParcelFileDescriptor.MODE_READ_ONLY)); PdfRenderer.Page first = pdf.openPage(0)) {
                Bitmap rendered = Bitmap.createBitmap(first.getWidth(), first.getHeight(), Bitmap.Config.ARGB_8888);
                first.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                assertEquals("PDF shadow matches PNG", darkAfter, greenAt(rendered, .12, .94), 5);
                rendered.recycle();
            }
            Log.i(TAG, "AUTO shadow " + darkBefore + " -> " + darkAfter + ", light " + brightBefore
                    + " -> " + brightAfter + ", print contrast " + inkBefore + " -> " + inkAfter);
        } finally {
            restored.recycle();
            enhanced.recycle();
            preview.recycle();
            photo.recycle();
        }
    }

    private static int greenAt(Bitmap bitmap, double x, double y) {
        return Color.green(bitmap.getPixel((int) (bitmap.getWidth() * x), (int) (bitmap.getHeight() * y)));
    }

    @Test public void limitsChromaNoiseOnShadowedPaperAndKeepsFaintStrokes() throws Exception {
        Bitmap paper = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888);
        int[] row = new int[256];
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < row.length; x++) {
                int tint = ((x + y) % 2 == 0) ? 3 : -3;
                row[x] = x >= 160 ? Color.rgb(250, 250, 250)
                        : Color.rgb(95 + tint, 95 - tint, 95);
            }
            paper.setPixels(row, 0, 256, 0, y, 256, 1);
        }
        Canvas c = new Canvas(paper);
        c.drawRect(20, 63, 100, 65, paint(12, Color.rgb(83, 83, 83)));
        c.setBitmap(null);
        ScanDocument doc = blank();
        ScanDocument.Page page = add(doc, paper).filter(ScanDocument.Filter.AUTO);
        Bitmap enhanced = ScanImages.render(store.image(doc, page), page, 256);
        try {
            int patch = enhanced.getPixel(40, 52);
            assertTrue(Color.green(patch) > 220);
            assertTrue("shadow chroma noise", Math.abs(Color.red(patch) - Color.green(patch)) < 7);
            assertTrue("faint line retained", Color.green(patch) - Color.green(enhanced.getPixel(40, 64)) > 20);
            assertEquals(250, Color.green(enhanced.getPixel(240, 52)), 1);
        } finally {
            enhanced.recycle();
            paper.recycle();
        }
    }

    // Measure the 90th–10th percentile contrast in the same shaded column of small print.
    private static int printContrast(Bitmap bitmap) {
        int[] histogram = new int[256];
        int count = 0;
        for (int y = bitmap.getHeight() / 3; y < bitmap.getHeight() * .66; y++) {
            for (int x = bitmap.getWidth() / 8; x < bitmap.getWidth() / 3; x++) {
                histogram[Color.green(bitmap.getPixel(x, y))]++;
                count++;
            }
        }
        int low = 0, sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += histogram[i];
            if (sum < count / 10) low = i + 1;
            if (sum >= count * .9) return i - low;
        }
        throw new AssertionError("empty text region");
    }

    @Test public void exportsOneAndTwentyPagesWithExactEditedOrder() throws Exception {
        ScanDocument doc = blank();
        List<ScanDocument.Page> pages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int width = i % 2 == 0 ? 1500 : 1800;
            int height = i % 2 == 0 ? 2200 : 1180;
            Bitmap page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(page);
            canvas.drawColor(i % 3 == 0 ? 0xffe3dfd9 : Color.WHITE);
            canvas.drawRect(width * .43f, height * .44f, width * .57f, height * .56f,
                    paint(24, marker(i)));
            canvas.drawText("Scan page " + (i + 1), 90, 150, paint(72, Color.BLACK));
            canvas.setBitmap(null);
            ScanDocument.Page stored = add(doc, page);
            if (i % 4 == 0) stored = stored.rotate();
            pages.add(stored);
            page.recycle();
        }
        java.util.Collections.rotate(pages, 3);
        doc = order(doc, pages);
        File batch = new File(artifacts, "batch-20.pdf");
        long start = SystemClock.elapsedRealtime();
        long baselinePss = Debug.getPss();
        AtomicLong peakPss = new AtomicLong(baselinePss);
        ScheduledExecutorService samples = Executors.newSingleThreadScheduledExecutor();
        samples.scheduleAtFixedRate(() -> peakPss.accumulateAndGet(Debug.getPss(), Math::max),
                0, 25, TimeUnit.MILLISECONDS);
        try (FileOutputStream out = new FileOutputStream(batch)) {
            ScanExport.pdf(store, doc, true, out, (done, total) -> {});
        } finally {
            samples.shutdownNow();
        }
        Log.i(TAG, String.format(Locale.ROOT, "20-page PDF: %d ms, %d KiB baseline, %d KiB sampled peak PSS, %d bytes",
                SystemClock.elapsedRealtime() - start, baselinePss, peakPss.get(), batch.length()));
        try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(batch,
                ParcelFileDescriptor.MODE_READ_ONLY))) {
            assertEquals(20, pdf.getPageCount());
            for (int i = 0; i < 20; i++) {
                try (PdfRenderer.Page page = pdf.openPage(i)) {
                    Bitmap thumbnail = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
                    Matrix transform = new Matrix();
                    transform.setScale(120f / page.getWidth(), 120f / page.getHeight());
                    page.render(thumbnail, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    int actual = thumbnail.getPixel(60, 60);
                    int expected = marker((i + 17) % 20);
                    assertEquals("red channel page " + (i + 1), Color.red(expected), Color.red(actual), 6);
                    assertEquals("green channel page " + (i + 1), Color.green(expected), Color.green(actual), 6);
                    thumbnail.recycle();
                }
            }
        }
        File single = new File(artifacts, "single.pdf");
        try (FileOutputStream out = new FileOutputStream(single)) {
            ScanExport.pdf(store, doc.withPages(Arrays.asList(pages.get(0))), false, out, (done, total) -> {});
        }
        try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(single,
                ParcelFileDescriptor.MODE_READ_ONLY))) {
            assertEquals(1, pdf.getPageCount());
        }
    }

    @Test public void readsTableGridAndExportsEditableNumbersAndMerges() throws Exception {
        Bitmap table = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(table);
        canvas.drawColor(Color.WHITE);
        Paint line = paint(38, Color.BLACK);
        line.setStrokeWidth(3);
        for (int y : Y) canvas.drawLine(X[0], y, X[4], y, line);
        for (int c = 0; c < X.length; c++) {
            canvas.drawLine(X[c], c == 0 || c == X.length - 1 ? Y[0] : Y[1], X[c], Y[5], line);
        }
        textAt(canvas, "采购清单", X[0] + 28, Y[0] + 63, 44);
        String[][] numbers = {
                {"商品编号", "物品", "数量", "金额"},
                {"00042", "纸张", "6", "12.50"},
                {"00043", "墨盒", "", "-3.75"},
                {"00044", "钢笔", "10", "1234.50"},
        };
        for (int r = 0; r < numbers.length; r++) {
            for (int c = 0; c < numbers[r].length; c++) {
                textAt(canvas, numbers[r][c], X[c] + 24, Y[r + 1] + 64, 38);
            }
        }
        canvas.setBitmap(null);
        Bitmap skewed = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas warped = new Canvas(skewed);
        warped.drawColor(Color.WHITE);
        Matrix perspective = new Matrix();
        assertTrue(perspective.setPolyToPoly(new float[]{0, 0, WIDTH, 0, WIDTH, HEIGHT, 0, HEIGHT}, 0,
                new float[]{22, 29, WIDTH - 6, 7, WIDTH - 4, HEIGHT - 9, 4, HEIGHT - 13}, 0, 4));
        warped.drawBitmap(table, perspective, new Paint(Paint.FILTER_BITMAP_FLAG));
        warped.setBitmap(null);
        table.recycle();
        ScanDocument doc = blank();
        doc = order(doc, Arrays.asList(add(doc, skewed)));
        skewed.recycle();
        ScanRecognition.TableDraft result = ScanRecognition.tables(assets, store, doc, (done, total) -> {});
        assertEquals(1, result.tables.size());
        OfficeTable scanned = result.tables.get(0);
        assertEquals(5, scanned.rows);
        assertEquals(4, scanned.columns);
        assertEquals(4, scanned.cell(0, 0).width);
        assertEquals("金额", scanned.cell(1, 3).text);
        assertEquals("00042", scanned.cell(2, 0).text);
        assertEquals("", scanned.cell(3, 2).text);
        assertEquals("-3.75", scanned.cell(3, 3).text.replace('−', '-'));
        assertEquals("钢笔", scanned.cell(4, 1).text);
        scanned.cell(2, 1).text = "已校对";
        File excel = new File(artifacts, "table.xlsx");
        OfficeExport.excel(result.tables, new FileOutputStream(excel));
        String xml = unzip(excel, "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("<mergeCell ref=\"A1:D1\"/>"));
        assertTrue(xml.contains("00042"));
        assertTrue(xml.contains("<c r=\"D4\" s=\"0\"><v>-3.75</v></c>"));
        assertTrue(xml.contains("已校对"));
        assertNull(OfficeExport.numeric("00042", false));
        assertNull(OfficeExport.numeric("=SUM(A1:A2)", false));
    }

    @Test public void readsChineseAndEnglishAndExportsEditedWordPages() throws Exception {
        ScanDocument doc = blank();
        List<ScanDocument.Page> sources = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Bitmap page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(page);
            canvas.drawColor(Color.WHITE);
            textAt(canvas, i == 0 ? "采购清单" : "验收记录", 110, 218, 69);
            textAt(canvas, i == 0 ? "Purchase Order 1120" : "Inspection Report 2026", 110, 350, 48);
            textAt(canvas, "数量与金额已核对", 110, 475, 44);
            canvas.setBitmap(null);
            sources.add(add(doc, page));
            page.recycle();
        }
        doc = order(doc, sources);
        ScanRecognition.WordDraft result = ScanRecognition.word(assets, store, doc, (done, total) -> {});
        assertEquals(2, result.pages.size());
        String heading = result.pages.get(0).split("\n", 2)[0];
        assertEquals("采购清单", heading);
        assertTrue(result.pages.get(0), result.pages.get(0).contains("Purchase Order 1120"));
        assertTrue(result.pages.get(1), result.pages.get(1).contains("Inspection Report"));
        // Manual edits must survive export alongside the recognized text.
        result.pages.set(1, result.pages.get(1) + "\n\n已复核 & 校对");
        File office = new File(artifacts, "word.docx");
        OfficeExport.word(result.pages, new FileOutputStream(office));
        String xml = unzip(office, "word/document.xml");
        assertTrue(xml.contains("采购清单"));
        assertTrue(xml.contains("已复核 &amp; 校对"));
        assertTrue(xml.contains("<w:pageBreakBefore/>"));
        assertTrue(xml.indexOf("Purchase Order") < xml.indexOf("Inspection Report"));
    }

    @Test public void handwritingKeepsBlankPagesAndExistingPrintedDraft() throws Exception {
        ScanDocument doc = blank();
        Bitmap paper = Bitmap.createBitmap(760, 520, Bitmap.Config.ARGB_8888);
        ScanDocument.Page blankPage, numberedPage;
        try {
            paper.eraseColor(Color.WHITE);
            assertEquals(ScanDocument.Filter.AUTO, ScanImages.initialHandwritingFilter(paper, CropQuad.FULL));
            blankPage = add(doc, paper);
            Canvas canvas = new Canvas(paper);
            canvas.drawRect(0, 0, 300, 500, paint(10, Color.BLACK));
            CropQuad dark = CropQuad.from(new float[]{.02f,.02f,.37f,.02f,.37f,.92f,.02f,.92f});
            assertEquals(ScanDocument.Filter.ORIGINAL, ScanImages.initialHandwritingFilter(paper, dark));
            canvas.drawColor(Color.WHITE);
            textAt(canvas, "手记 00742", 70, 185, 66);
            canvas.setBitmap(null);
            numberedPage = add(doc, paper);
        } finally {
            paper.recycle();
        }
        doc = order(doc, List.of(blankPage, numberedPage));
        store.saveReview(doc, ScanRecognition.WORD,
                new ScanRecognition.WordDraft(List.of("已有校对", "保留正文")).json());
        ScanRecognition.WordDraft hand = ScanRecognition.handwriting(assets, store, doc, (done, total) -> {});
        assertEquals(2, hand.pages.size());
        assertEquals("", hand.pages.get(0).trim());
        assertTrue(hand.pages.get(1), hand.pages.get(1).contains("00742"));
        assertTrue(hand.numberLines.get(0).isEmpty());
        assertTrue(hand.numberLines.get(1).stream().anyMatch(row -> row.text.contains("00742")
                && row.bounds.centerY() < .6f));
        store.saveReview(doc, ScanRecognition.HANDWRITING, hand.json());
        assertEquals("保留正文", ScanRecognition.WordDraft.from(store.loadReview(doc, ScanRecognition.WORD)).pages.get(1));
        assertEquals(hand.pages, ScanRecognition.WordDraft.from(store.loadReview(doc, ScanRecognition.HANDWRITING)).pages);
    }

    @Test public void benchmarksPrintedChineseAgainstTranscriptions() throws Exception {
        List<String> expected = Arrays.asList(
                "采购清单", "验收记录", "商品编号", "数量与金额已核对", "钢笔和文件夹",
                "请查收并确认交付日期", "甲方保留原始凭证", "工程竣工验收报告",
                "已收到付款凭据", "设备维护与故障排查", "龚雪与覃思远参加核验",
                "欧阳旻负责登记", "繁體中文金額與數量", "产品规格及质量证明",
                "编号 GX-0012", "小计金额 12,345.60 元", "余额 -3.75 元",
                "协议有效期至 2027 年 12 月 31 日", "附录 A 版本 V2.1");
        ScanDocument doc = blank();
        List<ScanDocument.Page> sources = new ArrayList<>();
        JSONArray baseline = new JSONArray();
        try (TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())) {
            for (int variant = 0; variant < 3; variant++) {
                int background = variant == 1 ? 0xffd8d2c6 : Color.WHITE;
                Bitmap page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(page);
                canvas.drawColor(background);
                for (int i = 0; i < expected.size(); i++) {
                    int size = variant == 0 ? i == 0 ? 69 : 40 : variant == 1 ? 32 : 26;
                    Paint paint = paint(size, variant == 1 ? 0xff827870 : Color.BLACK);
                    if (variant == 2) paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                    canvas.drawText(expected.get(i), 110, 130 + i * 73, paint);
                }
                canvas.setBitmap(null);
                sources.add(add(doc, page));
                try (FileOutputStream file = new FileOutputStream(new File(artifacts, "ocr-page-" + variant + ".png"))) {
                    assertTrue(page.compress(Bitmap.CompressFormat.PNG, 100, file));
                }
                Text recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(page, 0)), 60, TimeUnit.SECONDS);
                JSONArray lines = new JSONArray();
                for (Text.TextBlock block : recognized.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        android.graphics.Rect box = line.getBoundingBox();
                        if (box == null) continue;
                        int nearest = Math.max(0, Math.min(expected.size() - 1,
                                Math.round((box.bottom - 130f) / 73f)));
                        JSONArray rect = new JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom);
                        lines.put(new JSONObject().put("index", nearest).put("box", rect)
                                .put("text", line.getText()).put("confidence", line.getConfidence()));
                    }
                }
                baseline.put(lines);
                page.recycle();
            }
        }
        doc = order(doc, sources);
        long start = SystemClock.elapsedRealtime();
        ScanRecognition.WordDraft draft = ScanRecognition.word(assets, store, doc, (done, total) -> {});
        long millis = SystemClock.elapsedRealtime() - start;
        int sourceChars = 0;
        int errors = 0;
        for (String page : draft.pages) {
            String source = hanOnly(String.join("", expected));
            sourceChars += source.length();
            errors += characterDistance(source, hanOnly(page));
        }
        JSONObject report = new JSONObject().put("expected", new JSONArray(expected)).put("baseline", baseline)
                .put("pages", new JSONArray(draft.pages)).put("errors", errors).put("sourceChars", sourceChars)
                .put("millis", millis);
        try (FileOutputStream output = new FileOutputStream(new File(artifacts, "ocr-benchmark.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "Chinese benchmark: " + errors + "/" + sourceChars + " edits, " + millis + " ms");
        assertEquals(sources.size(), draft.pages.size());
        assertTrue("Expected at most five Han edits on the printed fixtures: " + errors, errors <= 5);
    }

    @Test public void keepsNumbersAndPunctuationWhenRefiningHan() {
        assertEquals("小计金额 12,345.60 元，编号 GX-0012", ChineseOcr.mergeHan(
                "小计全额 12,345.60 元，编号 GX-0012", "小计金额12,345.60元,编号GX-0012"));
        assertEquals("余額 -3.75 元", ChineseOcr.mergeHan("余額 -3.75 元", "余额3.75元"));
        assertEquals("編号 GX-0012", ChineseOcr.mergeHan("編号 GX-0012", "编号GX-012"));
        assertEquals("已 核 对", ChineseOcr.mergeHan("己 核 对", "已核对"));
        assertEquals("采购清单", ChineseOcr.mergeHan("采购清单", "设备维护"));
    }

    private static String hanOnly(String input) {
        StringBuilder output = new StringBuilder();
        input.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .forEach(output::appendCodePoint);
        return output.toString();
    }

    @Test public void infersBorderlessColumnsAndPreservesBlankCells() throws Exception {
        Bitmap paper = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(paper);
        canvas.drawColor(Color.WHITE);
        String[][] values = {
                {"Code", "Item", "Amount"},
                {"0012", "Apple", "4.50"},
                {"0013", "Pear", ""},
                {"0014", "", "-7.00"},
        };
        for (int r = 0; r < values.length; r++) {
            for (int c = 0; c < values[r].length; c++) {
                textAt(canvas, values[r][c], 100 + 350 * c, 360 + 95 * r, 44);
            }
        }
        canvas.setBitmap(null);
        ScanDocument document = blank();
        document = order(document, Arrays.asList(add(document, paper)));
        paper.recycle();
        ScanRecognition.TableDraft draft = ScanRecognition.tables(assets, store, document, (done, total) -> {});
        assertEquals(1, draft.tables.size());
        OfficeTable table = draft.tables.get(0);
        assertTrue(table.inferred);
        assertEquals(4, table.rows);
        assertEquals(3, table.columns);
        assertEquals("0012", table.cell(1, 0).text);
        assertEquals("", table.cell(2, 2).text);
        assertEquals("", table.cell(3, 1).text);
        assertEquals("-7.00", table.cell(3, 2).text.replace('−', '-'));
    }

    @Test public void keepsAllRowsAndColumnsAfterManualCropOnPrintedPhoto() throws Exception {
        checkPrintedTable(ScanDocument.Filter.ORIGINAL);
    }

    @Test public void keepsPrintedTableCellsWithAutomaticEnhancement() throws Exception {
        checkPrintedTable(ScanDocument.Filter.AUTO);
    }

    @Test public void readsSixColumnsOnFullAndTightlyCroppedLedger() throws Exception {
        ScanDocument doc = blank();
        List<ScanDocument.Page> pages = new ArrayList<>();
        // The test-only PNG is the unrotated procurement() fixture from tools/qa/make_samples.py.
        CropQuad tableBounds = CropQuad.from(new float[]{
                81f/1199, 407f/1629, 1121f/1199, 407f/1629,
                1121f/1199, 1241f/1629, 81f/1199, 1241f/1629});
        assertNotNull(tableBounds);
        for (CropQuad crop : List.of(CropQuad.FULL, tableBounds)) {
            ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, crop, ScanDocument.Filter.AUTO);
            try (InputStream source = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                    .open("flat-ledger.png");
                    FileOutputStream output = new FileOutputStream(store.image(doc, page))) {
                byte[] buffer = new byte[16384];
                for (int count; (count = source.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            pages.add(page);
        }
        ScanRecognition.TableDraft result = ScanRecognition.tables(assets, store, order(doc, pages), (done, total) -> {});
        assertEquals(2, result.tables.size());
        File excel = new File(artifacts, "full-and-cropped-ledger.xlsx");
        OfficeExport.excel(result.tables, new FileOutputStream(excel));
        for (int i = 0; i < result.tables.size(); i++) {
            OfficeTable table = result.tables.get(i);
            String description = i == 0 ? "full page" : "tight crop";
            assertEquals(description, i + 1, table.page);
            assertEquals(description, 13, table.rows);
            assertEquals(description, 6, table.columns);
            assertEquals(description, "001", table.cell(1, 0).text);
            assertEquals(description, "A4复印纸", table.cell(1, 1).text.replace(" ", ""));
            assertEquals(description, "个", table.cell(5, 3).text);
            assertEquals(description, "个", table.cell(6, 3).text);
            assertEquals(description, "个", table.cell(7, 3).text);
            assertEquals(description, "1000枚", table.cell(8, 2).text.replace(" ", ""));
            assertEquals(description, "", table.cell(12, 4).text);
            assertEquals(description, "-20.00", table.cell(12, 5).text.replace('−', '-'));
            String xml = unzip(excel, "xl/worksheets/sheet" + (i + 1) + ".xml");
            assertTrue(description, xml.contains("dimension ref=\"A1:F13\""));
            assertTrue(description, xml.contains("<v>1200.00</v>") && xml.contains("<v>-20.00</v>"));
            assertTrue(description, xml.contains("001") && !xml.contains("<mergeCell "));
        }
    }

    @Test public void separatesTwoRuledTablesOnOnePage() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1100, 1200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint rule = paint(3, Color.BLACK);
        rule.setStrokeWidth(3);
        for (int y : new int[]{130, 630}) {
            for (int row = 0; row <= 3; row++) canvas.drawLine(110, y + row * 85, 950, y + row * 85, rule);
            for (int col = 0; col <= 3; col++) canvas.drawLine(110 + 280 * col, y, 110 + 280 * col, y + 255, rule);
            for (int row = 0; row < 3; row++) {
                textAt(canvas, "A" + (row + 1), 145, y + row * 85 + 55, 36);
                textAt(canvas, "123", 425, y + row * 85 + 55, 36);
                textAt(canvas, "60", 705, y + row * 85 + 55, 36);
            }
        }
        canvas.setBitmap(null);
        ScanDocument doc = blank();
        ScanDocument.Page page = add(doc, bitmap);
        bitmap.recycle();
        ScanRecognition.TableDraft result = ScanRecognition.tables(assets, store, order(doc, List.of(page)),
                (done, total) -> {});
        assertEquals(2, result.tables.size());
        for (OfficeTable table : result.tables) {
            assertEquals(1, table.page);
            assertEquals(3, table.rows);
            assertEquals(3, table.columns);
            assertEquals("123", table.cell(1, 1).text);
        }
    }

    private void checkPrintedTable(ScanDocument.Filter filter) throws Exception {
        ScanDocument doc = blank();
        CropQuad crop = CropQuad.from(new float[]{.100043f, .055482f, .966398f, .066000f,
                .916667f, .960000f, .032258f, .933000f});
        assertNotNull(crop);
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, crop, filter);
        try (InputStream fixture = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("printed-table.jpg");
                FileOutputStream out = new FileOutputStream(store.image(doc, page))) {
            byte[] buffer = new byte[32768];
            for (int count; (count = fixture.read(buffer)) != -1;) out.write(buffer, 0, count);
        }
        doc = order(doc, Arrays.asList(page));
        ScanRecognition.TableDraft draft = ScanRecognition.tables(assets, store, doc, (done, total) -> {});
        List<String> dimensions = new ArrayList<>();
        for (OfficeTable table : draft.tables) dimensions.add(table.rows + "×" + table.columns);
        assertEquals(dimensions.toString(), 1, draft.tables.size());
        OfficeTable table = draft.tables.get(0);
        assertEquals(13, table.rows);
        assertEquals(6, table.columns);
        assertEquals("001", table.cell(1, 0).text);
        assertEquals("", table.cell(12, 4).text);
        assertEquals("-20.00", table.cell(12, 5).text.replace('−', '-'));
    }

    @Test public void keepsGeneratedFileWhenDestinationFailsThenRetries() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File generated = new File(artifacts, "retry-generated.docx");
        OfficeExport.word(Arrays.asList("校对文本"), new FileOutputStream(generated));
        long before = generated.length();
        try {
            ScanExport.save(context.getContentResolver(), generated,
                    Uri.parse("content://com.scandesk.app.test.missing/export.docx"));
            fail("saving without a provider should fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("可重试保存"));
            assertEquals(before, generated.length());
        }
        File retry = new File(artifacts, "retry-result.docx");
        ScanExport.save(context.getContentResolver(), generated, Uri.fromFile(retry));
        assertEquals(unzip(generated, "word/document.xml"), unzip(retry, "word/document.xml"));
    }

    private ScanDocument blank() throws IOException {
        ScanDocument doc = store.newDocument();
        store.save(doc);
        temporary.add(doc);
        return doc;
    }

    private ScanDocument order(ScanDocument doc, List<ScanDocument.Page> pages) throws IOException {
        ScanDocument ordered = doc.withPages(pages);
        store.save(ordered);
        return ordered;
    }

    private ScanDocument.Page add(ScanDocument doc, Bitmap content) throws IOException {
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0,
                CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
        try (FileOutputStream out = new FileOutputStream(store.image(doc, page))) {
            assertTrue(content.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        return page;
    }

    private static int marker(int page) {
        return Color.rgb(35 + page * 10, 176 - page * 5, 74 + page * 3);
    }

    private static Paint paint(int size, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return paint;
    }

    private static void textAt(Canvas canvas, String value, int x, int y, int size) {
        canvas.drawText(value, x, y, paint(size, Color.BLACK));
    }

    private static String unzip(File file, String part) throws IOException {
        try (ZipFile zip = new ZipFile(file);
                java.io.InputStream input = zip.getInputStream(zip.getEntry(part))) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            for (int found; (found = input.read(bytes)) != -1;) buffer.write(bytes, 0, found);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int characterDistance(String expected, String observed) {
        int[] previous = new int[observed.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= expected.length(); i++) {
            int[] current = new int[previous.length];
            current[0] = i;
            for (int j = 1; j < current.length; j++) {
                int replace = expected.charAt(i - 1) == observed.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(previous[j] + 1,
                        Math.min(current[j - 1] + 1, previous[j - 1] + replace));
            }
            previous = current;
        }
        return previous[observed.length()];
    }
}
