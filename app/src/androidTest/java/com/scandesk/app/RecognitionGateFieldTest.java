package com.scandesk.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class RecognitionGateFieldTest {
    private Context context;
    private File scratch;
    private ScanStore store;
    private ScanDocument document;

    @Before public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        scratch = Files.createTempDirectory(context.getCacheDir().toPath(), "recognition-gate-").toFile();
        store = new ScanStore(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return scratch; }
        });
        document = store.newDocument();
        store.save(document);
    }

    @After public void tearDown() throws IOException {
        if (document != null) store.delete(document);
        if (scratch != null) {
            Files.deleteIfExists(new File(scratch, "scans").toPath());
            Files.deleteIfExists(scratch.toPath());
        }
    }

    @Test public void printedModeRejectsThePrivateHandwrittenExample() throws Exception {
        addPrivateExample();

        ScanRecognition.UnreliableRecognition failure = assertThrows(
                ScanRecognition.UnreliableRecognition.class, () -> recognizeAndSave(false, (done, total) -> {}));

        assertEquals(1, failure.page);
        assertFalse(failure.handwriting);
        assertFalse(reviewFile(ScanRecognition.EXCEL).exists());
    }

    @Test public void handwrittenModeReturnsThreeColumnsForTheSameExample() throws Exception {
        addPrivateExample();

        ScanRecognition.TableDraft draft = recognizeAndSave(true, (done, total) -> {});

        assertEquals(1, draft.tables.size());
        assertEquals(1, draft.tables.get(0).page);
        assertEquals(3, draft.tables.get(0).columns);
        assertTrue(draft.skipped.isEmpty());
        ScanRecognition.TableDraft saved = ScanRecognition.TableDraft.from(
                store.loadReview(document, ScanRecognition.HAND_TABLE));
        assertTrue(saved.checkedFor(ScanRecognition.HAND_TABLE));
        assertFalse(saved.checkedFor(ScanRecognition.EXCEL));
    }

    @Test public void printedModeAcceptsAClearMixedScriptTable() throws Exception {
        addGeneratedPage(true);

        ScanRecognition.TableDraft draft = recognizeAndSave(false, (done, total) -> {});

        assertTrue(draft.checkedFor(ScanRecognition.EXCEL));
        assertTrue(draft.skipped.isEmpty());
        assertEquals(1, draft.tables.size());
        assertEquals(1, draft.tables.get(0).page);
        assertEquals(3, draft.tables.get(0).columns);
    }

    @Test public void aBlankSecondPageAbortsTheBatchAndPreservesThePreviousDraft() throws Exception {
        addGeneratedPage(true);
        recognizeAndSave(false, (done, total) -> {});
        File savedReview = reviewFile(ScanRecognition.EXCEL);
        byte[] before = Files.readAllBytes(savedReview.toPath());
        addGeneratedPage(false);
        List<Integer> completed = new ArrayList<>();

        ScanRecognition.UnreliableRecognition failure = assertThrows(
                ScanRecognition.UnreliableRecognition.class, () -> recognizeAndSave(false, (done, total) -> {
                    assertEquals(2, total);
                    completed.add(done);
                }));

        assertEquals(2, failure.page);
        assertEquals("no_text", failure.reason);
        assertEquals(List.of(1), completed);
        assertArrayEquals(before, Files.readAllBytes(savedReview.toPath()));
    }

    @Test public void verificationDoesNotRecycleTheBorrowedPageBitmap() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(400, 90, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(54);
        canvas.drawText("ABC 123", 16, 65, paint);
        canvas.setBitmap(null);
        try (ChineseOcr reader = new ChineseOcr(context.getAssets())) {
            ChineseOcr.Page page = reader.on(bitmap);
            android.graphics.Rect whole = new android.graphics.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            page.verifyLine("ABC 123", whole);
            assertFalse(bitmap.isRecycled());
            page.verifyLine("ABC 123", whole);
            assertFalse(bitmap.isRecycled());
        } finally { bitmap.recycle(); }
    }

    private ScanRecognition.TableDraft recognizeAndSave(boolean handwriting, ScanExport.Progress progress)
            throws Exception {
        String key = handwriting ? ScanRecognition.HAND_TABLE : ScanRecognition.EXCEL;
        ScanRecognition.TableDraft draft = ScanRecognition.checkedTables(
                context.getAssets(), store, document, handwriting, progress);
        store.saveReview(document, key, draft.json());
        return draft;
    }

    private File reviewFile(String key) {
        return new File(store.image(document, document.pages.get(0)).getParentFile(), key + ".json");
    }

    private void addPrivateExample() throws IOException {
        File directory = context.getExternalFilesDir("handtable");
        assumeTrue("Private sample storage is unavailable", directory != null);
        File image = new File(directory, "user-example.jpg");
        assumeTrue("Copy the private example to handtable/user-example.jpg to enable this test", image.isFile());
        ScanDocument.Page page = newPage(ScanDocument.Filter.AUTO);
        store.importImage(context.getContentResolver(), Uri.fromFile(image), document, page);
        Bitmap thumbnail = ScanImages.source(store.image(document, page), 0, 1000);
        try {
            CropQuad detected = ScanImages.detect(thumbnail);
            CropQuad crop = detected == null ? CropQuad.FULL : detected;
            addPage(page.crop(crop).filter(ScanImages.initialHandwritingFilter(thumbnail, crop)));
        } finally {
            thumbnail.recycle();
        }
    }

    private void addGeneratedPage(boolean table) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(1160, 780, Bitmap.Config.ARGB_8888);
        ScanDocument.Page page = newPage(ScanDocument.Filter.ORIGINAL);
        try {
            bitmap.eraseColor(Color.WHITE);
            if (table) drawPrintedTable(bitmap);
            try (FileOutputStream out = new FileOutputStream(store.image(document, page))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            addPage(page);
        } finally {
            bitmap.recycle();
        }
    }

    private static void drawPrintedTable(Bitmap bitmap) {
        int left = 80, top = 100, columnWidth = 330, rowHeight = 130;
        String[][] rows = {
                {"项目", "Code", "数量"},
                {"橙子", "FRUIT A", "28"},
                {"苹果", "FRUIT B", "105"},
                {"梨子", "FRUIT C", "36"}
        };
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(4);
        int right = left + rows[0].length * columnWidth;
        int bottom = top + rows.length * rowHeight;
        for (int column = 0; column <= rows[0].length; column++) {
            int x = left + column * columnWidth;
            canvas.drawLine(x, top, x, bottom, paint);
        }
        for (int row = 0; row <= rows.length; row++) {
            int y = top + row * rowHeight;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setTextSize(50);
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length; column++) {
                canvas.drawText(rows[row][column], left + column * columnWidth + 32,
                        top + row * rowHeight + 82, paint);
            }
        }
        canvas.setBitmap(null);
    }

    private static ScanDocument.Page newPage(ScanDocument.Filter filter) {
        return new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, filter);
    }

    private void addPage(ScanDocument.Page page) throws IOException {
        List<ScanDocument.Page> pages = new ArrayList<>(document.pages);
        pages.add(page);
        document = document.withPages(pages);
        store.save(document);
    }
}
