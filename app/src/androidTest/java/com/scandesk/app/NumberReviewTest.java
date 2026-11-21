package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Xml;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RunWith(AndroidJUnit4.class)
public final class NumberReviewTest {
    private static final int WIDTH = 1000, HEIGHT = 1200;
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test public void opensLegacyTextWithoutInventingLocations() throws Exception {
        List<String> expected = List.of("老单据\n编号 00042", "原金额 -3.75");
        JSONObject legacy = new JSONObject().put("pages", new JSONArray(expected));
        ScanRecognition.WordDraft old = ScanRecognition.WordDraft.from(legacy);
        assertEquals(expected, old.pages);
        assertTrue(old.numberLines.stream().allMatch(List::isEmpty));
        old.pages.set(1, "校对后金额 -3.50");
        ScanRecognition.WordDraft saved = ScanRecognition.WordDraft.from(old.json());
        assertEquals("老单据\n编号 00042", saved.pages.get(0));
        assertEquals("校对后金额 -3.50", saved.pages.get(1));
        assertTrue(saved.numberLines.stream().allMatch(List::isEmpty));
    }

    @Test public void keepsOcrLocationsAndLeadingZeroesWhenOnePageAmountIsCorrected() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ScanStore store = new ScanStore(context);
        ScanDocument document = store.newDocument();
        store.save(document);
        try {
            ScanDocument.Page first = addPage(store, document, "Invoice", 500);
            ScanDocument.Page second = addPage(store, document, "Receipt", 900);
            ScanDocument ordered = document.withPages(List.of(first, second));
            store.save(ordered);
            ScanRecognition.WordDraft scanned = ScanRecognition.word(context.getAssets(), store, ordered,
                    (done, total) -> {});
            assertEquals(2, scanned.numberLines.size());
            assertEquals(2, scanned.pages.size());
            anchorAt(scanned, 0, "00042", 295);
            anchorAt(scanned, 0, "128.75", 500);
            RectF expectedBox = anchorAt(scanned, 1, "128.75", 900).bounds;

            store.saveReview(ordered, ScanRecognition.WORD, scanned.json());
            ScanRecognition.WordDraft reopened = ScanRecognition.WordDraft.from(
                    store.loadReview(ordered, ScanRecognition.WORD));
            ScanRecognition.NumberLine originalAmount = anchorAt(reopened, 1, "128.75", 900);
            assertEquals(expectedBox, originalAmount.bounds);
            String amended = reopened.pages.get(1).replace("128.75", "-138.50");
            assertTrue(amended, amended.contains("-138.50"));
            reopened.pages.set(1, amended);
            originalAmount.reviewedText = originalAmount.text.replace("128.75", "-138.50");
            store.saveReview(ordered, ScanRecognition.WORD, reopened.json());
            ScanRecognition.WordDraft corrected = ScanRecognition.WordDraft.from(
                    store.loadReview(ordered, ScanRecognition.WORD));
            assertEquals(expectedBox, anchorAt(corrected, 1, "128.75", 900).bounds);
            assertTrue(anchorAt(corrected, 1, "128.75", 900).reviewedText.contains("-138.50"));

            File dir = context.getExternalFilesDir("acceptance");
            assertNotNull(dir);
            File exported = new File(dir, "number-review.docx");
            try (FileOutputStream out = new FileOutputStream(exported)) {
                OfficeExport.word(corrected.pages, out);
            }
            List<String> xmlPages = readWordPages(exported);
            assertEquals(2, xmlPages.size());
            for (String text : xmlPages) assertTrue(text, text.contains("00042"));
            assertTrue(xmlPages.get(0), xmlPages.get(0).contains("128.75"));
            assertFalse(xmlPages.get(0), xmlPages.get(0).contains("-138.50"));
            assertTrue(xmlPages.get(1), xmlPages.get(1).contains("-138.50"));
            assertFalse(xmlPages.get(1), xmlPages.get(1).contains("128.75"));
        } finally {
            store.delete(document);
        }
    }

    private static ScanDocument.Page addPage(ScanStore store, ScanDocument document,
            String heading, int amountBaseline) throws Exception {
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.ORIGINAL);
        Bitmap photo = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(store.image(document, page))) {
            Canvas canvas = new Canvas(photo);
            canvas.drawColor(Color.WHITE);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(Color.BLACK);
            ink.setTextSize(54);
            ink.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            canvas.drawText(heading, 100, 146, ink);
            canvas.drawText("Order 00042", 100, 295, ink);
            canvas.drawText("Amount 128.75", 100, amountBaseline, ink);
            assertTrue(photo.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            photo.recycle();
        }
        return page;
    }

    private static ScanRecognition.NumberLine anchorAt(ScanRecognition.WordDraft draft,
            int page, String number, int baseline) {
        for (ScanRecognition.NumberLine line : draft.numberLines.get(page)) {
            if (!line.text.contains(number)) continue;
            RectF box = line.bounds;
            assertTrue(box.toString(), box.left >= 0 && box.top >= 0 && box.right <= 1 && box.bottom <= 1);
            assertTrue(box.toString(), box.width() > .08f && box.height() > .015f);
            float center = box.centerY() * HEIGHT;
            assertTrue("line outside original glyph row: " + box, center > baseline - 55 && center < baseline + 3);
            return line;
        }
        throw new AssertionError("missing OCR source box for " + number + " in " + draft.pages.get(page));
    }

    private static List<String> readWordPages(File exported) throws Exception {
        List<String> pages = new ArrayList<>();
        try (ZipFile zip = new ZipFile(exported)) {
            ZipEntry part = zip.getEntry("word/document.xml");
            assertNotNull(part);
            try (InputStream in = zip.getInputStream(part)) {
                XmlPullParser xml = Xml.newPullParser();
                xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                xml.setInput(in, "UTF-8");
                StringBuilder page = new StringBuilder();
                for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                    if (event != XmlPullParser.START_TAG || !WORD_NS.equals(xml.getNamespace())) continue;
                    if ("pageBreakBefore".equals(xml.getName())) {
                        pages.add(page.toString());
                        page.setLength(0);
                    } else if ("t".equals(xml.getName())) {
                        page.append(xml.nextText()).append('\n');
                    }
                }
                pages.add(page.toString());
            }
        }
        return pages;
    }
}
