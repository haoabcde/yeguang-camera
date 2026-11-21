package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class TableViewportFlowTest {
    private ScanStore store;
    private ScanDocument doc;
    private SharedPreferences preferences;
    private Map<String, ?> selection;
    private UiAutomation windows;

    @Before public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        store = new ScanStore(context);
        preferences = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        selection = preferences.getAll();
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
        doc = store.newDocument().withPages(List.of(page));
        store.save(doc);
        Bitmap paper = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(store.image(doc, page))) {
            paper.eraseColor(Color.WHITE);
            assertTrue(paper.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            paper.recycle();
        }
        for (String key : List.of(ScanRecognition.EXCEL, ScanRecognition.HAND_TABLE)) {
            OfficeTable sheet = new OfficeTable("样表", 1, 2, 2, false, List.of(
                    new OfficeTable.Cell(0, 0, 1, 1, "编号"), new OfficeTable.Cell(0, 1, 1, 1, "项目"),
                    new OfficeTable.Cell(1, 0, 1, 1, "001"), new OfficeTable.Cell(1, 1, 1, 1, "样张")));
            sheet.columnWidths[0] = 168;
            sheet.rowHeights[1] = 72;
            store.saveReview(doc, key, new ScanRecognition.TableDraft(List.of(sheet), List.of()).json());
        }
        assertTrue(preferences.edit().putString("document", doc.id).putString("page", page.id)
                .putBoolean("crop", false).commit());
        windows = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @After public void cleanUp() throws Exception {
        SharedPreferences.Editor restore = preferences.edit().putString("document", (String) selection.get("document"))
                .putString("page", (String) selection.get("page"));
        if (selection.containsKey("crop")) restore.putBoolean("crop", (boolean) selection.get("crop"));
        else restore.remove("crop");
        restore.commit();
        store.delete(doc);
    }

    @Test public void controlsPersistPerDraftAcrossRotationRecreationAndReopening() throws Exception {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            open("Excel 表格");
            tap("放大表格");
            node("125%");
            assertEquals(1.25f, saved(ScanRecognition.EXCEL).zoom, .001f);
            tap("缩小表格");
            node("100%");
            tap("缩小表格");
            node("75%");
            tap("75%");
            node("100%");
            tap("放大表格");
            tap("校对操作");
            tap("恢复默认行列大小");
            OfficeTable print = saved(ScanRecognition.EXCEL);
            assertEquals(0, print.columnWidths[0], 0);
            assertEquals(0, print.rowHeights[1], 0);
            assertEquals(1.25f, print.zoom, .001f);

            tap("返回");
            open("手写转 Excel");
            node("100%");
            assertEquals(168, saved(ScanRecognition.HAND_TABLE).columnWidths[0], 0);
            tap("缩小表格");
            node("75%");
            AtomicInteger priorOrientation = new AtomicInteger();
            AtomicInteger target = new AtomicInteger();
            app.onActivity(a -> {
                priorOrientation.set(a.getRequestedOrientation());
                boolean portrait = a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                target.set(portrait ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT);
                a.setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            });
            try {
                long deadline = SystemClock.elapsedRealtime() + 10000;
                AtomicInteger actual = new AtomicInteger();
                do {
                    app.onActivity(a -> actual.set(a.getResources().getConfiguration().orientation));
                    if (actual.get() == target.get()) break;
                    SystemClock.sleep(60);
                } while (SystemClock.elapsedRealtime() < deadline);
                assertEquals("屏幕已旋转", target.get(), actual.get());
                node("75%");
                app.recreate();
                node("校对手写表格");
                node("75%");
            } finally {
                app.onActivity(a -> a.setRequestedOrientation(priorOrientation.get()));
            }
            tap("返回");
            open("Excel 表格");
            node("125%");
            OfficeTable hand = saved(ScanRecognition.HAND_TABLE);
            assertEquals(.75f, hand.zoom, .001f);
            assertEquals(72, hand.rowHeights[1], 0);
        }
    }

    @Test public void failedWriteRollsBackZoomAndSizesBeforeTheNextSave() throws Exception {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            open("Excel 表格");
            tap("放大表格");
            node("125%");
            File directory = store.image(doc, doc.pages.get(0)).getParentFile();
            File backup = new File(directory.getParentFile(), "viewport-test-" + doc.id);
            assertTrue(directory.renameTo(backup));
            try {
                assertTrue(directory.createNewFile());
                tap("放大表格");
                node("125%");
                tap("校对操作");
                tap("恢复默认行列大小");
            } finally {
                if (directory.isFile()) assertTrue(directory.delete());
                assertTrue(backup.renameTo(directory));
            }
            tap("缩小表格");
            node("100%");
            OfficeTable result = saved(ScanRecognition.EXCEL);
            assertEquals(1, result.zoom, .001f);
            assertEquals(168, result.columnWidths[0], 0);
            assertEquals(72, result.rowHeights[1], 0);
        }
    }

    private OfficeTable saved(String key) throws Exception {
        return ScanRecognition.TableDraft.from(store.loadReview(doc, key)).tables.get(0);
    }

    private void open(String format) {
        tap("导出");
        tap(format);
        node("导出 Excel");
    }

    private void tap(String label) {
        AccessibilityNodeInfo item = node(label);
        while (!item.isClickable() && item.getParent() != null) item = item.getParent();
        assertTrue(label, item.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private AccessibilityNodeInfo node(String label) {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        do {
            AccessibilityNodeInfo item = find(windows.getRootInActiveWindow(), label);
            if (item != null) return item;
            SystemClock.sleep(60);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("找不到 " + label);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String label) {
        if (root == null) return null;
        String description = String.valueOf(root.getContentDescription());
        if (label.equals(String.valueOf(root.getText())) || label.equals(description)
                || description.startsWith(label + "，")) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = find(root.getChild(i), label);
            if (child != null) return child;
        }
        return null;
    }
}
