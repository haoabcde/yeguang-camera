package com.scandesk.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TableDimensionsTest {
    @Test public void draftRoundTripKeepsSelectedSizesAndZoom() throws Exception {
        OfficeTable table = sampleTable();
        table.columnWidths[0] = 141.5f;
        table.rowHeights[1] = 52f;
        table.zoom = 1.65f;

        OfficeTable restored = OfficeTable.from(new JSONObject(table.json().toString()));
        assertArrayEquals(new float[]{141.5f, 0f}, restored.columnWidths, 0f);
        assertArrayEquals(new float[]{0f, 52f, 0f}, restored.rowHeights, 0f);
        assertEquals(1.65f, restored.zoom, 0f);
        assertEquals("001", restored.cell(1, 0).text);
        assertEquals(OfficeTable.CellType.NUMBER, restored.cell(1, 1).type);
        assertEquals(2, restored.cell(2, 0).width);
    }

    @Test public void olderDraftStartsWithAutomaticSizesAndNormalZoom() throws Exception {
        JSONObject saved = new JSONObject("{\"name\":\"旧稿\",\"page\":1,\"rows\":2,\"columns\":3,"
                + "\"cells\":[[0,0,1,1,\"编号\"],[1,0,1,1,\"001\"]]}");
        OfficeTable table = OfficeTable.from(saved);
        assertArrayEquals(new float[3], table.columnWidths, 0f);
        assertArrayEquals(new float[2], table.rowHeights, 0f);
        assertEquals(1f, table.zoom, 0f);
        assertEquals("001", table.cell(1, 0).text);
    }

    @Test public void draftSizesAreBoundedDuringLoadAndSave() throws Exception {
        JSONObject saved = new OfficeTable("尺寸", 1, 5, 5, false, List.of()).json()
                .put("columnWidths", new JSONArray().put(1).put(Double.MAX_VALUE)
                        .put("Infinity").put(-7).put(JSONObject.NULL).put(224))
                .put("rowHeights", new JSONArray().put(500).put(1).put("NaN"))
                .put("zoom", "Infinity");
        OfficeTable table = OfficeTable.from(saved);

        assertArrayEquals(new float[]{48f, 640f, 0f, 0f, 0f}, table.columnWidths, 0f);
        assertArrayEquals(new float[]{360f, 32f, 0f, 0f, 0f}, table.rowHeights, 0f);
        assertEquals(1f, table.zoom, 0f);
        assertEquals(0.6f, OfficeTable.from(saved.put("zoom", 0.2)).zoom, 0f);

        table.columnWidths[2] = Float.NaN;
        table.rowHeights[2] = 900f;
        table.zoom = 4f;
        JSONObject resaved = table.json();
        assertEquals(0d, resaved.getJSONArray("columnWidths").getDouble(2), 0d);
        assertEquals(360d, resaved.getJSONArray("rowHeights").getDouble(2), 0d);
        assertEquals(2.5d, resaved.getDouble("zoom"), 0d);
    }

    @Test public void workbookStoresSizesAndZoomWithoutChangingCellValues() throws Exception {
        OfficeTable sized = sampleTable();
        sized.columnWidths[0] = 140f;
        sized.rowHeights[1] = 64f;
        sized.zoom = 1.5f;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OfficeExport.excel(List.of(sized, sampleTable()), bytes);

        byte[] workbook = bytes.toByteArray();
        Document resized = readEntry(workbook, "xl/worksheets/sheet1.xml");
        Document automatic = readEntry(workbook, "xl/worksheets/sheet2.xml");
        assertEquals(1, readEntry(workbook, "xl/workbook.xml").getElementsByTagName("workbookView").getLength());
        assertEquals("150", element(resized, "sheetView", 0).getAttribute("zoomScale"));
        assertEquals("100", element(automatic, "sheetView", 0).getAttribute("zoomScale"));

        Element resizedColumn = element(resized, "col", 0);
        assertEquals("1", resizedColumn.getAttribute("min"));
        assertEquals("1", resizedColumn.getAttribute("customWidth"));
        assertEquals(20d, Double.parseDouble(resizedColumn.getAttribute("width")), 0d);
        assertEquals("10", element(automatic, "col", 0).getAttribute("width"));
        assertEquals(element(automatic, "col", 1).getAttribute("width"),
                element(resized, "col", 1).getAttribute("width"));

        Element resizedRow = element(resized, "row", 1);
        assertEquals("2", resizedRow.getAttribute("r"));
        assertEquals("1", resizedRow.getAttribute("customHeight"));
        assertEquals(48d, Double.parseDouble(resizedRow.getAttribute("ht")), 0d);
        assertFalse(element(resized, "row", 0).hasAttribute("ht"));
        assertFalse(element(automatic, "row", 1).hasAttribute("customHeight"));
        assertEquals("A3:B3", element(resized, "mergeCell", 0).getAttribute("ref"));

        Element identifier = element(resized, "c", 2);
        assertEquals("A2", identifier.getAttribute("r"));
        assertEquals("inlineStr", identifier.getAttribute("t"));
        assertEquals("001", identifier.getTextContent());
        Element amount = element(resized, "c", 3);
        assertEquals("B2", amount.getAttribute("r"));
        assertEquals("120.50", amount.getTextContent());
        assertEquals(1, amount.getElementsByTagName("v").getLength());
    }

    private static OfficeTable sampleTable() {
        OfficeTable.Cell amount = new OfficeTable.Cell(1, 1, 1, 1, "120.50");
        amount.type = OfficeTable.CellType.NUMBER;
        return new OfficeTable("采购清单", 1, 3, 2, false, List.of(
                new OfficeTable.Cell(0, 0, 1, 1, "编号"),
                new OfficeTable.Cell(0, 1, 1, 1, "金额"),
                new OfficeTable.Cell(1, 0, 1, 1, "001"), amount,
                new OfficeTable.Cell(2, 0, 1, 2, "合计")));
    }

    private static Element element(Document document, String tag, int position) {
        Element element = (Element) document.getElementsByTagName(tag).item(position);
        assertNotNull(tag + " " + position, element);
        return element;
    }

    private static Document readEntry(byte[] workbook, String path) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (path.equals(entry.getName())) {
                    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zip);
                }
            }
        }
        throw new AssertionError("Missing workbook entry " + path);
    }
}
