package com.scandesk.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A scrollable spreadsheet with source-cell editing, frozen rulers and persistent dimensions. */
@SuppressLint("ViewConstructor")
final class TableGridView extends View {
    interface CellAction {
        void edit(OfficeTable.Cell cell, TableGridView grid);
    }

    private static final int COLUMN_NODE = OfficeTable.MAX_ROWS * OfficeTable.MAX_COLUMNS + 1;
    private static final int ROW_NODE = COLUMN_NODE + OfficeTable.MAX_COLUMNS;
    private final OfficeTable table;
    private final ScanUi ui;
    private final CellAction action;
    private final int headerHeight, gutter, defaultHeight, touchSlop;
    private final int[] x, y;
    private final Paint paint = new Paint();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect(), visible = new Rect();
    private final ExploreByTouchHelper accessibility;
    private final ScaleGestureDetector scaling;
    private final ViewTreeObserver.OnScrollChangedListener scrollChanged = this::invalidate;
    private Runnable changeListener;
    private OfficeTable.Cell touched;
    private float pressX, pressY, originalSize;
    private int resizingColumn = -1, resizingRow = -1;
    private boolean multiplePointers, changed;
    private float zoom;

    TableGridView(Context context, ScanUi ui, OfficeTable table, CellAction action) {
        super(context);
        this.table = table;
        this.ui = ui;
        this.action = action;
        defaultHeight = Math.max(ui.dp(48), Math.round(ui.px(15) + ui.dp(24)));
        headerHeight = ui.dp(44);
        gutter = Math.max(ui.dp(40), Math.round(ui.px(12) * 3.8f));
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        x = new int[table.columns + 1];
        y = new int[table.rows + 1];
        zoom = table.viewZoom();
        rebuildGeometry();
        scaling = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                touched = null;
                multiplePointers = true;
                resizingColumn = resizingRow = -1;
                disallowScroll(true);
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                updateZoom(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) { finishChange(); }
        });
        accessibility = new ExploreByTouchHelper(this) {
            @Override protected int getVirtualViewAt(float px, float py) {
                Rect clip = viewport();
                float lx = px / zoom, ly = py / zoom;
                if (ly < clip.top / zoom + headerHeight) {
                    int col = columnAt(lx);
                    return col < 0 ? INVALID_ID : COLUMN_NODE + col;
                }
                if (lx < clip.left / zoom + gutter) {
                    int row = rowAt(ly);
                    return row < 0 ? INVALID_ID : ROW_NODE + row;
                }
                OfficeTable.Cell cell = hit(px, py);
                return cell == null ? INVALID_ID : cell.row * table.columns + cell.column + 1;
            }
            @Override protected void getVisibleVirtualViews(List<Integer> ids) {
                if (!getLocalVisibleRect(visible)) return;
                Rect body = bodyViewport();
                for (OfficeTable.Cell cell : table.cells) {
                    cellBounds(cell, bounds);
                    if (Rect.intersects(body, bounds)) ids.add(cell.row * table.columns + cell.column + 1);
                }
                for (int c = 0; c < table.columns; c++) {
                    rulerBounds(true, c, bounds);
                    if (Rect.intersects(visible, bounds)) ids.add(COLUMN_NODE + c);
                }
                for (int r = 0; r < table.rows; r++) {
                    rulerBounds(false, r, bounds);
                    if (Rect.intersects(visible, bounds)) ids.add(ROW_NODE + r);
                }
            }
            @Override protected void onPopulateNodeForVirtualView(int id, AccessibilityNodeInfoCompat node) {
                if (id >= COLUMN_NODE) {
                    boolean column = id < ROW_NODE;
                    int index = id - (column ? COLUMN_NODE : ROW_NODE);
                    float size = (column ? x[index + 1] - x[index] : y[index + 1] - y[index])
                            / getResources().getDisplayMetrics().density;
                    String name = column ? columnName(index) + " 列，列宽 " : "第 " + (index + 1) + " 行，行高 ";
                    node.setContentDescription(name + Math.round(size) + "，可调整");
                    node.setClassName("android.widget.SeekBar");
                    node.setRangeInfo(AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                            AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT,
                            column ? OfficeTable.MIN_COLUMN_WIDTH : OfficeTable.MIN_ROW_HEIGHT,
                            column ? OfficeTable.MAX_COLUMN_WIDTH : OfficeTable.MAX_ROW_HEIGHT, size));
                    node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS);
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
                    rulerBounds(column, index, bounds);
                    node.setBoundsInParent(bounds);
                    return;
                }
                OfficeTable.Cell cell = fromId(id);
                if (cell == null) {
                    node.setContentDescription("单元格");
                    node.setBoundsInParent(new Rect(0, 0, 1, 1));
                    return;
                }
                node.setContentDescription(OfficeExport.address(cell.row, cell.column) + " "
                        + (cell.text.isEmpty() ? "空白" : cell.text)
                        + (cell.unreviewed() ? (cell.needsReview ? "，未核对，识别存疑" : "，未核对")
                        : cell.reviewed ? "，已核对" : ""));
                node.setClassName("android.widget.Button");
                cellBounds(cell, bounds);
                bounds.intersect(bodyViewport());
                node.setBoundsInParent(bounds);
                node.setClickable(true);
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            }
            @Override protected boolean onPerformActionForVirtualView(int id, int event, Bundle arguments) {
                if (id >= COLUMN_NODE) {
                    boolean column = id < ROW_NODE;
                    int index = id - (column ? COLUMN_NODE : ROW_NODE);
                    float size = (column ? x[index + 1] - x[index] : y[index + 1] - y[index])
                            / getResources().getDisplayMetrics().density;
                    if (event == AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS.getId()
                            && arguments != null) {
                        size = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, size);
                    } else if (event == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD) size += column ? 16 : 8;
                    else if (event == AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD) size -= column ? 16 : 8;
                    else return false;
                    if (column) resizeColumn(index, size); else resizeRow(index, size);
                    return true;
                }
                OfficeTable.Cell cell = fromId(id);
                if (cell == null || event != AccessibilityNodeInfoCompat.ACTION_CLICK) return false;
                action.edit(cell, TableGridView.this);
                return true;
            }
        };
        ViewCompat.setAccessibilityDelegate(this, accessibility);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setChangeListener(Runnable listener) { changeListener = listener; }
    float getZoom() { return zoom; }

    void setZoom(float value) {
        Rect clip = viewport();
        updateZoom(value, clip.exactCenterX(), clip.exactCenterY());
        finishChange();
    }

    void resizeColumn(int column, float dp) {
        if (column < 0 || column >= table.columns || !Float.isFinite(dp)) return;
        table.columnWidths[column] = Math.max(OfficeTable.MIN_COLUMN_WIDTH, Math.min(OfficeTable.MAX_COLUMN_WIDTH, dp));
        changed = true;
        geometryChanged();
        finishChange();
    }

    void resizeRow(int row, float dp) {
        if (row < 0 || row >= table.rows || !Float.isFinite(dp)) return;
        table.rowHeights[row] = Math.max(OfficeTable.MIN_ROW_HEIGHT, Math.min(OfficeTable.MAX_ROW_HEIGHT, dp));
        changed = true;
        geometryChanged();
        finishChange();
    }

    void resetDimensions() {
        Arrays.fill(table.columnWidths, 0);
        Arrays.fill(table.rowHeights, 0);
        changed = true;
        geometryChanged();
        finishChange();
    }

    void refreshFromModel() {
        zoom = table.viewZoom();
        changed = false;
        geometryChanged();
    }

    private void updateZoom(float value, float focusX, float focusY) {
        if (!Float.isFinite(value)) return;
        float next = Math.max(OfficeTable.MIN_ZOOM, Math.min(OfficeTable.MAX_ZOOM, value));
        if (Math.abs(next - zoom) < .001f) return;
        float ratio = next / zoom;
        HorizontalScrollView horizontal = ancestor(HorizontalScrollView.class);
        ScrollView vertical = ancestor(ScrollView.class);
        int scrollX = horizontal == null ? 0 : horizontal.getScrollX();
        int scrollY = vertical == null ? 0 : vertical.getScrollY();
        int targetX = Math.round(focusX * ratio - (focusX - scrollX));
        int targetY = Math.round(focusY * ratio - (focusY - scrollY));
        zoom = next;
        table.zoom = next;
        changed = true;
        requestLayout();
        accessibility.invalidateRoot();
        invalidate();
        post(() -> {
            if (horizontal != null) horizontal.scrollTo(targetX, horizontal.getScrollY());
            if (vertical != null) vertical.scrollTo(vertical.getScrollX(), targetY);
        });
    }

    private <T> T ancestor(Class<T> kind) {
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (kind.isInstance(parent)) return kind.cast(parent);
        }
        return null;
    }

    private void finishChange() {
        if (!changed) return;
        changed = false;
        if (changeListener != null) changeListener.run();
    }

    private void rebuildGeometry() {
        x[0] = gutter;
        for (int c = 0; c < table.columns; c++) x[c + 1] = x[c] + columnWidth(c);
        y[0] = headerHeight;
        for (int r = 0; r < table.rows; r++) {
            float saved = table.rowHeight(r);
            y[r + 1] = y[r] + (saved == 0 ? defaultHeight : ui.dp(saved));
        }
    }

    private void geometryChanged() {
        rebuildGeometry();
        requestLayout();
        accessibility.invalidateRoot();
        invalidate();
    }

    private int columnWidth(int column) {
        float explicit = table.columnWidth(column);
        if (explicit > 0) return ui.dp(explicit);
        float width = ui.dp(96);
        List<Float> handwritingWidths = new ArrayList<>();
        text.setTextSize(ui.px(14));
        for (int row = 0; row < table.rows; row++) {
            OfficeTable.Cell cell = table.cell(row, column);
            if (cell != null && cell.width == 1 && cell.row == row) {
                text.setTypeface(cell.row == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                float contentWidth = text.measureText(cell.text.replace('\n', ' ')) + ui.dp(24);
                width = Math.max(width, contentWidth);
                if (cell.sourceBounds != null && !cell.text.isEmpty()) handwritingWidths.add(contentWidth);
            }
        }
        if (handwritingWidths.size() >= 5) {
            Collections.sort(handwritingWidths);
            width = Math.max(ui.dp(80), handwritingWidths.get((int) Math.ceil(handwritingWidths.size() * .8) - 1));
        }
        return Math.min(ui.dp(210), (int) Math.ceil(width));
    }

    void cellChanged(OfficeTable.Cell cell) { geometryChanged(); }

    void reveal(OfficeTable.Cell cell) {
        post(() -> {
            Rect target = new Rect();
            cellBounds(cell, target);
            target.left = Math.max(0, target.left - Math.round(gutter * zoom));
            target.top = Math.max(0, target.top - Math.round(headerHeight * zoom));
            requestRectangleOnScreen(target, true);
        });
    }

    private OfficeTable.Cell fromId(int id) {
        int index = id - 1;
        return index >= 0 && index < table.rows * table.columns
                ? table.cell(index / table.columns, index % table.columns) : null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollChanged);
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnScrollChangedListener(scrollChanged);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(x[table.columns] * zoom) + 1, Math.round(y[table.rows] * zoom) + 1);
    }

    private void cellBounds(OfficeTable.Cell cell, Rect rect) {
        rect.set(Math.round(x[cell.column] * zoom), Math.round(y[cell.row] * zoom),
                Math.round(x[cell.column + cell.width] * zoom), Math.round(y[cell.row + cell.height] * zoom));
    }

    private Rect viewport() {
        Rect clip = new Rect();
        if (!getLocalVisibleRect(clip)) clip.set(0, 0, getWidth(), getHeight());
        return clip;
    }

    private Rect bodyViewport() {
        Rect clip = viewport();
        clip.left += Math.round(gutter * zoom);
        clip.top += Math.round(headerHeight * zoom);
        return clip;
    }

    private void rulerBounds(boolean column, int index, Rect result) {
        Rect clip = viewport();
        if (column) result.set(Math.max(clip.left + Math.round(gutter * zoom), Math.round(x[index] * zoom)),
                clip.top, Math.round(x[index + 1] * zoom), clip.top + Math.round(headerHeight * zoom));
        else result.set(clip.left, Math.max(clip.top + Math.round(headerHeight * zoom), Math.round(y[index] * zoom)),
                clip.left + Math.round(gutter * zoom), Math.round(y[index + 1] * zoom));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(ScanUi.WHITE);
        canvas.save();
        canvas.scale(zoom, zoom);
        canvas.getClipBounds(visible);
        text.setTextSize(ui.px(14));
        text.setColor(ScanUi.INK);
        for (OfficeTable.Cell cell : table.cells) {
            bounds.set(x[cell.column], y[cell.row], x[cell.column + cell.width], y[cell.row + cell.height]);
            if (!Rect.intersects(visible, bounds)) continue;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(cell.row == 0 ? ScanUi.ACCENT_SOFT : ScanUi.WHITE);
            canvas.drawRect(bounds, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            paint.setColor(ScanUi.LINE);
            canvas.drawRect(bounds, paint);
            if (cell.unreviewed()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(cell.needsReview ? ScanUi.ACCENT : ScanUi.SECONDARY);
                canvas.drawCircle(bounds.right - ui.dp(7), bounds.top + ui.dp(7), ui.dp(3), paint);
            }
            text.setTypeface(Typeface.create("sans-serif", cell.row == 0 ? Typeface.BOLD : Typeface.NORMAL));
            int width = Math.max(1, bounds.width() - ui.dp(22));
            int lines = Math.max(1, (int) ((bounds.height() - ui.dp(12)) / (text.getFontSpacing() * 1.05f)));
            StaticLayout layout = StaticLayout.Builder.obtain(cell.text, 0, cell.text.length(), text, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                    .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).build();
            canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(bounds.left + ui.dp(11), bounds.top + Math.max(ui.dp(5), (bounds.height() - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }
        drawRulers(canvas);
        canvas.restore();
    }

    private void drawRulers(Canvas canvas) {
        int left = visible.left, top = visible.top;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ScanUi.SURFACE);
        canvas.drawRect(left, top, visible.right, top + headerHeight, paint);
        canvas.drawRect(left, top, left + gutter, visible.bottom, paint);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setColor(ScanUi.SECONDARY);
        text.setTextSize(ui.px(12));
        for (int c = 0; c < table.columns; c++) {
            if (x[c + 1] <= left + gutter || x[c] >= visible.right) continue;
            drawCentered(canvas, columnName(c), Math.max(x[c], left + gutter), x[c + 1], top, top + headerHeight);
            paint.setColor(c == resizingColumn ? ScanUi.ACCENT : ScanUi.LINE);
            paint.setStrokeWidth(ui.dp(c == resizingColumn ? 2 : 1));
            canvas.drawLine(x[c + 1], top, x[c + 1], top + headerHeight, paint);
        }
        for (int r = 0; r < table.rows; r++) {
            if (y[r + 1] <= top + headerHeight || y[r] >= visible.bottom) continue;
            drawCentered(canvas, Integer.toString(r + 1), left, left + gutter,
                    Math.max(y[r], top + headerHeight), y[r + 1]);
            paint.setColor(r == resizingRow ? ScanUi.ACCENT : ScanUi.LINE);
            paint.setStrokeWidth(ui.dp(r == resizingRow ? 2 : 1));
            canvas.drawLine(left, y[r + 1], left + gutter, y[r + 1], paint);
        }
        paint.setColor(ScanUi.SURFACE);
        canvas.drawRect(left, top, left + gutter, top + headerHeight, paint);
    }

    private static String columnName(int column) {
        String address = OfficeExport.address(0, column);
        return address.substring(0, address.length() - 1);
    }

    private void drawCentered(Canvas canvas, String value, int left, int right, int top, int bottom) {
        canvas.drawText(value, (left + right - text.measureText(value)) / 2,
                (top + bottom - text.ascent() - text.descent()) / 2, text);
    }

    private int columnAt(float px) {
        for (int c = 0; c < table.columns; c++) if (px >= x[c] && px < x[c + 1]) return c;
        return -1;
    }

    private int rowAt(float py) {
        for (int r = 0; r < table.rows; r++) if (py >= y[r] && py < y[r + 1]) return r;
        return -1;
    }

    private OfficeTable.Cell hit(float px, float py) {
        Rect body = bodyViewport();
        if (!body.contains((int) px, (int) py)) return null;
        int col = columnAt(px / zoom), row = rowAt(py / zoom);
        return row < 0 || col < 0 ? null : table.cell(row, col);
    }

    private void disallowScroll(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    @Override protected boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaling.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            multiplePointers = true;
            touched = null;
            disallowScroll(true);
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressX = event.getX();
                pressY = event.getY();
                multiplePointers = false;
                resizingColumn = resizingRow = -1;
                Rect clip = viewport();
                float px = pressX / zoom, py = pressY / zoom;
                float margin = ui.dp(10) / zoom;
                if (py <= clip.top / zoom + headerHeight && px >= clip.left / zoom + gutter) {
                    for (int c = 0; c < table.columns; c++) {
                        if (Math.abs(px - x[c + 1]) <= margin) {
                            resizingColumn = c;
                            originalSize = x[c + 1] - x[c];
                            break;
                        }
                    }
                } else if (px <= clip.left / zoom + gutter && py >= clip.top / zoom + headerHeight) {
                    for (int r = 0; r < table.rows; r++) {
                        if (Math.abs(py - y[r + 1]) <= margin) {
                            resizingRow = r;
                            originalSize = y[r + 1] - y[r];
                            break;
                        }
                    }
                }
                touched = resizingColumn >= 0 || resizingRow >= 0 ? null : hit(pressX, pressY);
                if (resizingColumn >= 0 || resizingRow >= 0) disallowScroll(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiplePointers) return true;
                float density = getResources().getDisplayMetrics().density;
                if (resizingColumn >= 0) {
                    table.columnWidths[resizingColumn] = Math.max(OfficeTable.MIN_COLUMN_WIDTH,
                            Math.min(OfficeTable.MAX_COLUMN_WIDTH, (originalSize + (event.getX() - pressX) / zoom) / density));
                    changed = true;
                    geometryChanged();
                } else if (resizingRow >= 0) {
                    table.rowHeights[resizingRow] = Math.max(OfficeTable.MIN_ROW_HEIGHT,
                            Math.min(OfficeTable.MAX_ROW_HEIGHT, (originalSize + (event.getY() - pressY) / zoom) / density));
                    changed = true;
                    geometryChanged();
                } else if (Math.hypot(event.getX() - pressX, event.getY() - pressY) > touchSlop) touched = null;
                return true;
            case MotionEvent.ACTION_UP:
                if (!multiplePointers && touched != null) performClick();
                // Fall through: both completion and cancellation commit the visible dimensions.
            case MotionEvent.ACTION_CANCEL:
                touched = null;
                resizingColumn = resizingRow = -1;
                disallowScroll(false);
                finishChange();
                invalidate();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (touched != null) action.edit(touched, this);
        return true;
    }
}

