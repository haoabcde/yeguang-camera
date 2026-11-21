package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

@RunWith(AndroidJUnit4.class)
public final class WordGridTest {
    @Test public void exportsPrintedRowsInColumnOrderAndAnchorsTheEntireNumberedRow() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ScanStore store = new ScanStore(context);
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.AUTO);
        ScanDocument document = store.newDocument().withPages(List.of(page));
        store.save(document);
        try {
            try (InputStream source = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                    .open("flat-ledger.png"); FileOutputStream file = new FileOutputStream(store.image(document, page))) {
                byte[] buffer = new byte[8192];
                for (int n; (n = source.read(buffer)) != -1;) file.write(buffer, 0, n);
            }
            ScanRecognition.WordDraft draft = ScanRecognition.word(context.getAssets(), store, document, (i, n) -> {});
            String content = draft.pages.get(0);
            List<String> lines = Arrays.asList(content.split("\n"));
            String first = lines.stream().filter(line -> line.contains("001") && line.contains("A4")
                    && line.contains("1,200.00")).findFirst().orElse(null);
            assertNotNull("First row should retain item code, name and amount together: " + content, first);
            assertTrue(first.indexOf("001") < first.indexOf("A4"));
            assertTrue(first.indexOf("A4") < first.indexOf("1,200.00"));
            String last = lines.stream().filter(line -> line.contains("012") && line.contains("20.00"))
                    .findFirst().orElse(null);
            assertNotNull(last);
            assertEquals("Each of the 12 data rows occupies one line", 11, lines.indexOf(last) - lines.indexOf(first));
            assertTrue(content.indexOf("采购清单") >= 0 && content.indexOf("采购清单") < content.indexOf(first));
            assertTrue(content.indexOf("备注") > content.indexOf(last));
            ScanRecognition.NumberLine anchor = draft.numberLines.get(0).stream().filter(item -> first.equals(item.text))
                    .findFirst().orElse(null);
            assertNotNull(anchor);
            assertTrue(anchor.bounds.width() > .65f && anchor.bounds.height() > 0 && anchor.bounds.height() < .06f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OfficeExport.word(draft.pages, bytes);
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (!entry.getName().equals("word/document.xml")) continue;
                    org.w3c.dom.NodeList runs = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(zip).getElementsByTagName("w:t");
                    for (int i = 0; i < runs.getLength(); i++) {
                        if (first.equals(runs.item(i).getTextContent())) return;
                    }
                    break;
                }
            }
            throw new AssertionError("DOCX omitted the joined first row");
        } finally {
            store.delete(document);
        }
    }

    @Test public void alignedTextWithoutPrintedRulesIsNotAWordGrid() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(800, 720, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(26);
            for (int i = 0; i < 5; i++) {
                canvas.drawText("Section " + i, 30, 85 + i * 100, paint);
                canvas.drawText("Chapter " + i, 470, 85 + i * 100, paint);
            }
            canvas.setBitmap(null);
            assertTrue(TableVision.ruledRows(bitmap).isEmpty());
        } finally {
            bitmap.recycle();
        }
    }
}
