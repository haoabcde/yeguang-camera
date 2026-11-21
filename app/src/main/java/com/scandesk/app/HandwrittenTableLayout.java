package com.scandesk.app;

import android.graphics.RectF;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Keeps detected items in their visual columns, including isolated rows and unreadable items. */
final class HandwrittenTableLayout {
    private HandwrittenTableLayout() {}

    static OfficeTable create(List<HandwritingOcr.Line> lines, int page, String label) throws IOException {
        return create(lines, page, label, 0);
    }

    private static OfficeTable create(List<HandwritingOcr.Line> lines, int page, String label, int requestedColumns)
            throws IOException {
        if (lines.isEmpty()) throw new IOException("没有可按原图分列的条目");
        List<Float> heights = new ArrayList<>(), widths = new ArrayList<>();
        for (HandwritingOcr.Line line : lines) {
            heights.add(line.bounds.height());
            widths.add(line.bounds.width());
        }
        Collections.sort(heights);
        Collections.sort(widths);
        float height = heights.get(heights.size() / 2);
        float width = widths.get(widths.size() / 2);
        List<Column> columns = requestedColumns == 0 ? columns(lines, height, width)
                : fittedColumns(lines, height, width, requestedColumns);
        List<HandwritingOcr.Line> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingDouble((HandwritingOcr.Line line) -> line.bounds.centerY())
                .thenComparingDouble(line -> line.bounds.left));
        List<Row> rows = new ArrayList<>();
        List<Row> notes = new ArrayList<>();
        for (HandwritingOcr.Line line : ordered) {
            int column = nearest(columns, line.bounds.left);
            if (columns.size() > 1 && (!typicalSize(line.bounds, height, width)
                    || !columns.get(column).aligns(line.bounds, height))) {
                // Marginalia can share a baseline with a body value without sharing its cell.
                Row note = new Row(line.bounds.centerY(), columns.size());
                note.items[column] = line;
                notes.add(note);
                continue;
            }
            Row selected = null;
            for (int i = rows.size() - 1; i >= 0; i--) {
                Row row = rows.get(i);
                if (line.bounds.centerY() - row.center > height * .70f) break;
                if (row.items[column] == null) {
                    selected = row;
                    break;
                }
            }
            if (selected == null) {
                selected = new Row(line.bounds.centerY(), columns.size());
                rows.add(selected);
            }
            selected.items[column] = line;
        }
        rows.addAll(notes);
        rows.sort(Comparator.comparingDouble(row -> row.center));
        if (rows.size() > OfficeTable.MAX_ROWS || columns.size() > OfficeTable.MAX_COLUMNS) {
            throw new IOException("第 " + page + " 页内容过多，请裁切后分段识别");
        }
        List<OfficeTable.Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < columns.size(); c++) {
                HandwritingOcr.Line item = rows.get(r).items[c];
                OfficeTable.Cell cell = new OfficeTable.Cell(r, c, 1, 1, item == null ? "" : item.text);
                cell.type = OfficeTable.CellType.TEXT;
                if (item != null) {
                    cell.sourceBounds = new RectF(item.bounds);
                    cell.needsReview = item.uncertain;
                    cell.alternative = item.alternative;
                }
                cells.add(cell);
            }
        }
        return new OfficeTable("第 " + page + " 页" + label, page, rows.size(), columns.size(), true, cells);
    }

    /** Reassign physical columns from source positions without rerunning OCR or losing corrected text. */
    static OfficeTable relayout(OfficeTable original, int requestedColumns) throws IOException {
        if (requestedColumns < 0 || requestedColumns > OfficeTable.MAX_COLUMNS) throw new IOException("列数超出范围");
        List<HandwritingOcr.Line> lines = new ArrayList<>();
        Map<RectF, ArrayDeque<OfficeTable.Cell>> sources = new HashMap<>();
        List<OfficeTable.Cell> manual = new ArrayList<>();
        for (OfficeTable.Cell cell : original.cells) {
            if (cell.sourceBounds != null) {
                lines.add(new HandwritingOcr.Line(cell.text, cell.alternative, cell.sourceBounds, cell.needsReview));
                sources.computeIfAbsent(cell.sourceBounds, key -> new ArrayDeque<>()).add(cell);
            } else if (!cell.text.isEmpty()) manual.add(cell);
        }
        OfficeTable arranged = create(lines, original.page, "", requestedColumns);
        List<OfficeTable.Cell> cells = new ArrayList<>();
        for (OfficeTable.Cell cell : arranged.cells) {
            OfficeTable.Cell source = cell.sourceBounds == null ? null : sources.get(cell.sourceBounds).removeFirst();
            cells.add(source == null ? cell : copy(source, cell.row, cell.column));
        }
        // Text added into formerly empty cells has no source coordinates. Preserve those entries
        // as supplementary rows rather than inventing a source box or silently dropping them.
        int row = arranged.rows;
        int previousRow = -1;
        manual.sort(Comparator.comparingInt((OfficeTable.Cell cell) -> cell.row).thenComparingInt(cell -> cell.column));
        for (OfficeTable.Cell cell : manual) {
            if (cell.row != previousRow) {
                if (previousRow >= 0) row++;
                previousRow = cell.row;
            }
            int column = Math.min(arranged.columns - 1, cell.column * arranged.columns / original.columns);
            boolean occupied = false;
            for (OfficeTable.Cell existing : cells) {
                if (existing.row == row && existing.column == column) { occupied = true; break; }
            }
            if (occupied) row++;
            cells.add(copy(cell, row, column));
        }
        int rows = manual.isEmpty() ? arranged.rows : row + 1;
        if (rows > OfficeTable.MAX_ROWS) throw new IOException("调整后行数过多，请先减少内容");
        OfficeTable result = new OfficeTable(original.name, original.page, rows, arranged.columns, true, cells);
        result.zoom = original.viewZoom();
        if (result.columns == original.columns && result.rows == original.rows) {
            System.arraycopy(original.columnWidths, 0, result.columnWidths, 0, result.columns);
            System.arraycopy(original.rowHeights, 0, result.rowHeights, 0, result.rows);
        }
        return result;
    }

    private static OfficeTable.Cell copy(OfficeTable.Cell source, int row, int column) {
        OfficeTable.Cell cell = new OfficeTable.Cell(row, column, 1, 1, source.text);
        cell.type = source.type;
        cell.sourceBounds = source.sourceBounds == null ? null : new RectF(source.sourceBounds);
        cell.alternative = source.alternative;
        cell.needsReview = source.needsReview;
        cell.reviewed = source.reviewed;
        return cell;
    }

    private static List<Column> fittedColumns(List<HandwritingOcr.Line> lines, float height, float width, int count) {
        List<Column> detected = columns(lines, height, width);
        if (detected.size() == count) return detected;
        List<RectF> body = new ArrayList<>();
        for (HandwritingOcr.Line line : lines) if (typicalSize(line.bounds, height, width)) body.add(line.bounds);
        if (body.isEmpty()) for (HandwritingOcr.Line line : lines) body.add(line.bounds);
        body.sort(Comparator.comparingDouble(box -> box.left));
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float anchor = body.get(Math.min(body.size() - 1, (int) ((i + .5) * body.size() / count))).left;
            columns.add(new Column(new RectF(anchor, 0, anchor + width, height)));
        }
        // Fit source text positions rather than dividing the photo into equal-width strips.
        for (int step = 0; step < 12; step++) {
            List<List<RectF>> groups = new ArrayList<>();
            for (int i = 0; i < count; i++) groups.add(new ArrayList<>());
            for (RectF box : body) groups.get(nearest(columns, box.left)).add(box);
            for (int i = 0; i < count; i++) {
                if (groups.get(i).isEmpty()) continue;
                Column column = new Column(groups.get(i).get(0));
                for (int j = 1; j < groups.get(i).size(); j++) column.add(groups.get(i).get(j));
                columns.set(i, column);
            }
            columns.sort(Comparator.comparingDouble(column -> column.left));
        }
        return columns;
    }

    private static List<Column> columns(List<HandwritingOcr.Line> lines, float height, float width) {
        List<HandwritingOcr.Line> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingDouble(line -> line.bounds.left));
        List<Column> columns = new ArrayList<>();
        for (HandwritingOcr.Line line : ordered) {
            if (!typicalSize(line.bounds, height, width)) continue;
            Column previous = columns.isEmpty() ? null : columns.get(columns.size() - 1);
            if (previous == null || !previous.aligns(line.bounds, height)) {
                columns.add(new Column(line.bounds));
            } else {
                previous.add(line.bounds);
            }
        }
        if (columns.isEmpty()) columns.add(new Column(ordered.get(0).bounds));
        int largest = 0;
        for (Column column : columns) largest = Math.max(largest, column.items.size());
        List<Column> body = new ArrayList<>();
        for (Column column : columns) {
            if (column.items.size() >= Math.max(4, largest * .2f)) body.add(column);
        }
        // Densely repeated columns establish body baselines. On sparse sheets there is
        // no dominant body against which to reject less frequent columns.
        if (body.size() >= 2) {
            columns.removeIf(column -> !body.contains(column) && !column.matchesBodyRows(body, height));
        }
        return columns;
    }

    private static boolean typicalSize(RectF box, float height, float width) {
        return box.width() <= Math.max(.20f, width * 1.8f) && box.height() <= height * 1.45f;
    }

    private static int nearest(List<Column> columns, float left) {
        int nearest = 0;
        for (int c = 1; c < columns.size(); c++) {
            if (Math.abs(left - columns.get(c).left) < Math.abs(left - columns.get(nearest).left)) nearest = c;
        }
        return nearest;
    }

    private static final class Column {
        float left;
        float right;
        final List<RectF> items = new ArrayList<>();

        Column(RectF box) { add(box); }

        void add(RectF box) {
            items.add(box);
            left += (box.left - left) / items.size();
            right += (box.right - right) / items.size();
        }

        boolean aligns(RectF box, float height) {
            float narrow = Math.min(right - left, box.width());
            float overlap = Math.min(right, box.right) - Math.max(left, box.left);
            return overlap > 0 && (Math.abs(box.left - left) <= Math.max(.012f, height)
                    || overlap >= .52f * narrow && Math.abs(box.left - left) <= .58f * narrow);
        }

        boolean matchesBodyRows(List<Column> body, float height) {
            List<RectF> aligned = new ArrayList<>();
            for (RectF box : items) {
                boolean onBaseline = false;
                for (Column column : body) {
                    for (RectF reference : column.items) {
                        if (Math.abs(reference.centerY() - box.centerY()) <= height * .55f) {
                            onBaseline = true;
                            break;
                        }
                    }
                    if (onBaseline) break;
                }
                if (!onBaseline) continue;
                for (RectF prior : aligned) {
                    if (Math.abs(box.centerY() - prior.centerY()) >= height
                            && Math.abs(box.left - prior.left) <= Math.min(height * .8f, box.width() * .22f)) return true;
                }
                aligned.add(box);
            }
            return false;
        }
    }

    private static final class Row {
        final float center;
        final HandwritingOcr.Line[] items;

        Row(float center, int columns) {
            this.center = center;
            items = new HandwritingOcr.Line[columns];
        }
    }
}
