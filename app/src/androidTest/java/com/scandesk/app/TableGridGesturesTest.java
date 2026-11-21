package com.scandesk.app;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real injected touch events through the nested scroll containers, not calls to the gesture handlers. */
@RunWith(AndroidJUnit4.class)
public final class TableGridGesturesTest {
    @Test public void dragsRulersPinchesAndStillEditsTheCorrectScaledCell() throws Exception {
        List<OfficeTable.Cell> cells = new ArrayList<>();
        for (int r = 0; r < 12; r++) for (int c = 0; c < 3; c++) {
            OfficeTable.Cell cell = new OfficeTable.Cell(r, c, 1, 1, "v" + r + c);
            cell.type = OfficeTable.CellType.TEXT;
            cells.add(cell);
        }
        OfficeTable table = new OfficeTable("交互样表", 1, 12, 3, false, cells);
        TableGridView[] grid = new TableGridView[1];
        AtomicInteger changes = new AtomicInteger();
        List<OfficeTable.Cell> edited = new ArrayList<>();
        UiAutomation windows = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(activity -> {
                ScanUi ui = new ScanUi(activity);
                grid[0] = new TableGridView(activity, ui, table, (cell, view) -> edited.add(cell));
                grid[0].setChangeListener(changes::incrementAndGet);
                HorizontalScrollView horizontal = new HorizontalScrollView(activity);
                horizontal.addView(grid[0]);
                ScrollView vertical = new ScrollView(activity);
                // This fixture replaces the activity's normal inset-aware editor chrome.
                // Keep injected ruler touches below the system status-bar gesture region.
                vertical.setPadding(0, ui.dp(48), 0, ui.dp(32));
                vertical.setClipToPadding(true);
                vertical.addView(horizontal);
                activity.setContentView(vertical);
            });
            AccessibilityNodeInfo column = node(windows, "A 列，列宽");
            Rect box = new Rect();
            column.getBoundsInScreen(box);
            drag(windows, box.right - 2, box.centerY(), box.right + 84, box.centerY());
            assertTrue("column drag width " + table.columnWidths[0] + " at " + box, table.columnWidths[0] > 120);
            assertTrue(edited.isEmpty());
            AccessibilityNodeInfo row = node(windows, "第 1 行，行高");
            row.getBoundsInScreen(box);
            drag(windows, box.centerX(), box.bottom - 2, box.centerX(), box.bottom + 70);
            assertTrue("row drag increased first row", table.rowHeights[0] > 70);
            assertTrue(edited.isEmpty());
            assertEquals(2, changes.get());
            AccessibilityNodeInfo body = node(windows, "A1 v00");
            body.getBoundsInScreen(box);
            Rect window = new Rect();
            windows.getRootInActiveWindow().getBoundsInScreen(window);
            pinch(windows, window.centerX(), box.centerY() + 50, window.width() * .2f, window.width() * .35f);
            assertTrue("pinch zoom=" + grid[0].getZoom() + ", callbacks=" + changes.get(), grid[0].getZoom() > 1.4f);
            assertEquals(3, changes.get());
            assertTrue("pinch must not open a cell", edited.isEmpty());
            float enlarged = grid[0].getZoom();
            pinch(windows, window.centerX(), window.centerY(), window.width() * .35f, window.width() * .2f);
            assertTrue("reverse pinch decreases zoom", grid[0].getZoom() < enlarged * .85f);
            assertEquals(4, changes.get());
            app.onActivity(activity -> grid[0].reveal(table.cell(7, 2)));
            AccessibilityNodeInfo distant = node(windows, "C8 v72");
            distant.getBoundsInScreen(box);
            tap(windows, box.centerX(), box.centerY());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, edited.size());
            assertSame(table.cell(7, 2), edited.get(0));
            assertNotNull(node(windows, "第 8 行，行高"));
            assertNotNull(node(windows, "C 列，列宽"));
            OfficeTable saved = OfficeTable.from(table.json());
            assertEquals(table.zoom, saved.zoom, .001f);
            assertEquals(table.columnWidths[0], saved.columnWidths[0], .001f);
            assertEquals(table.rowHeights[0], saved.rowHeights[0], .001f);
            assertEquals("v72", saved.cell(7, 2).text);
        }
    }

    private static AccessibilityNodeInfo node(UiAutomation windows, String prefix) {
        long until = SystemClock.elapsedRealtime() + 10000;
        do {
            AccessibilityNodeInfo found = find(windows.getRootInActiveWindow(), prefix);
            if (found != null) return found;
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("Missing " + prefix);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String prefix) {
        if (root == null) return null;
        if (String.valueOf(root.getContentDescription()).startsWith(prefix)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(root.getChild(i), prefix);
            if (found != null) return found;
        }
        return null;
    }

    private static void drag(UiAutomation windows, float x1, float y1, float x2, float y2) {
        long down = SystemClock.uptimeMillis();
        single(windows, down, MotionEvent.ACTION_DOWN, x1, y1);
        for (int i = 1; i <= 8; i++) {
            SystemClock.sleep(20);
            single(windows, down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / 8, y1 + (y2 - y1) * i / 8);
        }
        single(windows, down, MotionEvent.ACTION_UP, x2, y2);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void tap(UiAutomation windows, float x, float y) {
        long down = SystemClock.uptimeMillis();
        single(windows, down, MotionEvent.ACTION_DOWN, x, y);
        single(windows, down, MotionEvent.ACTION_UP, x, y);
    }

    private static void single(UiAutomation windows, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(windows.injectInputEvent(event, true));
        event.recycle();
    }

    private static void pinch(UiAutomation windows, float x, float y, float first, float last) {
        long down = SystemClock.uptimeMillis();
        single(windows, down, MotionEvent.ACTION_DOWN, x - first, y);
        two(windows, down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y, first);
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(20);
            two(windows, down, MotionEvent.ACTION_MOVE, x, y, first + (last - first) * i / 12);
        }
        two(windows, down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y, last);
        single(windows, down, MotionEvent.ACTION_UP, x - last, y);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void two(UiAutomation windows, long down, int action, float x, float y, float span) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = x + (i == 0 ? -span : span);
            coords[i].y = y;
            coords[i].pressure = coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        assertTrue(windows.injectInputEvent(event, true));
        event.recycle();
    }
}

