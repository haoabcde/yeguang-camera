package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

@RunWith(AndroidJUnit4.class)
public final class HandwrittenTableLayoutTest {
    @Test public void infersColumnCountFromUnequalSourcePositionsWithSpanningHeadings() throws Exception {
        for (int count = 1; count <= 6; count++) {
            List<HandwritingOcr.Line> lines = new ArrayList<>();
            lines.add(box("整页标题", .04f, .02f, .85f, .035f));
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < count; col++) {
                    // Unequal whitespace and slight indentation: no equal-width photo slices.
                    float x = .04f + col * .13f + col * col * .004f + (row % 2) * .003f;
                    lines.add(box(row + "/" + col, x, .16f + row * .12f, .06f, .035f));
                }
            }
            OfficeTable table = HandwrittenTableLayout.create(lines, 1, "");
            assertEquals("source columns " + count, count, table.columns);
            assertEquals(lines.size(), table.cells.stream().filter(cell -> cell.sourceBounds != null).count());
            for (OfficeTable.Cell cell : table.cells) {
                if (cell.text.matches("[0-9]/[0-9]")) assertEquals(Integer.parseInt(cell.text.substring(2)), cell.column);
            }
        }
    }

    @Test public void reflowPreservesCorrectionsReviewStatusEmptyInkAndManualAdditions() throws Exception {
        OfficeTable initial = HandwrittenTableLayout.create(List.of(
                box("001", .08f, .1f, .12f, .03f), box("009", .62f, .1f, .12f, .03f),
                box("002", .08f, .3f, .12f, .03f), box("", .62f, .3f, .12f, .03f)), 1, "");
        OfficeTable.Cell corrected = initial.cell(0, 0);
        corrected.text = "0001";
        corrected.reviewed = true;
        corrected.needsReview = true;
        corrected.alternative = "001";
        List<OfficeTable.Cell> cells = new ArrayList<>(initial.cells);
        cells.add(new OfficeTable.Cell(initial.rows, 0, 1, 1, "手工补充"));
        OfficeTable original = new OfficeTable("校对内容", 1, initial.rows + 1, 2, true, cells);
        String snapshot = original.json().toString();
        OfficeTable changed = HandwrittenTableLayout.relayout(original, 3);
        assertEquals(3, changed.columns);
        OfficeTable restored = HandwrittenTableLayout.relayout(OfficeTable.from(changed.json()), 0);
        assertEquals(2, restored.columns);
        assertEquals(snapshot, original.json().toString());
        assertEquals(4, restored.cells.stream().filter(cell -> cell.sourceBounds != null).count());
        OfficeTable.Cell saved = restored.cells.stream().filter(cell -> cell.text.equals("0001")).findFirst().get();
        assertTrue(saved.reviewed);
        assertTrue(saved.needsReview);
        assertEquals("001", saved.alternative);
        assertEquals(OfficeTable.CellType.TEXT, saved.type);
        assertEquals(corrected.sourceBounds, saved.sourceBounds);
        assertEquals(1, restored.cells.stream().filter(cell -> "手工补充".equals(cell.text)).count());
        assertEquals(1, restored.cells.stream().filter(cell -> cell.sourceBounds != null && cell.text.isEmpty()).count());
    }

    @Test public void keepsRepeatedTextInItsColumnsAndTheIsolatedLastRow() throws Exception {
        HandwritingOcr.Line tail = line("末项", .72f, .87f);
        OfficeTable table = HandwrittenTableLayout.create(List.of(
                tail, line("重复", .72f, .10f), line("重复", .09f, .29f),
                line("乙", .42f, .30f), line("重复", .09f, .11f),
                line("丙", .72f, .30f), line("甲", .42f, .10f)), 2, "记录");

        assertEquals(2, table.page);
        assertEquals(3, table.columns);
        assertEquals(3, table.rows);
        assertEquals("重复", table.cell(0, 0).text);
        assertEquals("甲", table.cell(0, 1).text);
        assertEquals("重复", table.cell(0, 2).text);
        assertEquals("重复", table.cell(1, 0).text);
        assertEquals("乙", table.cell(1, 1).text);
        assertEquals("丙", table.cell(1, 2).text);
        assertNull(table.cell(2, 0).sourceBounds);
        assertNull(table.cell(2, 1).sourceBounds);
        assertEquals("末项", table.cell(2, 2).text);
        assertEquals(tail.bounds, table.cell(2, 2).sourceBounds);
        assertEquals(7, table.cells.stream().filter(cell -> cell.sourceBounds != null).count());
    }

    @Test public void unreadableBoxSurvivesDraftRoundTripAndRemainsReviewable() throws Exception {
        RectF unreadable = new RectF(.56f, .42f, .72f, .46f);
        HandwritingOcr.Line blank = new HandwritingOcr.Line("", "", unreadable, true);
        OfficeTable initial = HandwrittenTableLayout.create(List.of(
                line("甲项", .08f, .12f), line("乙项", .56f, .12f), blank,
                new HandwritingOcr.Line("未定", "末定", new RectF(.08f, .42f, .24f, .46f), true),
                line("结束", .08f, .82f)), 1, "记录");
        OfficeTable table = OfficeTable.from(initial.json());
        OfficeTable.Cell emptyInk = table.cell(1, 1);
        assertEquals("", emptyInk.text);
        assertEquals(unreadable, emptyInk.sourceBounds);
        assertTrue(emptyInk.needsReview);
        assertTrue(emptyInk.unreviewed());
        assertEquals("末定", table.cell(1, 0).alternative);

        OfficeTable.Cell unused = table.cell(2, 1);
        assertEquals("", unused.text);
        assertNull(unused.sourceBounds);
        assertFalse(unused.unreviewed());
    }

    @Test public void workbookPreservesIdentifiersAndDateLikeValuesAsLiteralText() throws Exception {
        List<String> expected = List.of("001", "04-17", "2031-07-09", "52");
        OfficeTable table = HandwrittenTableLayout.create(List.of(
                line(expected.get(0), .11f, .10f), line(expected.get(1), .11f, .29f),
                line(expected.get(2), .11f, .48f), line(expected.get(3), .11f, .67f)), 1, "代号");
        for (OfficeTable.Cell cell : table.cells) assertEquals(OfficeTable.CellType.TEXT, cell.type);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OfficeExport.excel(List.of(OfficeTable.from(table.json())), buffer);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            ZipEntry entry;
            do {
                entry = zip.getNextEntry();
                assertNotNull("xl/worksheets/sheet1.xml", entry);
            } while (!"xl/worksheets/sheet1.xml".equals(entry.getName()));
            NodeList cells = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(zip).getElementsByTagName("c");
            assertEquals(expected.size(), cells.getLength());
            for (int i = 0; i < expected.size(); i++) {
                Element cell = (Element) cells.item(i);
                assertEquals("A" + (i + 1), cell.getAttribute("r"));
                assertEquals("inlineStr", cell.getAttribute("t"));
                assertEquals(expected.get(i), cell.getElementsByTagName("t").item(0).getTextContent());
                assertEquals(0, cell.getElementsByTagName("v").getLength());
            }
        }
    }

    @Test public void indentedValuesKeepTheirBodyColumnAndMarginalNotesGetSeparateRows() throws Exception {
        List<HandwritingOcr.Line> items = new ArrayList<>();
        for (int r = 0; r < 12; r++) {
            float y = .12f + r * .045f;
            items.add(box("l" + r, .07f, y, .12f, .022f));
            items.add(box("m" + r, .34f, y, .14f, .022f));
            items.add(box("r" + r, r < 8 ? .57f : .615f, y, .13f, .022f));
        }
        items.add(box("side", .84f, .208f, .11f, .04f)); // Same baseline as a body row.
        items.add(box("above", .79f, .03f, .12f, .025f));
        items.add(box("below", .86f, .75f, .1f, .025f));
        OfficeTable table = HandwrittenTableLayout.create(items, 1, "");
        assertEquals(3, table.columns);
        assertEquals(items.size(), table.cells.stream().filter(cell -> cell.sourceBounds != null).count());
        for (int r = 0; r < 12; r++) {
            final String label = "l" + r;
            OfficeTable.Cell left = table.cells.stream().filter(cell -> label.equals(cell.text)).findFirst().get();
            assertEquals("m" + r, table.cell(left.row, 1).text);
            assertEquals("r" + r, table.cell(left.row, 2).text);
        }
        OfficeTable.Cell side = table.cells.stream().filter(cell -> "side".equals(cell.text)).findFirst().get();
        assertNull(table.cell(side.row, 0).sourceBounds);
        assertNull(table.cell(side.row, 1).sourceBounds);
    }

    @Test public void anIndependentFourthColumnSurvivesWithOnlyTwoAlignedValues() throws Exception {
        List<HandwritingOcr.Line> items = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            float y = .08f + r * .075f;
            for (int c = 0; c < 3; c++) items.add(box(r + "/" + c, .05f + c * .21f, y, .12f, .028f));
            if (r == 1 || r == 8) items.add(box("rare" + r, r == 1 ? .72f : .728f, y, .11f, .026f));
        }
        OfficeTable table = HandwrittenTableLayout.create(items, 1, "");
        assertEquals(4, table.columns);
        assertEquals(10, table.rows);
        assertEquals("rare1", table.cell(1, 3).text);
        assertEquals("rare8", table.cell(8, 3).text);
        assertNull(table.cell(5, 3).sourceBounds);
    }

    @Test public void sparseTwoColumnSheetKeepsAnUnrepeatedColumn() throws Exception {
        OfficeTable table = HandwrittenTableLayout.create(List.of(line("a", .11f,.11f),
                line("b", .11f,.4f), line("c", .55f,.4f)), 1, "");
        assertEquals(2, table.columns);
        assertEquals(2, table.rows);
        assertEquals("c", table.cell(1, 1).text);
    }

    private static HandwritingOcr.Line box(String text, float x, float y, float w, float h) {
        return new HandwritingOcr.Line(text, "", new RectF(x, y, x + w, y + h), false);
    }

    private static HandwritingOcr.Line line(String text, float left, float top) {
        return new HandwritingOcr.Line(text, "", new RectF(left, top, left + .16f, top + .04f), false);
    }
}
