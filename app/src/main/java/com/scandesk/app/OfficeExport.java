package com.scandesk.app;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class OfficeExport {
    private static final String TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Pattern IDENTIFIER = Pattern.compile("编号|编码|证件|单号|学号|工号|邮编|手机号|电话"
            + "|\\b(id|code|phone|serial|zip)\\b", Pattern.CASE_INSENSITIVE);

    private OfficeExport() {}

    static void word(List<String> pages, OutputStream output) throws IOException {
        if (pages.isEmpty()) throw new IOException("没有可导出的文字");
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "[Content_Types].xml", typesOpen()
                    + override("/word/document.xml", "wordprocessingml.document.main") + "</Types>");
            entry(zip, "_rels/.rels", relationsOpen() + relation(1, "officeDocument", "word/document.xml")
                    + "</Relationships>");
            StringBuilder body = new StringBuilder("<w:document xmlns:w=\"" + WORD + "\"><w:body>");
            for (int page = 0; page < pages.size(); page++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                String[] paragraphs = pages.get(page).replace("\r\n", "\n").split("\n{2,}", -1);
                for (int i = 0; i < paragraphs.length; i++) {
                    body.append("<w:p>");
                    if (page > 0 && i == 0) body.append("<w:pPr><w:pageBreakBefore/></w:pPr>");
                    String[] lines = paragraphs[i].split("\n", -1);
                    for (int line = 0; line < lines.length; line++) {
                        if (line > 0) body.append("<w:r><w:br/></w:r>");
                        body.append("<w:r><w:rPr><w:rFonts w:eastAsia=\"Microsoft YaHei\"/></w:rPr>"
                                + "<w:t xml:space=\"preserve\">").append(escape(lines[line]))
                                .append("</w:t></w:r>");
                    }
                    body.append("</w:p>");
                }
            }
            body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                    + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\""
                    + " w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
            entry(zip, "word/document.xml", body.toString());
        }
    }

    static void excel(List<OfficeTable> tables, OutputStream output) throws IOException {
        if (tables.isEmpty()) throw new IOException("没有可导出的表格");
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            StringBuilder types = new StringBuilder(typesOpen())
                    .append(override("/xl/workbook.xml", "spreadsheetml.sheet.main"))
                    .append(override("/xl/styles.xml", "spreadsheetml.styles"));
            StringBuilder workbook = new StringBuilder("<workbook xmlns=\"" + SHEET
                    + "\" xmlns:r=\"" + OFFICE + "\"><bookViews><workbookView/></bookViews><sheets>");
            StringBuilder relationships = new StringBuilder(relationsOpen());
            for (int i = 0; i < tables.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                int id = i + 1;
                OfficeTable table = tables.get(i);
                String local = "worksheets/sheet" + id + ".xml";
                types.append(override("/xl/" + local, "spreadsheetml.worksheet"));
                relationships.append(relation(id, "worksheet", local));
                workbook.append("<sheet name=\"").append(escape(id + " " + table.name))
                        .append("\" sheetId=\"").append(id).append("\" r:id=\"rId").append(id).append("\"/>");
                entry(zip, "xl/" + local, worksheet(table));
            }
            relationships.append(relation(tables.size() + 1, "styles", "styles.xml"));
            entry(zip, "xl/styles.xml", styles());
            entry(zip, "xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
            entry(zip, "xl/_rels/workbook.xml.rels", relationships.append("</Relationships>").toString());
            entry(zip, "[Content_Types].xml", types.append("</Types>").toString());
            entry(zip, "_rels/.rels", relationsOpen() + relation(1, "officeDocument", "xl/workbook.xml")
                    + "</Relationships>");
        }
    }

    private static String worksheet(OfficeTable table) {
        StringBuilder xml = new StringBuilder("<worksheet xmlns=\"" + SHEET + "\"><dimension ref=\"A1:")
                .append(address(table.rows - 1, table.columns - 1)).append("\"/>")
                .append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"")
                .append(Math.round(table.viewZoom() * 100f)).append("\"/></sheetViews><cols>");
        boolean[] identifiers = new boolean[table.columns];
        for (int c = 0; c < table.columns; c++) {
            OfficeTable.Cell header = table.cell(0, c);
            identifiers[c] = header != null && IDENTIFIER.matcher(header.text).find();
            xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"");
            float width = table.columnWidth(c);
            if (width > 0) {
                xml.append(width / 7f);
            } else {
                int automaticWidth = 10;
                for (int r = 0; r < table.rows; r++) {
                    OfficeTable.Cell cell = table.cell(r, c);
                    if (cell != null && cell.width == 1) {
                        automaticWidth = Math.max(automaticWidth, cell.text.length() + 4);
                    }
                }
                xml.append(Math.min(40, automaticWidth));
            }
            xml.append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>");
        boolean hasPending = false;
        boolean hasUncertain = false;
        for (int r = 0; r < table.rows; r++) {
            xml.append("<row r=\"").append(r + 1).append('"');
            float height = table.rowHeight(r);
            if (height > 0) xml.append(" ht=\"").append(height * 0.75f).append("\" customHeight=\"1\"");
            xml.append('>');
            for (int c = 0; c < table.columns; c++) {
                OfficeTable.Cell cell = table.cell(r, c);
                if (cell == null || cell.row != r || cell.column != c) continue;
                boolean uncertain = cell.needsReview && !cell.reviewed;
                boolean pending = cell.unreviewed() && !uncertain;
                hasPending |= pending;
                hasUncertain |= uncertain;
                int style = r == 0 ? 1 : 0;
                if (uncertain) style += 4;
                else if (pending) style += 2;
                xml.append("<c r=\"").append(address(r, c)).append("\" s=\"").append(style).append('"');
                String number = null;
                if (cell.type == OfficeTable.CellType.NUMBER
                        || cell.type == OfficeTable.CellType.AUTO && !identifiers[c]) {
                    number = numeric(cell.text, cell.type == OfficeTable.CellType.NUMBER);
                }
                if (number != null) {
                    xml.append("><v>").append(number).append("</v></c>");
                } else {
                    xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(escape(cell.text)).append("</t></is></c>");
                }
            }
            xml.append("</row>");
        }
        xml.append("</sheetData>");
        int merged = 0;
        for (OfficeTable.Cell cell : table.cells) {
            if (cell.width > 1 || cell.height > 1) merged++;
        }
        if (merged > 0) {
            xml.append("<mergeCells count=\"").append(merged).append("\">");
            for (OfficeTable.Cell cell : table.cells) {
                if (cell.width == 1 && cell.height == 1) continue;
                xml.append("<mergeCell ref=\"").append(address(cell.row, cell.column)).append(':')
                        .append(address(cell.row + cell.height - 1, cell.column + cell.width - 1)).append("\"/>");
            }
            xml.append("</mergeCells>");
        }
        if (hasPending || hasUncertain) {
            xml.append("<headerFooter><oddFooter>&amp;C");
            if (hasPending) xml.append("浅黄：未核对");
            if (hasPending && hasUncertain) xml.append("　");
            if (hasUncertain) xml.append("浅橙：识别存疑，待核对");
            xml.append("</oddFooter></headerFooter>");
        }
        return xml.append("</worksheet>").toString();
    }

    static String numeric(String text, boolean override) {
        String trimmed = text.trim().replace('−', '-');
        if (trimmed.length() > 40) return null;
        Matcher match = NUMBER.matcher(trimmed);
        if (!match.matches()) return null;
        String integer = match.group(1).replace(",", "");
        if (!override && integer.length() > 1 && integer.startsWith("0")) return null;
        BigDecimal number = new BigDecimal(trimmed.replace(",", ""));
        return number.precision() <= 15 && number.scale() <= 15 ? number.toPlainString() : null;
    }

    static String address(int row, int column) {
        StringBuilder letters = new StringBuilder();
        for (int n = column + 1; n > 0; n = (n - 1) / 26) {
            letters.append((char) ('A' + (n - 1) % 26));
        }
        return letters.reverse().append(row + 1).toString();
    }

    private static String styles() {
        return "<styleSheet xmlns=\"" + SHEET + "\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + reviewFill("FFFFF4CC") + reviewFill("FFFCD5B5") + "</fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"6\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + reviewStyle(0, 2) + reviewStyle(1, 2)
                + reviewStyle(0, 3) + reviewStyle(1, 3) + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static String reviewFill(String color) {
        return "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"" + color
                + "\"/><bgColor indexed=\"64\"/></patternFill></fill>";
    }

    private static String reviewStyle(int fontId, int fillId) {
        return "<xf numFmtId=\"0\" fontId=\"" + fontId + "\" fillId=\"" + fillId
                + "\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"/>";
    }

    private static String typesOpen() {
        return "<Types xmlns=\"" + TYPES + "\"><Default Extension=\"rels\" "
                + "ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>";
    }

    private static String override(String name, String type) {
        return "<Override PartName=\"" + name + "\" ContentType=\"" + CONTENT_TYPE + type + "+xml\"/>";
    }

    private static String relationsOpen() {
        return "<Relationships xmlns=\"" + RELS + "\">";
    }

    private static String relation(int id, String type, String target) {
        return "<Relationship Id=\"rId" + id + "\" Type=\"" + OFFICE + "/" + type
                + "\" Target=\"" + target + "\"/>";
    }

    private static void entry(ZipOutputStream zip, String path, String body) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(XML.getBytes(StandardCharsets.UTF_8));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '"': escaped.append("&quot;"); break;
                default:
                    if (cp == 9 || cp == 10 || cp == 13 || cp >= 0x20 && cp <= 0xd7ff
                            || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000) escaped.appendCodePoint(cp);
            }
        }
        return escaped.toString();
    }
}
