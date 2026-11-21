package com.scandesk.app;

import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class OfficeTable {
    static final int MAX_ROWS = 180;
    static final int MAX_COLUMNS = 40;
    static final float MIN_COLUMN_WIDTH = 48f;
    static final float MAX_COLUMN_WIDTH = 640f;
    static final float MIN_ROW_HEIGHT = 32f;
    static final float MAX_ROW_HEIGHT = 360f;
    static final float MIN_ZOOM = 0.6f;
    static final float MAX_ZOOM = 2.5f;
    final String name;
    final int page;
    final int rows;
    final int columns;
    final boolean inferred;
    final List<Cell> cells;
    // Zero leaves a row or column at its automatic size. Explicit sizes are in dp.
    final float[] columnWidths;
    final float[] rowHeights;
    float zoom = 1f;
    private final Cell[][] owners;

    OfficeTable(String name, int page, int rows, int columns, boolean inferred, List<Cell> cells) {
        if (rows < 1 || rows > MAX_ROWS || columns < 1 || columns > MAX_COLUMNS) {
            throw new IllegalArgumentException("表格尺寸超出范围");
        }
        this.name = name;
        this.page = page;
        this.rows = rows;
        this.columns = columns;
        this.inferred = inferred;
        this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        columnWidths = new float[columns];
        rowHeights = new float[rows];
        owners = new Cell[rows][columns];
        for (Cell cell : cells) {
            if (cell.row < 0 || cell.column < 0 || cell.height < 1 || cell.width < 1
                    || cell.row + cell.height > rows || cell.column + cell.width > columns) {
                throw new IllegalArgumentException("表格包含无效单元格");
            }
            for (int r = cell.row; r < cell.row + cell.height; r++) {
                for (int c = cell.column; c < cell.column + cell.width; c++) {
                    if (owners[r][c] != null) throw new IllegalArgumentException("表格包含重叠单元格");
                    owners[r][c] = cell;
                }
            }
        }
    }

    Cell cell(int row, int column) {
        return owners[row][column];
    }

    float columnWidth(int column) {
        return dimension(columnWidths[column], MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH);
    }

    float rowHeight(int row) {
        return dimension(rowHeights[row], MIN_ROW_HEIGHT, MAX_ROW_HEIGHT);
    }

    float viewZoom() {
        return clampZoom(zoom);
    }

    JSONObject json() throws JSONException {
        JSONArray values = new JSONArray();
        for (Cell cell : cells) {
            JSONArray value = new JSONArray().put(cell.row).put(cell.column).put(cell.height)
                    .put(cell.width).put(cell.text).put(cell.type.name());
            if (cell.needsReview || cell.sourceBounds != null || cell.reviewed || !cell.alternative.isEmpty()) {
                value.put(cell.needsReview);
                RectF box = cell.sourceBounds;
                if (box != null) value.put(new JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom));
                else value.put(JSONObject.NULL);
                value.put(cell.reviewed);
                if (!cell.alternative.isEmpty()) value.put(cell.alternative);
            }
            values.put(value);
        }
        JSONArray widths = new JSONArray();
        for (int c = 0; c < columns; c++) widths.put(columnWidth(c));
        JSONArray heights = new JSONArray();
        for (int r = 0; r < rows; r++) heights.put(rowHeight(r));
        return new JSONObject().put("name", name).put("page", page).put("rows", rows)
                .put("columns", columns).put("inferred", inferred).put("cells", values)
                .put("columnWidths", widths).put("rowHeights", heights).put("zoom", viewZoom());
    }

    static OfficeTable from(JSONObject json) throws JSONException {
        JSONArray values = json.getJSONArray("cells");
        List<Cell> cells = new ArrayList<>(values.length());
        for (int i = 0; i < values.length(); i++) {
            JSONArray value = values.getJSONArray(i);
            Cell cell = new Cell(value.getInt(0), value.getInt(1), value.getInt(2),
                    value.getInt(3), value.getString(4));
            cell.type = CellType.valueOf(value.optString(5, CellType.AUTO.name()));
            cell.needsReview = value.optBoolean(6, false);
            cell.reviewed = value.optBoolean(8, false);
            cell.alternative = value.optString(9, "");
            JSONArray box = value.optJSONArray(7);
            if (box != null && box.length() == 4) {
                RectF region = new RectF((float) box.optDouble(0), (float) box.optDouble(1),
                        (float) box.optDouble(2), (float) box.optDouble(3));
                if (region.intersect(0, 0, 1, 1)) cell.sourceBounds = region;
            }
            cells.add(cell);
        }
        OfficeTable table = new OfficeTable(json.getString("name"), json.getInt("page"), json.getInt("rows"),
                json.getInt("columns"), json.optBoolean("inferred"), cells);
        readDimensions(json.optJSONArray("columnWidths"), table.columnWidths,
                MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH);
        readDimensions(json.optJSONArray("rowHeights"), table.rowHeights, MIN_ROW_HEIGHT, MAX_ROW_HEIGHT);
        table.zoom = clampZoom(json.optDouble("zoom", 1));
        return table;
    }

    private static void readDimensions(JSONArray values, float[] dimensions, float min, float max) {
        if (values == null) return;
        for (int i = 0; i < Math.min(values.length(), dimensions.length); i++) {
            dimensions[i] = dimension(values.optDouble(i, 0), min, max);
        }
    }

    private static float dimension(double value, float min, float max) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        return (float) Math.max(min, Math.min(max, value));
    }

    private static float clampZoom(double value) {
        if (!Double.isFinite(value)) return 1f;
        return (float) Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }

    enum CellType {
        AUTO("自动"), TEXT("文本"), NUMBER("数字");
        final String label;

        CellType(String label) {
            this.label = label;
        }
    }

    static final class Cell {
        final int row;
        final int column;
        final int height;
        final int width;
        String text;
        CellType type = CellType.AUTO;
        RectF sourceBounds;
        boolean needsReview;
        boolean reviewed;
        String alternative = "";

        boolean unreviewed() {
            return sourceBounds != null && !reviewed;
        }

        Cell(int row, int column, int height, int width, String text) {
            this.row = row;
            this.column = column;
            this.height = height;
            this.width = width;
            this.text = text;
        }
    }
}
