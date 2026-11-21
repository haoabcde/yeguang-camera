package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

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
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class TableStructureTest {
    @Test public void decodesTwoLevelHeadersWithRowAndColumnSpansAndEmptyCells() {
        List<String> tags = Arrays.asList("<thead>", "<tr>", "<td", " rowspan=\"2\"", ">", "</td>",
                "<td", " colspan=\"2\"", ">", "</td>", "</tr>", "<tr>", "<td></td>", "<td></td>",
                "</tr>", "</thead>", "<tbody>", "<tr>", "<td></td>", "<td></td>", "<td></td>", "</tr>", "</tbody>");
        List<RectF> boxes = new ArrayList<>();
        for (int i = 0; i < 7; i++) boxes.add(new RectF(i, i, i + 5, i + 5));
        TableStructureOcr.Result result = TableStructureOcr.decode(tags, boxes, .99f);
        assertNotNull(result);
        assertEquals(3, result.rows);
        assertEquals(3, result.columns);
        assertEquals(7, result.cells.size());
        assertEquals(2, result.cells.get(0).height);
        assertEquals(2, result.cells.get(1).width);
        assertEquals(1, result.cells.get(2).row);
        assertEquals(1, result.cells.get(2).column);
        assertEquals(2, result.cells.get(3).column);
    }

    @Test public void rejectsRaggedOverlappingAndUnmatchedStructures() {
        List<RectF> boxes = new ArrayList<>();
        for (int i = 0; i < 4; i++) boxes.add(new RectF(0, 0, 10, 10));
        assertNull(TableStructureOcr.decode(Arrays.asList("<tr>", "<td></td>", "<td></td>", "</tr>",
                "<tr>", "<td></td>", "</tr>"), boxes.subList(0, 3), 1));
        assertNull(TableStructureOcr.decode(Arrays.asList("<tr>", "<td", " rowspan=\"3\"", ">", "</td>",
                "<td></td>", "</tr>", "<tr>", "<td></td>", "</tr>"), boxes.subList(0, 3), 1));
        assertNull(TableStructureOcr.decode(Arrays.asList("<tr>", "<td></td>", "<td></td>", "</tr>",
                "<tr>", "<td></td>", "<td></td>", "</tr>"), boxes.subList(0, 3), 1));
    }

    @Test public void separatesOnlyVerifiedSymbolGuttersAndKeepsTheOriginalString() {
        String original = "Name   0012";
        List<String> symbols = Arrays.asList("N", "a", "m", "e", "0", "0", "1", "2");
        List<Rect> boxes = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) boxes.add(new Rect(i < 4 ? i * 9 : 150 + (i - 4) * 9,
                10, (i < 4 ? i * 9 : 150 + (i - 4) * 9) + 8, 24));
        List<TableVision.ElementPart> parts = TableVision.splitElement(original, symbols, boxes);
        assertEquals(2, parts.size());
        assertEquals("Name   ", parts.get(0).text);
        assertEquals("0012", parts.get(1).text);
        assertEquals(original, parts.get(0).text + parts.get(1).text);
        for (TableVision.ElementPart part : parts) assertEquals(part.text, original.substring(part.start, part.end));
        assertEquals(150, parts.get(1).bounds.left);
        assertTrue(TableVision.splitElement("Name   0013", symbols, boxes).isEmpty());
        assertTrue(TableVision.splitElement("0012", Arrays.asList("0", "0", "1", "2"), boxes.subList(4, 8)).isEmpty());
        List<Rect> normal = Arrays.asList(new Rect(0, 10, 8, 24), new Rect(9, 10, 17, 24),
                new Rect(18, 10, 26, 24), new Rect(31, 10, 39, 24));
        assertTrue(TableVision.splitElement("abc d", Arrays.asList("a", "b", "c", "d"), normal).isEmpty());
    }

    @Test public void columnEvidenceRequiresTwoAlignedRowsAndSharedWhitespace() {
        List<Rect> first = Arrays.asList(new Rect(70, 20, 150, 50), new Rect(420, 20, 500, 50), new Rect(770, 20, 920, 50));
        List<Rect> second = Arrays.asList(new Rect(70, 100, 160, 130), new Rect(420, 100, 520, 130), new Rect(770, 100, 850, 130));
        assertTrue(TableVision.stableColumnGutters(Arrays.asList(first, second), 3, 30));
        assertFalse(TableVision.stableColumnGutters(Arrays.asList(first), 3, 30));
        List<Rect> prose = Arrays.asList(new Rect(70, 100, 410, 130), new Rect(420, 100, 760, 130), new Rect(770, 100, 900, 130));
        assertFalse(TableVision.stableColumnGutters(Arrays.asList(first, prose), 3, 30));
        List<Rect> indented = Arrays.asList(new Rect(160, 100, 240, 130), new Rect(510, 100, 590, 130), new Rect(860, 100, 940, 130));
        assertFalse(TableVision.stableColumnGutters(Arrays.asList(first, indented), 3, 30));
    }

    @Test public void preprocessingUsesBgrAndNormalizedZeroPadding() throws Exception {
        Bitmap image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        try {
            image.eraseColor(Color.RED);
            float[] values = TableStructureOcr.preprocess(image, new Rect(0, 0, 200, 100));
            int plane = TableStructureOcr.EDGE * TableStructureOcr.EDGE;
            assertEquals(-.485f / .229f, values[0], .00001f);
            assertEquals(-.456f / .224f, values[plane], .00001f);
            assertEquals((1 - .406f) / .225f, values[2 * plane], .00001f);
            assertEquals(0, values[300 * TableStructureOcr.EDGE], 0);
            assertEquals(0, values[plane + 300 * TableStructureOcr.EDGE], 0);
        } finally { image.recycle(); }
    }

    @Test public void smallTableInputIsBoundedIndependentAndCancellationSafe() throws Exception {
        Bitmap source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        Bitmap ready = null;
        Bitmap large = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888);
        try {
            source.eraseColor(Color.RED);
            ready = ScanRecognition.Reader.tableInput(source);
            assertTrue(ready != source);
            assertEquals(1600, ready.getWidth());
            assertEquals(800, ready.getHeight());
            assertFalse(source.isRecycled());
            assertEquals(Color.RED, source.getPixel(20, 20));
            assertSame(large, ScanRecognition.Reader.tableInput(large));
            Thread.currentThread().interrupt();
            try {
                ScanRecognition.Reader.tableInput(source);
                throw new AssertionError("Cancelled preprocessing must stop");
            } catch (java.io.IOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(source.isRecycled());
            } finally { Thread.interrupted(); }
        } finally {
            if (ready != null && ready != source) ready.recycle();
            source.recycle();
            large.recycle();
        }
    }

    @Test public void ordinaryParagraphDoesNotBecomeATable() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap image = Bitmap.createBitmap(1100, 950, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(34);
        String[] lines = {"The scanned page contains ordinary paragraphs.",
                "This sentence continues with several normal words.",
                "Keep the reading order and preserve every sentence.",
                "A paragraph is not a spreadsheet or a table."};
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 55, 150 + i * 72, paint);
        canvas.setBitmap(null);
        try (ScanRecognition.Reader reader = new ScanRecognition.Reader(context.getAssets())) {
            assertTrue(reader.tables(image, 1).isEmpty());
        }
    }

    @Test public void bundledModelReadsTableCropAndReturnsSourceCoordinates() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap image;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("flat-ledger.png")) {
            image = BitmapFactory.decodeStream(input);
        }
        assertNotNull(image);
        try (TableStructureOcr model = new TableStructureOcr(context.getAssets())) {
            Rect crop = new Rect(81, 407, 1121, 1241);
            TableStructureOcr.Result result = model.recognize(image, crop);
            assertNotNull(result);
            assertEquals(13, result.rows);
            assertEquals(6, result.columns);
            assertEquals(78, result.cells.size());
            assertTrue(result.confidence > .95f);
            for (TableStructureOcr.Cell cell : result.cells) {
                assertTrue(cell.bounds.left >= crop.left && cell.bounds.right <= crop.right);
                assertTrue(cell.bounds.top >= crop.top && cell.bounds.bottom <= crop.bottom);
            }
            assertTrue(result.cells.get(0).bounds.top < 470);
            assertTrue(result.cells.get(77).bounds.top > 1100);
        } finally { image.recycle(); }
    }

    @Test public void separatesBorderlessRegionsAndKeepsNotesAndLiteralNumbers() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap image = Bitmap.createBitmap(1100, 1500, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(36);
        paint.setColor(Color.BLACK);
        String[][] first = {{"Code", "Item", "Amount"}, {"0012", "Apple", "4.50"},
                {"0013", "Pear", ""}, {"0014", "", "-7.00"}};
        String[][] second = {{"Ref", "Value"}, {"0020", "-12.50"}, {"0021", "3.00"}};
        for (int r = 0; r < first.length; r++) for (int c = 0; c < first[r].length; c++)
            canvas.drawText(first[r][c], 70 + 350 * c, 180 + 80 * r, paint);
        for (int r = 0; r < second.length; r++) for (int c = 0; c < second[r].length; c++)
            canvas.drawText(second[r][c], 70 + 350 * c, 850 + 80 * r, paint);
        canvas.drawText("Note: check totals before sending.", 70, 1350, paint);
        canvas.setBitmap(null);
        File artifacts = new File(context.getExternalFilesDir(null), "acceptance");
        assertTrue(artifacts.isDirectory() || artifacts.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(artifacts, "table-multi-input.png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        try (ScanRecognition.Reader reader = new ScanRecognition.Reader(context.getAssets())) {
            List<OfficeTable> tables = reader.tables(image, 1);
            JSONArray result = new JSONArray();
            for (OfficeTable table : tables) result.put(table.json());
            Files.write(new File(artifacts, "table-multi-results.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
            assertEquals(result.toString(), 3, tables.size());
            OfficeTable one = tables.get(0), two = tables.get(1), notes = tables.get(2);
            assertEquals(result.toString(), 4, one.rows);
            assertEquals(result.toString(), 3, one.columns);
            assertEquals("0012", one.cell(1, 0).text);
            assertEquals("", one.cell(2, 2).text);
            assertEquals("", one.cell(3, 1).text);
            assertEquals("-7.00", one.cell(3, 2).text.replace('−', '-'));
            assertEquals(3, two.rows);
            assertEquals(2, two.columns);
            assertEquals("0020", two.cell(1, 0).text);
            assertEquals("-12.50", two.cell(1, 1).text.replace('−', '-'));
            assertEquals(1, notes.columns);
            assertTrue(notes.cells.get(0).text.contains("check totals"));
            assertNotNull(one.cell(1, 0).sourceBounds);
        }
    }

    @Test public void fallbackKeepsSingleCellHeadingAndTailWhenStructureAssetsAreUnavailable() throws Exception {
        Bitmap image = Bitmap.createBitmap(1100, 1500, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(36);
        paint.setColor(Color.BLACK);
        canvas.drawText("Ledger summary", 70, 120, paint);
        String[][] rows = {{"Code", "Value"}, {"0012", "10.50"}, {"0013", "-4.00"}};
        for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length; c++)
            canvas.drawText(rows[r][c], 70 + 450 * c, 200 + r * 80, paint);
        canvas.drawText("End", 70, 700, paint);
        canvas.setBitmap(null);
        // The instrumentation APK intentionally has no production ONNX assets.
        try (ScanRecognition.Reader reader = new ScanRecognition.Reader(
                InstrumentationRegistry.getInstrumentation().getContext().getAssets())) {
            List<OfficeTable> tables = reader.tables(image, 1);
            JSONArray result = new JSONArray();
            for (OfficeTable table : tables) result.put(table.json());
            assertEquals(result.toString(), 2, tables.size());
            assertEquals(3, tables.get(0).rows);
            assertEquals(2, tables.get(0).columns);
            assertEquals("0012", tables.get(0).cell(1, 0).text);
            assertEquals("-4.00", tables.get(0).cell(2, 1).text.replace('−', '-'));
            String preserved = String.join("\n", tables.get(1).cells.stream().map(cell -> cell.text).toArray(String[]::new));
            assertTrue(preserved, preserved.contains("Ledger summary") && preserved.contains("End"));
            assertNotNull(tables.get(1).cells.get(0).sourceBounds);
        }
    }

    /** Optional local-only public-image regression. No third-party images or ground truth enter the APK. */
    @Test public void externalFrozenPublicTables() throws Exception {
        String directory = InstrumentationRegistry.getArguments().getString("tableFixtureDir");
        Assume.assumeTrue("Pass tableFixtureDir to run external public table regressions", directory != null);
        Context context = ApplicationProvider.getApplicationContext();
        File root = new File(directory);
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(root, "frozen.json").toPath()), StandardCharsets.UTF_8));
        JSONArray cases = manifest.getJSONArray("cases"), results = new JSONArray();
        TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try (TableStructureOcr model = new TableStructureOcr(context.getAssets());
                ScanRecognition.Reader reader = new ScanRecognition.Reader(context.getAssets())) {
            for (int i = 0; i < cases.length(); i++) {
                JSONObject item = cases.getJSONObject(i);
                File input = new File(root, item.getString("file"));
                assertEquals(item.getString("sha256"), hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input.toPath()))));
                Bitmap image = BitmapFactory.decodeFile(input.getPath());
                assertNotNull(image);
                long start = android.os.SystemClock.elapsedRealtime();
                TableStructureOcr.Result predicted = model.recognize(image, new Rect(0, 0, image.getWidth(), image.getHeight()));
                JSONObject record = new JSONObject().put("file", input.getName()).put("sha256", item.getString("sha256"));
                JSONArray structure = new JSONArray();
                if (predicted != null) {
                    record.put("rows", predicted.rows).put("columns", predicted.columns).put("confidence", predicted.confidence);
                    for (TableStructureOcr.Cell cell : predicted.cells) structure.put(new JSONArray().put(cell.row)
                            .put(cell.column).put(cell.height).put(cell.width).put(cell.bounds.left).put(cell.bounds.top)
                            .put(cell.bounds.right).put(cell.bounds.bottom));
                }
                record.put("structure", structure);
                Text raw = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 60, TimeUnit.SECONDS);
                record.put("ocr", ocr(raw));
                Bitmap scaled = ScanRecognition.Reader.tableInput(image);
                try {
                    Text larger = Tasks.await(recognizer.process(InputImage.fromBitmap(scaled, 0)), 60, TimeUnit.SECONDS);
                    record.put("scaledOcr", ocr(larger)).put("scaledWidth", scaled.getWidth()).put("scaledHeight", scaled.getHeight());
                } finally { if (scaled != image) scaled.recycle(); }
                long nativeStart = android.os.SystemClock.elapsedRealtime();
                List<OfficeTable> nativeTables = reader.tables(image.copy(Bitmap.Config.ARGB_8888, false), 1, false);
                JSONArray nativeValues = new JSONArray();
                for (OfficeTable table : nativeTables) nativeValues.put(table.json());
                record.put("nativeTables", nativeValues).put("nativeElapsedMs", android.os.SystemClock.elapsedRealtime() - nativeStart);
                long appStart = android.os.SystemClock.elapsedRealtime();
                List<OfficeTable> restored = reader.tables(image, 1);
                JSONArray tables = new JSONArray();
                for (OfficeTable table : restored) tables.put(table.json());
                record.put("tables", tables).put("appElapsedMs", android.os.SystemClock.elapsedRealtime() - appStart)
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start);
                results.put(record);
                Files.write(new File(root, "android-results.json").toPath(), results.toString(2).getBytes(StandardCharsets.UTF_8));
                if (!restored.isEmpty()) try (FileOutputStream output = new FileOutputStream(new File(root,
                        input.getName().replace(".png", ".xlsx")))) { OfficeExport.excel(restored, output); }
            }
        } finally { recognizer.close(); }
    }

    private static JSONArray ocr(Text raw) throws Exception {
        JSONArray detected = new JSONArray();
        for (Text.TextBlock block : raw.getTextBlocks()) for (Text.Line line : block.getLines())
            for (Text.Element element : line.getElements()) {
                Rect bounds = element.getBoundingBox();
                if (bounds == null) continue;
                JSONArray symbols = new JSONArray();
                for (Text.Symbol symbol : element.getSymbols()) {
                    Rect box = symbol.getBoundingBox();
                    if (box != null) symbols.put(new JSONArray().put(symbol.getText()).put(box.left)
                            .put(box.top).put(box.right).put(box.bottom));
                }
                detected.put(new JSONArray().put(element.getText()).put(bounds.left).put(bounds.top)
                        .put(bounds.right).put(bounds.bottom).put(symbols));
            }
        return detected;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
