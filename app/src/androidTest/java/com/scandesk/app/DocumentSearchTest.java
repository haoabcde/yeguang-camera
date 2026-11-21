package com.scandesk.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class DocumentSearchTest {
    @Test public void handwrittenTablesAreSearchableAfterCorrectionsWithoutJoiningAdjacentCells() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        File root = new File(app.getCacheDir(), ScanStore.newId());
        ScanStore store = new ScanStore(new ContextWrapper(app) {
            @Override public File getNoBackupFilesDir() { return root; }
        });
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
        ScanDocument doc = store.newDocument().renamed("手写表搜索").withPages(List.of(page));
        store.save(doc);
        OfficeTable.Cell corrected = new OfficeTable.Cell(0, 0, 1, 1, "00987");
        OfficeTable.Cell next = new OfficeTable.Cell(0, 1, 1, 1, "65");
        OfficeTable table = new OfficeTable("登记", 1, 1, 2, true, List.of(corrected, next));
        ScanExport.Progress pass = (done, total) -> {};
        try {
            store.saveReview(doc, ScanRecognition.HAND_TABLE, new ScanRecognition.TableDraft(List.of(table), List.of()).json());
            DocumentSearch.Results found = DocumentSearch.find(store, app.getAssets(), "００９８７", false, pass);
            assertEquals(1, found.hits.size());
            assertEquals(page.id, found.hits.get(0).pageId);
            assertTrue(found.hits.get(0).context.contains("手写表格"));
            DocumentSearch.Hit secondCell = DocumentSearch.find(store, app.getAssets(), "65", false, pass).hits.get(0);
            assertEquals(ScanRecognition.HAND_TABLE, secondCell.reviewKey);
            assertEquals(0, secondCell.tableIndex);
            assertEquals(0, secondCell.row);
            assertEquals(1, secondCell.column);
            assertTrue(DocumentSearch.find(store, app.getAssets(), "98765", false, pass).hits.isEmpty());
            corrected.text = "00981";
            corrected.reviewed = true;
            store.saveReview(doc, ScanRecognition.HAND_TABLE, new ScanRecognition.TableDraft(List.of(table), List.of()).json());
            assertTrue(DocumentSearch.find(store, app.getAssets(), "00987", false, pass).hits.isEmpty());
            assertEquals(1, DocumentSearch.find(store, app.getAssets(), "00981", false, pass).hits.size());
            OfficeTable printed = new OfficeTable("印刷", 1, 1, 1, false,
                    List.of(new OfficeTable.Cell(0, 0, 1, 1, "00981")));
            store.saveReview(doc, ScanRecognition.EXCEL, new ScanRecognition.TableDraft(List.of(printed), List.of()).json());
            assertEquals(1, DocumentSearch.find(store, app.getAssets(), "00981", false, pass).hits.size());
            store.save(doc.withPages(List.of()));
            assertTrue(DocumentSearch.find(store, app.getAssets(), "00981", false, pass).hits.isEmpty());
        } finally { store.delete(doc); }
    }

    @Test public void selectedScanPagesRequireMatchingOrderCountAndSourcePositions() {
        ScanDocument.Page second = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.ORIGINAL);
        ScanDocument.Page fourth = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.ORIGINAL);
        List<ScanDocument.Page> pages = List.of(second, fourth);
        String mapping = second.id + ":2," + fourth.id + ":4";
        assertTrue(MainActivity.matchesScanSubset(pages, pages, mapping, mapping));
        assertFalse(MainActivity.matchesScanSubset(List.of(fourth, second), pages, mapping, mapping));
        assertFalse(MainActivity.matchesScanSubset(List.of(fourth), pages, mapping, mapping));
        assertFalse(MainActivity.matchesScanSubset(pages, pages, second.id + ":1," + fourth.id + ":4", mapping));
        assertFalse(MainActivity.matchesScanSubset(pages, pages, null, mapping));
    }

    @Test public void documentTitleTracksOnlyItsChosenSourceAndSurvivesReopen() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        File root = new File(app.getCacheDir(), ScanStore.newId());
        ScanStore store = new ScanStore(new ContextWrapper(app) {
            @Override public File getNoBackupFilesDir() { return root; }
        });
        ScanDocument blank = store.newDocument();
        ScanDocument doc = blank.titleFromImage("IMG_20261120_161806.jpg");
        assertSame(blank, doc);
        assertEquals("十一月采购单", blank.titleFromImage("十一月采购单.png").name);
        doc = doc.titleFromDraft(ScanRecognition.HANDWRITING, List.of("  会议记录  \n第二行","第二页"));
        try {
            store.save(doc);
            doc = store.load(doc.id);
            assertEquals("会议记录", doc.name);
            assertEquals(0, doc.revision);
            assertSame(doc, doc.titleFromDraft(ScanRecognition.WORD, List.of("另一种识别")));
            assertEquals("修正后的标题", doc.titleFromDraft(ScanRecognition.HANDWRITING, List.of("修正后的标题")).name);
            doc = doc.renamed("我自己的名称").filed(ScanDocument.Folder.MEETINGS);
            store.save(doc);
            doc = store.load(doc.id);
            assertSame(doc, doc.titleFromDraft(ScanRecognition.HANDWRITING, List.of("下次的文字")));
        } finally {
            store.delete(doc);
        }
    }

    @Test public void opensMatchingPageAndHonorsCorrectionsAndRevision() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        File root = new File(app.getCacheDir(), ScanStore.newId());
        ScanStore store = new ScanStore(new ContextWrapper(app) {
            @Override public File getNoBackupFilesDir() { return root; }
        });
        ScanDocument.Page missing = new ScanDocument.Page(ScanStore.newId(), 0,
                CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
        ScanDocument.Page receipt = new ScanDocument.Page(ScanStore.newId(), 0,
                CropQuad.FULL, ScanDocument.Filter.AUTO);
        ScanDocument doc = store.newDocument().renamed("十一月费用报销").withPages(List.of(missing, receipt));
        store.save(doc);
        try {
            Bitmap page = Bitmap.createBitmap(1400, 600, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(page);
            canvas.drawColor(Color.WHITE);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(Color.BLACK);
            ink.setTextSize(62);
            canvas.drawText("采购费用核对", 80, 170, ink);
            canvas.drawText("Invoice 00742", 80, 295, ink);
            try (FileOutputStream out = new FileOutputStream(store.image(doc, receipt))) {
                assertTrue(page.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally {
                page.recycle();
            }
            ScanExport.Progress pass = (done, total) -> {};
            DocumentSearch.Results names = DocumentSearch.find(store, app.getAssets(), "报销", false, pass);
            assertEquals(2, names.missingPages);
            assertEquals(doc.id, names.hits.get(0).documentId);
            DocumentSearch.Results ocr = DocumentSearch.find(store, app.getAssets(), "００７４２", true, pass);
            assertEquals(1, ocr.missingPages);
            assertEquals(1, ocr.hits.size());
            assertEquals(receipt.id, ocr.hits.get(0).pageId);
            assertNull(ocr.hits.get(0).reviewKey);
            assertTrue(ocr.hits.get(0).context.startsWith("第 2 页"));
            assertEquals(1, DocumentSearch.find(store, app.getAssets(), "invoice", false, pass).hits.size());

            store.saveReview(doc, ScanRecognition.WORD,
                    new ScanRecognition.WordDraft(List.of("", "修 订 编 号 00127")).json());
            assertTrue(DocumentSearch.find(store, app.getAssets(), "00742", false, pass).hits.isEmpty());
            assertEquals(receipt.id, DocumentSearch.find(store, app.getAssets(), "修订编号", false, pass).hits.get(0).pageId);
            store.saveReview(doc, ScanRecognition.HANDWRITING,
                    new ScanRecognition.WordDraft(List.of("", "手记约定 00127")).json());
            assertEquals(1, DocumentSearch.find(store, app.getAssets(), "00127", false, pass).hits.size());
            DocumentSearch.Hit hand = DocumentSearch.find(store, app.getAssets(), "手记约定", false, pass).hits.get(0);
            assertEquals(receipt.id, hand.pageId);
            assertEquals(ScanRecognition.HANDWRITING, hand.reviewKey);
            DocumentSearch.Hit printed = DocumentSearch.find(store, app.getAssets(), "修订编号", false, pass).hits.get(0);
            assertEquals(receipt.id, printed.pageId);
            assertEquals(ScanRecognition.WORD, printed.reviewKey);
            OfficeTable.Cell code = new OfficeTable.Cell(0, 0, 1, 1, "A007450");
            OfficeTable.Cell next = new OfficeTable.Cell(0, 1, 1, 1, "01");
            OfficeTable table = new OfficeTable("物品", 2, 1, 2, false, List.of(code, next));
            store.saveReview(doc, ScanRecognition.EXCEL,
                    new ScanRecognition.TableDraft(List.of(table), List.of(1)).json());
            DocumentSearch.Hit hit = DocumentSearch.find(store, app.getAssets(), "A007450", false, pass).hits.get(0);
            assertEquals(receipt.id, hit.pageId);
            assertTrue(hit.context.contains("表格"));
            assertTrue(DocumentSearch.find(store, app.getAssets(), "45001", false, pass).hits.isEmpty());
            code.text = "A007460";
            store.saveReview(doc, ScanRecognition.EXCEL,
                    new ScanRecognition.TableDraft(List.of(table), List.of(1)).json());
            assertTrue(DocumentSearch.find(store, app.getAssets(), "A007450", false, pass).hits.isEmpty());
            assertEquals(1, DocumentSearch.find(store, app.getAssets(), "A007460", false, pass).hits.size());
            store.save(doc.withPages(List.of(missing)));
            assertTrue(DocumentSearch.find(store, app.getAssets(), "00127", false, pass).hits.isEmpty());
        } finally {
            store.delete(doc);
            new File(root, "scans").delete();
            root.delete();
        }
    }
}
